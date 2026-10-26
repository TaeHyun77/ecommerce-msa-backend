package com.park.ecommerce.toss;

import com.park.ecommerce.config.RestClientConfig;
import com.park.ecommerce.toss.dto.TossPaymentResponse;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 토스페이먼츠 API 규격(인증 헤더, 멱등키, 요청·응답 형식)은 실제 HTTP를 거쳐야 검증되므로 JDK 내장 서버로 토스를 흉내 낸다
class TossPaymentsClientTest {
    private static final String DONE_PAYMENT = """
            {
              "mId": "tosspayments",
              "paymentKey": "tgen_pay_0001",
              "orderId": "ORDER-0001",
              "orderName": "유기농 우유 900ml",
              "status": "DONE",
              "method": "카드",
              "totalAmount": 7800,
              "requestedAt": "2026-09-22T12:00:00+09:00",
              "approvedAt": "2026-09-22T12:00:05+09:00"
            }
            """;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private HttpServer server;
    private TossPaymentsClient tossPaymentsClient;

    private int responseStatus;
    private String responseBody = "";
    private String receivedRequest;
    private String receivedAuthorization;
    private String receivedIdempotencyKey;
    private String receivedBody;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            receivedRequest = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
            receivedAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            receivedIdempotencyKey = exchange.getRequestHeaders().getFirst("Idempotency-Key");
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
        RestClient restClient = new RestClientConfig().tossPaymentsRestClient(RestClient.builder(), baseUrl, "test_sk_dummy");
        tossPaymentsClient = new TossPaymentsClient(restClient);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("승인을 요청하면 시크릿 키 Basic 인증과 주문번호 멱등키를 붙여 결제 키·주문번호·금액을 보낸다")
    void sendsConfirmInTossFormat() {
        responseStatus = 200;
        responseBody = DONE_PAYMENT;

        tossPaymentsClient.confirm("tgen_pay_0001", "ORDER-0001", 7_800);

        assertThat(receivedRequest).isEqualTo("POST /v1/payments/confirm");
        assertThat(receivedAuthorization).isEqualTo("Basic dGVzdF9za19kdW1teTo="); // base64("test_sk_dummy:")
        assertThat(receivedIdempotencyKey).isEqualTo("ORDER-0001");
        assertThat(jsonMapper.readTree(receivedBody)).isEqualTo(jsonMapper.readTree("""
                { "paymentKey": "tgen_pay_0001", "orderId": "ORDER-0001", "amount": 7800 }
                """));
    }

    @Test
    @DisplayName("승인 응답에서 결제 상태·수단·승인 시각을 읽는다 - 쓰지 않는 필드가 있어도 무시한다")
    void readsConfirmedPayment() {
        responseStatus = 200;
        responseBody = DONE_PAYMENT;

        TossPaymentResponse payment = tossPaymentsClient.confirm("tgen_pay_0001", "ORDER-0001", 7_800);

        assertThat(payment.status()).isEqualTo("DONE");
        assertThat(payment.method()).isEqualTo("카드");
        assertThat(payment.approvedAt()).isEqualTo(OffsetDateTime.of(2026, 9, 22, 12, 0, 5, 0, ZoneOffset.ofHours(9)));
    }

    @Test
    @DisplayName("오류 응답을 받으면 토스 에러 코드와 메시지를 담은 예외를 던진다")
    void throwsWithTossErrorCode() {
        responseStatus = 400;
        responseBody = """
                { "code": "REJECT_CARD_COMPANY", "message": "카드사에서 거절했습니다." }
                """;

        assertThatThrownBy(() -> tossPaymentsClient.confirm("tgen_pay_0001", "ORDER-0001", 7_800))
                .isInstanceOf(TossPaymentsException.class)
                .hasMessage("카드사에서 거절했습니다.")
                .extracting("code")
                .isEqualTo("REJECT_CARD_COMPANY");
    }

    @Test
    @DisplayName("결제를 조회하면 결제 키로 조회해 현재 결제 상태를 읽는다")
    void findsPaymentByPaymentKey() {
        responseStatus = 200;
        responseBody = """
                { "paymentKey": "tgen_pay_0001", "orderId": "ORDER-0001", "status": "IN_PROGRESS", "totalAmount": 7800, "approvedAt": null }
                """;

        TossPaymentResponse payment = tossPaymentsClient.find("tgen_pay_0001");

        assertThat(receivedRequest).isEqualTo("GET /v1/payments/tgen_pay_0001");
        assertThat(receivedAuthorization).isEqualTo("Basic dGVzdF9za19kdW1teTo=");
        assertThat(payment.status()).isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("토스에 연결할 수 없으면 에러 코드 없이 응답 없음 예외를 던진다 - 결과를 알 수 없는 경우")
    void throwsWithoutCodeWhenUnreachable() {
        server.stop(0);

        assertThatThrownBy(() -> tossPaymentsClient.find("tgen_pay_0001"))
                .isInstanceOf(TossPaymentsException.class)
                .extracting("code", "noResponse")
                .containsExactly(null, true);
    }
}
