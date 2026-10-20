package com.park.ecommerce.payment;

import com.park.ecommerce.client.payment.PaymentApiClient;
import com.park.ecommerce.exception.PaymentResultUnknownException;
import com.park.ecommerce.client.payment.dto.PaymentApproveRequest;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 서킷 브레이커는 스프링 AOP와 설정값으로 동작하므로, 실제 컨텍스트에서 JDK 내장 서버로 payment-service 장애를 흉내 내 확인한다
@Testcontainers
@SpringBootTest(properties = { // 테스트 중 스케줄러 개입 방지
        "order.payment-waiting.expire-poll-delay=1h",
        "order.approval-recovery.poll-delay=1h"
})
class PaymentApiClientCircuitBreakerTest {
    private static final PaymentApproveRequest REQUEST = new PaymentApproveRequest("ORDER-0001", "tgen_pay_0001", 7_800);
    private static final int MINIMUM_NUMBER_OF_CALLS = 10; // 서킷이 실패율을 판단하기 시작하는 호출 수 (application.properties)

    private static final AtomicInteger RESPONSE_STATUS = new AtomicInteger();
    private static final AtomicInteger RECEIVED_REQUESTS = new AtomicInteger();
    // 컨텍스트가 뜨기 전에 주소가 정해져야 하므로 클래스 로딩 시점에 서버를 띄운다
    private static final HttpServer PAYMENT_SERVICE = startPaymentService();

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.0");

    @Autowired
    private PaymentApiClient paymentApiClient;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @DynamicPropertySource
    static void paymentServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("external.payment-service.base-url", () -> "http://localhost:" + PAYMENT_SERVICE.getAddress().getPort());
    }

    @AfterAll
    static void stopPaymentService() {
        PAYMENT_SERVICE.stop(0);
    }

    // 테스트끼리 같은 서킷을 공유하므로 매번 닫힌 상태에서 시작한다
    @BeforeEach
    void resetCircuit() {
        circuitBreakerRegistry.circuitBreaker("paymentService").reset();
        RECEIVED_REQUESTS.set(0);
    }

    @Test
    @DisplayName("payment-service가 연달아 5xx로 응답하면 서킷이 열려, 이후 호출은 보내지 않고 바로 거절한다")
    void opensCircuitOnConsecutiveServerErrors() {
        RESPONSE_STATUS.set(503);
        for (int i = 0; i < MINIMUM_NUMBER_OF_CALLS; i++) {
            assertThatThrownBy(() -> paymentApiClient.approve(REQUEST)).isInstanceOf(PaymentResultUnknownException.class);
        }
        int requestsBeforeOpen = RECEIVED_REQUESTS.get();

        assertThatThrownBy(() -> paymentApiClient.approve(REQUEST)).isInstanceOf(CallNotPermittedException.class);
        assertThat(RECEIVED_REQUESTS.get()).isEqualTo(requestsBeforeOpen);
    }

    @Test
    @DisplayName("호출 계약 오류(4xx)는 payment-service 장애가 아니므로 서킷을 열지 않는다")
    void keepsCircuitClosedOnContractErrors() {
        RESPONSE_STATUS.set(409);
        for (int i = 0; i <= MINIMUM_NUMBER_OF_CALLS; i++) {
            assertThatThrownBy(() -> paymentApiClient.approve(REQUEST))
                    .isInstanceOf(PaymentResultUnknownException.class)
                    .isNotInstanceOf(CallNotPermittedException.class);
        }

        assertThat(RECEIVED_REQUESTS.get()).isEqualTo(MINIMUM_NUMBER_OF_CALLS + 1);
    }

    private static HttpServer startPaymentService() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                RECEIVED_REQUESTS.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                byte[] body = """
                        { "code": "PAYMENT_ERROR", "message": "오류" }
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(RESPONSE_STATUS.get(), body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
