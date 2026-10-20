package com.park.ecommerce.order;

import com.park.ecommerce.order.domain.Order;
import com.park.ecommerce.order.domain.OrderRepository;
import com.park.ecommerce.order.domain.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

// 주문 상태 전이 경합은 조건부 UPDATE와 행 잠금이 핵심이라 실제 MySQL에서 동시에 실행해 확인
@Testcontainers
@SpringBootTest(properties = { // 테스트 중 스케줄러 개입 방지
        "order.payment-waiting.expire-poll-delay=1h",
        "order.approval-recovery.poll-delay=1h"
})
class OrderTransitionConcurrencyTest {
    private static final String PAYMENT_KEY = "tgen_pay_0001";

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.0");

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("승인 시작과 결제 기한 만료 취소가 같은 주문에 동시에 실행되면 한쪽만 반영된다 - 결제 기한 경계에서 승인 요청과 만료 스케줄러가 겹친 경우")
    void appliesOnlyOneWhenStartApprovalAndCancelExpiredRace() throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(10);
            String orderNo = paymentWaitingOrder(expiresAt);
            AtomicInteger started = new AtomicInteger();
            AtomicInteger cancelled = new AtomicInteger();

            // 두 쪽에 서로 다른 시각을 넘겨 시간 조건은 모두 참으로 만든다 - 행 잠금과 상태 조건만으로 승패가 갈리는지 확인하기 위해
            List<Throwable> errors = runConcurrently(List.of(
                    () -> started.set(transactionTemplate.execute(status ->
                            orderRepository.startApproval(orderNo, PAYMENT_KEY, expiresAt.minusSeconds(1)))),
                    () -> cancelled.set(transactionTemplate.execute(status ->
                            orderRepository.cancelExpired(expiresAt.plusSeconds(1))))
            ));

            assertThat(errors).isEmpty();
            assertThat(started.get() + cancelled.get()).isEqualTo(1);
            assertThat(statusOf(orderNo)).isEqualTo(started.get() == 1 ? OrderStatus.APPROVING : OrderStatus.CANCELLED);
        }
    }

    private String paymentWaitingOrder(LocalDateTime expiresAt) {
        Order order = Order.builder()
                .memberId(1L)
                .orderedAt(LocalDateTime.now())
                .expiresAt(expiresAt)
                .build();
        order.addLine(1L, "유기농 우유 900ml", 3_900, 2);
        orderRepository.save(order);
        return order.getOrderNo();
    }

    private OrderStatus statusOf(String orderNo) {
        return orderRepository.findByOrderNo(orderNo).map(Order::getStatus).orElseThrow();
    }

    // 작업들을 최대한 같은 순간에 시작시키고, 발생한 예외를 모아 돌려준다
    private static List<Throwable> runConcurrently(List<Runnable> tasks) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(tasks.size());
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (Runnable task : tasks) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    task.run();
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        return errors;
    }
}
