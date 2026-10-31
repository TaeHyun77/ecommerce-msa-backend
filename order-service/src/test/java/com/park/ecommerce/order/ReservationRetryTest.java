package com.park.ecommerce.order;

import com.park.ecommerce.client.product.ProductApiClient;
import com.park.ecommerce.client.product.dto.StockReservationRequest;
import com.park.ecommerce.client.promotion.PromotionApiClient;
import com.park.ecommerce.client.promotion.dto.CouponReservationRequest;
import com.park.ecommerce.exception.OrderErrorCode;
import com.park.ecommerce.exception.OrderException;
import com.park.ecommerce.exception.ProductServiceUnavailableException;
import com.sun.net.httpserver.HttpExchange;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 재시도는 스프링 AOP와 설정값으로 동작하고 서킷 브레이커와의 순서도 중요하므로, 실제 컨텍스트에서 JDK 내장 서버로 product·promotion-service 응답을 흉내 내 확인한다
@Testcontainers
@SpringBootTest(properties = { // 테스트 중 스케줄러 개입 방지
        "order.payment-waiting.expire-poll-delay=1h",
        "order.approval-recovery.poll-delay=1h"
})
class ReservationRetryTest {
    private static final int TIMEOUT = -1; // 응답 대신 read timeout(2초)보다 오래 기다리게 하는 표시

    private static final StockReservationRequest STOCK_REQUEST = new StockReservationRequest(
            "ORDER-0001", LocalDateTime.of(2099, 1, 1, 0, 0), List.of(new StockReservationRequest.Item(1L, 2))
    );
    private static final CouponReservationRequest COUPON_REQUEST = new CouponReservationRequest(
            10L, 1L, "ORDER-0001", 7_800, LocalDateTime.of(2099, 1, 1, 0, 0)
    );

    // 요청마다 앞에서부터 하나씩 꺼내 응답할 상태 코드
    private static final Queue<Integer> RESPONSES = new ConcurrentLinkedQueue<>();
    private static final List<String> RECEIVED = Collections.synchronizedList(new ArrayList<>());
    // 컨텍스트가 뜨기 전에 주소가 정해져야 하므로 클래스 로딩 시점에 서버를 띄운다
    private static final HttpServer SERVER = startServer();

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.0");

    @Autowired
    private ProductApiClient productApiClient;

