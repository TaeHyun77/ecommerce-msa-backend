package com.park.ecommerce.promotion;

import com.park.ecommerce.client.promotion.PromotionApiClient;
import com.park.ecommerce.client.promotion.dto.CouponReservationRequest;
import com.park.ecommerce.config.RestClientConfig;
import com.park.ecommerce.exception.OrderErrorCode;
import com.park.ecommerce.exception.OrderException;
import com.park.ecommerce.exception.PromotionServiceUnavailableException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 서비스 간 계약(요청 형식, 오류 응답 변환)은 실제 HTTP를 거쳐야 검증되므로 JDK 내장 서버로 promotion-service를 흉내 낸다
class PromotionApiClientTest {
    private static final CouponReservationRequest REQUEST = new CouponReservationRequest(
            10L, 1L, "ORDER-0001", 7_800, LocalDateTime.of(2026, 9, 22, 12, 10)
    );

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private HttpServer server;
    private PromotionApiClient promotionApiClient;

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
        RestClient restClient = new RestClientConfig().promotionServiceRestClient(RestClient.builder(), baseUrl);
        promotionApiClient = new PromotionApiClient(restClient);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("쿠폰 선점을 요청하면 promotion-service 계약 형식으로 보내고 응답의 할인액을 돌려준다")
    void sendsReservationInContractFormat() {
        responseStatus = 200;
        responseBody = """
                { "discountAmount": 2000 }
                """;

        int discountAmount = promotionApiClient.reserveCoupon(REQUEST);

        assertThat(discountAmount).isEqualTo(2_000);
        assertThat(receivedRequest).isEqualTo("POST /internal/coupon-reservations");
        assertThat(jsonMapper.readTree(receivedBody)).isEqualTo(jsonMapper.readTree("""
                {
                  "memberCouponId": 10,
                  "memberId": 1,
                  "orderNo": "ORDER-0001",
                  "orderAmount": 7800,
                  "expiresAt": "2026-09-22T12:10:00"
                }
                """));
    }

    @ParameterizedTest(name = "{0} 응답은 {1}")
    @CsvSource({
            "404, COUPON_NOT_FOUND",
            "400, COUPON_NOT_IN_USE_PERIOD",
            "409, COUPON_NOT_AVAILABLE"
    })
    @DisplayName("쓸 수 없는 쿠폰이라 4xx를 받으면 쿠폰 상태에 맞는 예외로 바꾼다")
    void convertsClientErrorToCouponError(int status, OrderErrorCode errorCode) {
        responseStatus = status;
        responseBody = """
                { "code": "ANY", "message": "쿠폰 오류" }
                """;

        assertThatThrownBy(() -> promotionApiClient.reserveCoupon(REQUEST))
                .isInstanceOf(OrderException.class)
                .extracting("errorCode")
                .isEqualTo(errorCode);
    }

    @Test
    @DisplayName("쿠폰 확정에 성공하면 true를 돌려준다")
    void confirmsCoupon() {
        responseStatus = 204;

        boolean confirmed = promotionApiClient.confirmCoupon("ORDER-0001");

        assertThat(receivedRequest).isEqualTo("POST /internal/coupon-reservations/ORDER-0001/confirm");
        assertThat(confirmed).isTrue();
    }

    @Test
    @DisplayName("쿠폰 선점이 만료·해제돼 409를 받으면 false를 돌려준다 - 장애와 구분해 결제를 막는 근거로 쓴다")
    void returnsFalseWhenCouponReservationExpired() {
        responseStatus = 409;
        responseBody = """
                { "code": "COUPON_RESERVATION_EXPIRED", "message": "결제 기한이 지나 쿠폰을 사용할 수 없습니다." }
                """;

        assertThat(promotionApiClient.confirmCoupon("ORDER-0001")).isFalse();
    }

    @Test
    @DisplayName("쿠폰 해제를 요청하면 주문번호로 해제 API를 호출한다")
    void releasesCoupon() {
        responseStatus = 204;

        promotionApiClient.releaseCoupon("ORDER-0001");

        assertThat(receivedRequest).isEqualTo("POST /internal/coupon-reservations/ORDER-0001/release");
    }

    @Test
    @DisplayName("promotion-service가 5xx로 응답하면 쿠폰 상태가 아닌 장애 예외로 바꾼다")
    void convertsServerErrorToUnavailable() {
        responseStatus = 500;

        assertThatThrownBy(() -> promotionApiClient.reserveCoupon(REQUEST))
                .isInstanceOf(PromotionServiceUnavailableException.class);
        assertThatThrownBy(() -> promotionApiClient.confirmCoupon("ORDER-0001"))
                .isInstanceOf(PromotionServiceUnavailableException.class);
    }

    @Test
    @DisplayName("promotion-service에 연결할 수 없으면 장애 예외로 바꾼다")
    void convertsConnectionFailureToUnavailable() {
        server.stop(0);

        assertThatThrownBy(() -> promotionApiClient.reserveCoupon(REQUEST))
                .isInstanceOf(PromotionServiceUnavailableException.class);
    }
}
