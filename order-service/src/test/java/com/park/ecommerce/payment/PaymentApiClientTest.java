package com.park.ecommerce.payment;

import com.park.ecommerce.client.payment.PaymentApiClient;
import com.park.ecommerce.config.RestClientConfig;
import com.park.ecommerce.exception.PaymentResultUnknownException;
import com.park.ecommerce.client.payment.dto.PaymentApproveRequest;
import com.park.ecommerce.client.payment.dto.PaymentApproveResponse;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 서비스 간 계약(요청 형식, 결과 불명 신호)은 실제 HTTP를 거쳐야 검증되므로 JDK 내장 서버로 payment-service를 흉내 낸다
class PaymentApiClientTest {
    private static final PaymentApproveRequest REQUEST = new PaymentApproveRequest("ORDER-0001", "tgen_pay_0001", 7_800);

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private HttpServer server;
    private PaymentApiClient paymentApiClient;

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
        RestClient restClient = new RestClientConfig().paymentServiceRestClient(RestClient.builder(), baseUrl);
        paymentApiClient = new PaymentApiClient(restClient);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("승인을 요청하면 주문번호·결제 키·금액을 payment-service 계약 형식으로 보낸다")
    void sendsApproveInContractFormat() {
        responseStatus = 200;
        responseBody = """
                { "orderNo": "ORDER-0001", "status": "DONE", "failReason": null }
                """;

        paymentApiClient.approve(REQUEST);

        assertThat(receivedRequest).isEqualTo("POST /internal/payments/approve");
        assertThat(jsonMapper.readTree(receivedBody)).isEqualTo(jsonMapper.readTree("""
                { "orderNo": "ORDER-0001", "paymentKey": "tgen_pay_0001", "amount": 7800 }
                """));
    }

    @Test
    @DisplayName("승인 완료 응답은 완료로, 승인 실패 응답은 완료가 아닌 것으로 읽는다")
    void readsConfirmedResult() {
        responseStatus = 200;
        responseBody = """
                { "orderNo": "ORDER-0001", "status": "DONE", "failReason": null }
                """;
        PaymentApproveResponse done = paymentApiClient.approve(REQUEST);

        responseBody = """
                { "orderNo": "ORDER-0001", "status": "FAILED", "failReason": "ABORTED / REJECT_CARD_COMPANY: 카드사에서 거절했습니다." }
                """;
        PaymentApproveResponse failed = paymentApiClient.approve(REQUEST);

        assertThat(done.isDone()).isTrue();
        assertThat(failed.isDone()).isFalse();
        assertThat(failed.failReason()).isEqualTo("ABORTED / REJECT_CARD_COMPANY: 카드사에서 거절했습니다.");
    }

    @Test
    @DisplayName("payment-service가 결과 불명(503)으로 응답하면 결과 불명 예외를 던진다")
    void throwsUnknownWhenPaymentResultUnknown() {
        responseStatus = 503;
        responseBody = """
                { "code": "PAYMENT_RESULT_UNKNOWN", "message": "결제 결과를 확인하지 못했습니다. 잠시 후 다시 확인해주세요." }
                """;

        assertThatThrownBy(() -> paymentApiClient.approve(REQUEST))
                .isInstanceOf(PaymentResultUnknownException.class);
    }

    @Test
    @DisplayName("호출 계약 오류(4xx)도 결과를 알 수 없으므로 결과 불명 예외를 던진다")
    void throwsUnknownOnContractError() {
        responseStatus = 409;
        responseBody = """
                { "code": "PAYMENT_REQUEST_MISMATCH", "message": "이미 다른 결제 정보로 승인을 요청한 주문입니다." }
                """;

        assertThatThrownBy(() -> paymentApiClient.approve(REQUEST))
                .isInstanceOf(PaymentResultUnknownException.class);
    }

    @Test
    @DisplayName("payment-service에 연결할 수 없으면 결과 불명 예외를 던진다")
    void throwsUnknownWhenUnreachable() {
        server.stop(0);

        assertThatThrownBy(() -> paymentApiClient.approve(REQUEST))
                .isInstanceOf(PaymentResultUnknownException.class);
    }
}
