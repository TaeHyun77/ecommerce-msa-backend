package com.park.ecommerce.search;

import com.park.ecommerce.client.product.ProductApiClient;
import com.park.ecommerce.config.RestClientConfig;
import com.park.ecommerce.exception.ProductServiceUnavailableException;
import com.park.ecommerce.exception.SearchErrorCode;
import com.park.ecommerce.exception.SearchException;
import com.park.ecommerce.search.dto.ProductSummaryResponse;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 서비스 간 계약(요청 형식, 오류 응답 변환)은 실제 HTTP를 거쳐야 검증되므로 JDK 내장 서버로 product-service를 흉내 낸다
class ProductApiClientTest {
    private HttpServer server;
    private ProductApiClient productApiClient;

    private int responseStatus;
    private String responseBody = "";
    private String receivedRequest;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            receivedRequest = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + "?" + exchange.getRequestURI().getRawQuery();

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
    @DisplayName("상품 식별자 목록으로 조회하면 내부 API 계약 형식으로 요청하고 응답을 변환한다")
    void findsProductsInContractFormat() {
        responseStatus = 200;
        responseBody = """
                [ { "productId": 1, "name": "노르웨이 고등어", "brand": "컬리", "price": 14900, "storageType": "FROZEN",
                    "thumbnailUrl": null, "categoryId": 2, "status": "ON_SALE", "availableQuantity": 5 } ]
                """;

        List<ProductSummaryResponse> products = productApiClient.findProducts(List.of(1L, 2L));

        assertThat(receivedRequest).isEqualTo("GET /internal/products?ids=1&ids=2");
        assertThat(products).containsExactly(new ProductSummaryResponse(
                1L, "노르웨이 고등어", "컬리", 14_900, "FROZEN", null, 2L, "ON_SALE", 5
        ));
    }

    @Test
    @DisplayName("product-service가 5xx로 응답하면 서킷 브레이커가 집계하는 장애 예외로 바꾼다")
    void convertsServerErrorToUnavailable() {
        responseStatus = 500;

        assertThatThrownBy(() -> productApiClient.findProducts(List.of(1L)))
                .isInstanceOf(ProductServiceUnavailableException.class);
    }

    @Test
    @DisplayName("4xx는 호출 계약 오류라 서킷 브레이커가 집계하지 않도록 장애 예외가 아닌 검색 예외로 바꾼다")
    void convertsClientErrorToSearchException() {
        responseStatus = 400;

        assertThatThrownBy(() -> productApiClient.findProducts(List.of(1L)))
                .isInstanceOf(SearchException.class)
                .isNotInstanceOf(ProductServiceUnavailableException.class)
                .extracting("errorCode")
                .isEqualTo(SearchErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("product-service에 연결할 수 없으면 장애 예외로 바꾼다")
    void convertsConnectionFailureToUnavailable() {
        server.stop(0);

        assertThatThrownBy(() -> productApiClient.findProducts(List.of(1L)))
                .isInstanceOf(ProductServiceUnavailableException.class);
    }
}
