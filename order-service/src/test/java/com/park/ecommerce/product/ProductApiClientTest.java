package com.park.ecommerce.product;

import com.park.ecommerce.client.product.ProductApiClient;
import com.park.ecommerce.config.RestClientConfig;
import com.park.ecommerce.exception.OrderErrorCode;
import com.park.ecommerce.exception.OrderException;
import com.park.ecommerce.exception.ProductServiceUnavailableException;
import com.park.ecommerce.client.product.dto.StockReservationRequest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 서비스 간 계약(요청 형식, 오류 응답 변환)은 실제 HTTP를 거쳐야 검증되므로 JDK 내장 서버로 product-service를 흉내 낸다
class ProductApiClientTest {
    private static final StockReservationRequest REQUEST = new StockReservationRequest(
            "ORDER-0001",
            LocalDateTime.of(2026, 9, 22, 12, 10),
            List.of(new StockReservationRequest.Item(1L, 2), new StockReservationRequest.Item(2L, 1))
    );

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private HttpServer server;
    private ProductApiClient productApiClient;

    private int responseStatus;
    private String responseBody = "";
    private String receivedRequest;
    private String receivedBody;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            receivedRequest = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
            receivedBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();

        String baseUrl = "http://localhost:" + server.getAddress().getPort();
        RestClient restClient = new RestClientConfig().productServiceRestClient(RestClient.builder(), baseUrl);
        productApiClient = new ProductApiClient(restClient);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("선점을 요청하면 주문번호·만료 시각·품목을 product-service 계약 형식으로 보낸다")
    void sendsReservationInContractFormat() {
        responseStatus = 204;

        productApiClient.reserve(REQUEST);

        assertThat(receivedRequest).isEqualTo("POST /internal/stock-reservations");
        assertThat(jsonMapper.readTree(receivedBody)).isEqualTo(jsonMapper.readTree("""
                {
                  "orderNo": "ORDER-0001",
                  "expiresAt": "2026-09-22T12:10:00",
                  "items": [ { "productId": 1, "quantity": 2 }, { "productId": 2, "quantity": 1 } ]
                }
                """));
    }

    @Test
    @DisplayName("재고가 부족해 409를 받으면 재고 부족 예외로 바꾼다")
    void convertsConflictToInsufficientStock() {
        responseStatus = 409;
        responseBody = """
                { "code": "INSUFFICIENT_STOCK", "message": "재고가 부족합니다." }
                """;

        assertThatThrownBy(() -> productApiClient.reserve(REQUEST))
                .isInstanceOf(OrderException.class)
                .extracting("errorCode")
                .isEqualTo(OrderErrorCode.INSUFFICIENT_STOCK);
    }

    @Test
    @DisplayName("선점 확정에 성공하면 true를 돌려준다")
    void confirmsReservation() {
        responseStatus = 204;

        boolean confirmed = productApiClient.confirmReservation("ORDER-0001");

        assertThat(receivedRequest).isEqualTo("POST /internal/stock-reservations/ORDER-0001/confirm");
        assertThat(confirmed).isTrue();
    }

    @Test
    @DisplayName("선점이 만료·해제돼 409를 받으면 false를 돌려준다 - 장애와 구분해 결제를 막는 근거로 쓴다")
    void returnsFalseWhenReservationExpired() {
        responseStatus = 409;
        responseBody = """
                { "code": "RESERVATION_EXPIRED", "message": "재고 선점이 만료되었거나 해제되었습니다." }
                """;

        assertThat(productApiClient.confirmReservation("ORDER-0001")).isFalse();
    }

    @Test
    @DisplayName("선점 확정 중 product-service가 5xx로 응답하면 만료가 아닌 장애 예외로 바꾼다")
    void convertsServerErrorOnConfirmToUnavailable() {
        responseStatus = 500;

        assertThatThrownBy(() -> productApiClient.confirmReservation("ORDER-0001"))
                .isInstanceOf(ProductServiceUnavailableException.class);
    }

    @Test
    @DisplayName("선점 해제를 요청하면 주문번호로 해제 API를 호출한다")
    void releasesReservation() {
        responseStatus = 204;

        productApiClient.releaseReservation("ORDER-0001");

        assertThat(receivedRequest).isEqualTo("POST /internal/stock-reservations/ORDER-0001/release");
    }

    @Test
    @DisplayName("product-service가 5xx로 응답하면 상품 서비스 장애 예외로 바꾼다")
    void convertsServerErrorToUnavailable() {
        responseStatus = 500;

        assertThatThrownBy(() -> productApiClient.reserve(REQUEST))
                .isInstanceOf(ProductServiceUnavailableException.class);
    }

    @Test
    @DisplayName("product-service에 연결할 수 없으면 상품 서비스 장애 예외로 바꾼다")
    void convertsConnectionFailureToUnavailable() {
        server.stop(0);

        assertThatThrownBy(() -> productApiClient.reserve(REQUEST))
                .isInstanceOf(ProductServiceUnavailableException.class);
    }
}
