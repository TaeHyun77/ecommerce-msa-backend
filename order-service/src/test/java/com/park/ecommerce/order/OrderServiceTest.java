package com.park.ecommerce.order;

import com.park.ecommerce.exception.OrderErrorCode;
import com.park.ecommerce.exception.OrderException;
import com.park.ecommerce.order.domain.Order;
import com.park.ecommerce.order.domain.OrderRepository;
import com.park.ecommerce.order.domain.OrderStatus;
import com.park.ecommerce.order.dto.OrderDetailResponse;
import com.park.ecommerce.order.dto.OrderSummaryResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 상태 변경은 조건부 UPDATE의 조건이 핵심이라 실제 MySQL에서 검증
@Testcontainers
@SpringBootTest(properties = {
        "order.payment-waiting.ttl=10m",
        "order.payment-waiting.expire-poll-delay=1h" // 테스트 중 스케줄러 개입 방지 - 만료 스케줄러는 테스트에서 직접 호출
})
class OrderServiceTest {
    private static final Long MEMBER_ID = 1L;

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.0");

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderExpiryScheduler orderExpiryScheduler;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    @DisplayName("본인 주문을 조회하면 상태·금액·품목 스냅샷을 돌려준다")
    void findsOwnOrder() {
        String orderNo = savedOrder(LocalDateTime.now().plusMinutes(10));

        OrderDetailResponse response = orderService.getOrder(MEMBER_ID, orderNo);

        assertThat(response.status()).isEqualTo(OrderStatus.PAYMENT_WAITING);
        assertThat(response.totalAmount()).isEqualTo(10_300);
        assertThat(response.lines()).containsExactly(
                new OrderDetailResponse.Line(1L, "유기농 우유 900ml", 3_900, 2),
                new OrderDetailResponse.Line(2L, "국산콩 두부 300g", 2_500, 1)
        );
    }

    @Test
    @DisplayName("주문 목록은 본인 주문만 최근 주문부터 주문번호·주문일·품목과 함께 돌려준다")
    void findsOwnOrdersNewestFirst() {
        Long memberId = 100L; // 다른 테스트가 만든 주문과 섞이지 않도록 전용 회원 사용
        String older = savedOrder(memberId, LocalDateTime.now().minusMinutes(30));
        String newer = savedOrder(memberId, LocalDateTime.now().minusMinutes(10));
        savedOrder(101L, LocalDateTime.now().minusMinutes(20));

        List<OrderSummaryResponse> responses = orderService.getOrderSummaries(memberId);

        assertThat(responses).extracting(OrderSummaryResponse::orderNo).containsExactly(newer, older);
        assertThat(responses.get(0).lines()).containsExactly(
                new OrderDetailResponse.Line(1L, "유기농 우유 900ml", 3_900, 2),
                new OrderDetailResponse.Line(2L, "국산콩 두부 300g", 2_500, 1)
        );
    }

    @Test
    @DisplayName("주문이 없는 회원의 주문 목록은 비어 있다")
    void returnsEmptyOrdersForMemberWithoutOrders() {
        assertThat(orderService.getOrderSummaries(999L)).isEmpty();
    }

    @Test
    @DisplayName("다른 회원의 주문을 조회하면 주문이 없는 것으로 거절한다")
    void rejectsOtherMembersOrder() {
        String orderNo = savedOrder(LocalDateTime.now().plusMinutes(10));

        assertThatThrownBy(() -> orderService.getOrder(2L, orderNo))
                .isInstanceOf(OrderException.class)
                .extracting("errorCode")
                .isEqualTo(OrderErrorCode.ORDER_NOT_FOUND);
    }

    @Test
    @DisplayName("결제 기한이 지난 결제 대기 주문은 스케줄러가 실패 처리한다")
    void failsExpiredPaymentWaitingOrder() {
        String orderNo = savedOrder(LocalDateTime.now().minusSeconds(1));

        orderExpiryScheduler.failExpiredOrders();

        assertThat(statusOf(orderNo)).isEqualTo(OrderStatus.FAILED);
    }

    @Test
    @DisplayName("결제 기한 전 결제 대기 주문은 스케줄러가 실패 처리하지 않는다")
    void keepsPaymentWaitingOrderBeforeExpiresAt() {
        String orderNo = savedOrder(LocalDateTime.now().plusMinutes(10));

        orderExpiryScheduler.failExpiredOrders();

        assertThat(statusOf(orderNo)).isEqualTo(OrderStatus.PAYMENT_WAITING);
    }

    @Test
    @DisplayName("결제 대기가 아닌 주문은 결제 기한이 지나도 스케줄러가 실패 처리하지 않는다")
    void keepsNonWaitingOrderPastExpiresAt() {
        String orderNo = savedOrder(LocalDateTime.now().minusSeconds(1));
        markPaid(orderNo);

        orderExpiryScheduler.failExpiredOrders();

        assertThat(statusOf(orderNo)).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("결제 기한 만료 스케줄러가 주기 작업으로 등록된다 - 테스트는 직접 호출하므로 등록 누락을 따로 확인")
    void registersExpirySchedulerAsScheduledTask() {
        List<String> scheduledTasks = applicationContext.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                .flatMap(holder -> holder.getScheduledTasks().stream())
                .map(task -> task.getTask().toString())
                .toList();

        assertThat(scheduledTasks).anyMatch(task -> task.endsWith("OrderExpiryScheduler.failExpiredOrders"));
    }

    private String savedOrder(LocalDateTime expiresAt) {
        return savedOrder(MEMBER_ID, LocalDateTime.now().minusMinutes(10), expiresAt);
    }

    private String savedOrder(Long memberId, LocalDateTime orderedAt) {
        return savedOrder(memberId, orderedAt, orderedAt.plusMinutes(10));
    }

    private String savedOrder(Long memberId, LocalDateTime orderedAt, LocalDateTime expiresAt) {
        Order order = Order.builder()
                .memberId(memberId)
                .orderedAt(orderedAt)
                .expiresAt(expiresAt)
                .build();
        order.addLine(1L, "유기농 우유 900ml", 3_900, 2);
        order.addLine(2L, "국산콩 두부 300g", 2_500, 1);
        orderRepository.save(order);
        return order.getOrderNo();
    }

    // 결제 완료 전이는 결제 승인 단계에서 구현하므로, 결제 대기가 아닌 상태를 DB에서 직접 만든다
    private void markPaid(String orderNo) {
        jdbcTemplate.update("update orders set status = 'PAID' where order_no = ?", orderNo);
    }

    private OrderStatus statusOf(String orderNo) {
        return orderRepository.findByOrderNo(orderNo).map(Order::getStatus).orElseThrow();
    }
}