    @Autowired
    private PromotionApiClient promotionApiClient;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @DynamicPropertySource
    static void serviceUrls(DynamicPropertyRegistry registry) {
        String baseUrl = "http://localhost:" + SERVER.getAddress().getPort();
        registry.add("external.product-service.base-url", () -> baseUrl);
        registry.add("external.promotion-service.base-url", () -> baseUrl);
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    // 테스트끼리 같은 서킷을 공유하므로 매번 닫힌 상태에서 시작한다
    @BeforeEach
    void reset() {
        circuitBreakerRegistry.circuitBreaker("productService").reset();
        circuitBreakerRegistry.circuitBreaker("promotionService").reset();
        RESPONSES.clear();
        RECEIVED.clear();
    }

    @Test
    @DisplayName("재고 선점이 5xx로 결과를 모르면 같은 요청을 한 번 더 보내고, 성공하면 정상 종료한다")
    void retriesStockReservationOnServerError() {
        RESPONSES.addAll(List.of(503, 204));

        productApiClient.reserve(STOCK_REQUEST);

        assertThat(RECEIVED).hasSize(2).allMatch(request -> request.contains("\"orderNo\":\"ORDER-0001\""));
    }

    @Test
    @DisplayName("재고 선점이 타임아웃되면 같은 요청을 한 번 더 보낸다 - 첫 요청이 실제로 선점했어도 product-service가 성공으로 응답")
    void retriesStockReservationOnTimeout() {
        RESPONSES.addAll(List.of(TIMEOUT, 204));

        productApiClient.reserve(STOCK_REQUEST);

        assertThat(RECEIVED).hasSize(2);
    }

    @Test
    @DisplayName("재시도까지 결과를 모르면 장애 예외를 던지고, 시도마다 서킷에 실패로 기록된다 - 재시도가 서킷 바깥에서 동작")
    void throwsAfterRetryAndRecordsEachAttempt() {
        RESPONSES.addAll(List.of(503, 503));

        assertThatThrownBy(() -> productApiClient.reserve(STOCK_REQUEST))
                .isInstanceOf(ProductServiceUnavailableException.class);

        assertThat(RECEIVED).hasSize(2);
        assertThat(circuitBreakerRegistry.circuitBreaker("productService").getMetrics().getNumberOfFailedCalls()).isEqualTo(2);
    }

    @Test
    @DisplayName("재고 부족(409)은 다시 보내도 결과가 같으므로 재시도하지 않는다")
    void doesNotRetryInsufficientStock() {
        RESPONSES.add(409);

        assertThatThrownBy(() -> productApiClient.reserve(STOCK_REQUEST))
                .isInstanceOf(OrderException.class)
                .extracting("errorCode")
                .isEqualTo(OrderErrorCode.INSUFFICIENT_STOCK);

        assertThat(RECEIVED).hasSize(1);
    }

    @Test
    @DisplayName("서킷이 열려 있으면 재시도 없이 바로 거절한다")
    void doesNotRetryWhenCircuitOpen() {
        circuitBreakerRegistry.circuitBreaker("productService").transitionToOpenState();
        long startedAt = System.nanoTime();

        assertThatThrownBy(() -> productApiClient.reserve(STOCK_REQUEST))
                .isInstanceOf(CallNotPermittedException.class);

        assertThat(RECEIVED).isEmpty();
        assertThat(System.nanoTime() - startedAt).isLessThan(200_000_000L); // 재시도 대기(200ms) 없이 끝남
    }

    @Test
    @DisplayName("선점 확정은 재시도 대상이 아니다 - 결과 불명은 승인 복구 스케줄러가 다시 처리")
    void doesNotRetryConfirmation() {
        RESPONSES.add(503);

        assertThatThrownBy(() -> productApiClient.confirmReservation("ORDER-0001"))
                .isInstanceOf(ProductServiceUnavailableException.class);

        assertThat(RECEIVED).hasSize(1);
    }

    @Test
    @DisplayName("쿠폰 선점이 5xx로 결과를 모르면 같은 요청을 한 번 더 보내고 응답의 할인액을 돌려준다")
    void retriesCouponReservationOnServerError() {
        RESPONSES.addAll(List.of(503, 200));

        int discountAmount = promotionApiClient.reserveCoupon(COUPON_REQUEST);

        assertThat(discountAmount).isEqualTo(2_000);
        assertThat(RECEIVED).hasSize(2).allMatch(request -> request.contains("\"orderNo\":\"ORDER-0001\""));
    }

    @Test
    @DisplayName("쓸 수 없는 쿠폰(409)은 다시 보내도 결과가 같으므로 재시도하지 않는다")
    void doesNotRetryUnavailableCoupon() {
        RESPONSES.add(409);

        assertThatThrownBy(() -> promotionApiClient.reserveCoupon(COUPON_REQUEST))
                .isInstanceOf(OrderException.class)
                .extracting("errorCode")
                .isEqualTo(OrderErrorCode.COUPON_NOT_AVAILABLE);

        assertThat(RECEIVED).hasSize(1);
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", ReservationRetryTest::respond);
            // 타임아웃을 흉내 내는 요청이 잠든 동안 재시도 요청을 받을 수 있도록 여러 스레드로 처리
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void respond(HttpExchange exchange) throws IOException {
        RECEIVED.add(exchange.getRequestURI().getPath() + " " + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        Integer status = RESPONSES.poll();
        try {
            if (status == null || status == TIMEOUT) {
                Thread.sleep(2_500);
                return; // 호출한 쪽은 이미 타임아웃으로 연결을 끊음
            }
            byte[] body = (status == 200 ? "{ \"discountAmount\": 2000 }" : "{ \"code\": \"ERROR\", \"message\": \"오류\" }")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            if (status == 204) {
                exchange.sendResponseHeaders(204, -1);
            } else {
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }
}
