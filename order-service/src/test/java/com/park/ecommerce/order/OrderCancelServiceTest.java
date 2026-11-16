package com.park.ecommerce.order;

import com.park.ecommerce.client.payment.PaymentApiClient;
import com.park.ecommerce.client.product.ProductApiClient;
import com.park.ecommerce.client.promotion.PromotionApiClient;
import com.park.ecommerce.exception.OrderErrorCode;
import com.park.ecommerce.exception.OrderException;
import com.park.ecommerce.exception.PaymentResultUnknownException;
import com.park.ecommerce.exception.ProductServiceUnavailableException;
import com.park.ecommerce.order.domain.Order;
import com.park.ecommerce.order.domain.OrderRepository;
import com.park.ecommerce.order.domain.OrderStatus;
import com.park.ecommerce.order.dto.OrderCancelResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

// 상태 전이는 조건부 UPDATE의 조건이 핵심이라 실제 MySQL로 확인하고, 외부 서비스인 product·promotion·payment-service만 대체한다
@Testcontainers
@SpringBootTest(properties = {
        "order.approval-recovery.stale-after=2m",
        "order.approval-recovery.fast-retry-window=15m",
        "order.approval-recovery.slow-retry-interval=5m",
        "order.approval-recovery.give-up-after=24h",
        // 테스트 중 스케줄러 개입 방지 - 복구 스케줄러는 테스트에서 직접 호출
        "order.payment-waiting.expire-poll-delay=1h",
        "order.approval-recovery.poll-delay=1h"
})
class OrderCancelServiceTest {
    private static final Long MEMBER_ID = 1L;
    private static final Long MEMBER_COUPON_ID = 10L;

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.0");

    @Autowired
    private OrderCancelService orderCancelService;

    @Autowired
    private OrderApprovalRecoveryScheduler orderApprovalRecoveryScheduler;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationContext applicationContext;

    @MockitoBean
    private ProductApiClient productApiClient;

    @MockitoBean
    private PaymentApiClient paymentApiClient;

    @MockitoBean
    private PromotionApiClient promotionApiClient;

    @Test
    @DisplayName("결제 완료 주문을 취소하면 환불을 먼저 한 뒤 쿠폰과 재고를 되돌리고 취소 완료로 바꾼다")
    void refundsBeforeReleasingCouponAndStock() {
        String orderNo = paidOrder(2_000);

        OrderCancelResponse response = orderCancelService.cancelOrder(MEMBER_ID, orderNo);

        assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(statusOf(orderNo)).isEqualTo(OrderStatus.CANCELLED);
        assertThat(nextRetryAtOf(orderNo)).isNull();
        InOrder inOrder = inOrder(paymentApiClient, promotionApiClient, productApiClient);
        inOrder.verify(paymentApiClient).cancel(orderNo);
        inOrder.verify(promotionApiClient).releaseCoupon(orderNo);
        inOrder.verify(productApiClient).releaseReservation(orderNo);
    }

    @Test
    @DisplayName("쿠폰을 쓰지 않은 주문은 쿠폰 해제를 요청하지 않는다")
    void skipsCouponReleaseForOrderWithoutCoupon() {
        String orderNo = paidOrder(null);

        orderCancelService.cancelOrder(MEMBER_ID, orderNo);

        assertThat(statusOf(orderNo)).isEqualTo(OrderStatus.CANCELLED);
        verify(promotionApiClient, never()).releaseCoupon(anyString());
    }

    @Test
    @DisplayName("0원 주문은 환불할 결제가 없으므로 환불을 요청하지 않고 쿠폰과 재고만 되돌린다")
    void skipsRefundForFreeOrder() {
        String orderNo = paidOrder(7_800);

        orderCancelService.cancelOrder(MEMBER_ID, orderNo);

        assertThat(statusOf(orderNo)).isEqualTo(OrderStatus.CANCELLED);
        verify(paymentApiClient, never()).cancel(anyString());
        verify(promotionApiClient).releaseCoupon(orderNo);
        verify(productApiClient).releaseReservation(orderNo);
    }

    @Test
    @DisplayName("환불 결과를 모르면 쿠폰과 재고를 되돌리지 않고 취소 중으로 남기며, 복구 스케줄러가 다시 처리해 취소 완료로 바꾼다")
    void staysCancellingWhenRefundUnknownThenRecovers() {
        String orderNo = paidOrder(null);
        willThrow(new PaymentResultUnknownException("payment-service 응답 status=503"))
                .willDoNothing()
                .given(paymentApiClient).cancel(orderNo);

        OrderCancelResponse response = orderCancelService.cancelOrder(MEMBER_ID, orderNo);

        assertThat(response.status()).isEqualTo(OrderStatus.CANCELLING);
        assertThat(statusOf(orderNo)).isEqualTo(OrderStatus.CANCELLING);
        assertThat(nextRetryAtOf(orderNo)).isBeforeOrEqualTo(LocalDateTime.now());
        verify(productApiClient, never()).releaseReservation(anyString());

        orderApprovalRecoveryScheduler.recoverCancels();

        assertThat(statusOf(orderNo)).isEqualTo(OrderStatus.CANCELLED);
        verify(paymentApiClient, times(2)).cancel(orderNo);
        verify(productApiClient).releaseReservation(orderNo);
    }

    @Test
    @DisplayName("환불 뒤 재고 해제에 실패하면 취소 중으로 남기고, 복구 때 환불부터 다시 요청한다 - 모든 단계가 멱등")
    void retriesFromRefundWhenStockReleaseFails() {
        String orderNo = paidOrder(null);
        willThrow(new ProductServiceUnavailableException())
                .willDoNothing()
                .given(productApiClient).releaseReservation(orderNo);

        orderCancelService.cancelOrder(MEMBER_ID, orderNo);
        assertThat(statusOf(orderNo)).isEqualTo(OrderStatus.CANCELLING);

        orderApprovalRecoveryScheduler.recoverCancels();

        assertThat(statusOf(orderNo)).isEqualTo(OrderStatus.CANCELLED);
        verify(paymentApiClient, times(2)).cancel(orderNo);
    }

    @Test
    @DisplayName("취소를 막 시작한 주문은 진행 중인 요청과 겹치지 않도록 복구하지 않는다")
    void skipsRecentlyStartedCancel() {
        String orderNo = paidOrder(null);
        changeToCancelling(orderNo, LocalDateTime.now(), LocalDateTime.now().plusMinutes(2));

        orderApprovalRecoveryScheduler.recoverCancels();

        assertThat(statusOf(orderNo)).isEqualTo(OrderStatus.CANCELLING);
        verifyNoInteractions(paymentApiClient);
    }

    @Test
    @DisplayName("빠른 재시도 구간이 지난 취소는 결과를 모르면 다음 시도 시각을 5분 뒤로 적는다")
    void retriesSlowlyAfterFastRetryWindow() {
        String orderNo = paidOrder(null);
        changeToCancelling(orderNo, LocalDateTime.now().minusMinutes(16), LocalDateTime.now());
        willThrow(new PaymentResultUnknownException("payment-service 응답 status=503")).given(paymentApiClient).cancel(orderNo);

        orderApprovalRecoveryScheduler.recoverCancels();

        assertThat(statusOf(orderNo)).isEqualTo(OrderStatus.CANCELLING);
        assertThat(nextRetryAtOf(orderNo))
                .isAfter(LocalDateTime.now().plusMinutes(4))
                .isBeforeOrEqualTo(LocalDateTime.now().plusMinutes(5));
    }

    @Test
    @DisplayName("재시도 상한이 지난 취소를 다시 처리해도 결과를 모르면 결제 완료로 되돌리지 않고 재시도만 멈춘다 - 수동 확인 대상")
    void givesUpWhenStillUnknownAfterGiveUpAfter() {
        String orderNo = paidOrder(null);
        changeToCancelling(orderNo, LocalDateTime.now().minusHours(25), LocalDateTime.now());
        willThrow(new PaymentResultUnknownException("payment-service 응답 status=503")).given(paymentApiClient).cancel(orderNo);

        orderApprovalRecoveryScheduler.recoverCancels();
        orderApprovalRecoveryScheduler.recoverCancels();

        assertThat(statusOf(orderNo)).isEqualTo(OrderStatus.CANCELLING);
        assertThat(nextRetryAtOf(orderNo)).isNull();
        verify(paymentApiClient, times(1)).cancel(orderNo);
    }

    @Test
    @DisplayName("이미 취소한 주문을 다시 취소하면 환불을 다시 요청하지 않고 취소 완료를 응답한다")
    void returnsCancelledWithoutRefundingAgain() {
        String orderNo = paidOrder(null);
        orderCancelService.cancelOrder(MEMBER_ID, orderNo);

        OrderCancelResponse response = orderCancelService.cancelOrder(MEMBER_ID, orderNo);

        assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);
        verify(paymentApiClient, times(1)).cancel(orderNo);
        verify(productApiClient, times(1)).releaseReservation(orderNo);
    }

    @Test
    @DisplayName("결제가 완료되지 않은 주문은 취소를 거절한다")
    void rejectsOrderNotPaid() {
        String waiting = savedOrder(null);
        String approving = savedOrder(null);
        jdbcTemplate.update("update orders set status = 'APPROVING' where order_no = ?", approving);

        for (String orderNo : List.of(waiting, approving)) {
            assertThatThrownBy(() -> orderCancelService.cancelOrder(MEMBER_ID, orderNo))
                    .isInstanceOf(OrderException.class)
                    .extracting("errorCode")
                    .isEqualTo(OrderErrorCode.ORDER_NOT_CANCELABLE);
        }
        verifyNoInteractions(paymentApiClient, productApiClient);
    }

    @Test
    @DisplayName("다른 회원의 주문은 없는 주문과 같이 404로 거절한다")
    void rejectsOtherMembersOrder() {
        String orderNo = paidOrder(null);

        assertThatThrownBy(() -> orderCancelService.cancelOrder(MEMBER_ID + 1, orderNo))
                .isInstanceOf(OrderException.class)
                .extracting("errorCode")
                .isEqualTo(OrderErrorCode.ORDER_NOT_FOUND);
        assertThat(statusOf(orderNo)).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("취소 복구가 주기 작업으로 등록된다 - 테스트는 직접 호출하므로 등록 누락을 따로 확인")
    void registersCancelRecoveryAsScheduledTask() {
        List<String> scheduledTasks = applicationContext.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                .flatMap(holder -> holder.getScheduledTasks().stream())
                .map(task -> task.getTask().toString())
                .toList();

        assertThat(scheduledTasks).anyMatch(task -> task.endsWith("OrderApprovalRecoveryScheduler.recoverCancels"));
    }

    // 상품 합계 7,800원의 결제 완료 주문 - discountAmount가 있으면 쿠폰을 쓴 주문
    private String paidOrder(Integer discountAmount) {
        String orderNo = savedOrder(discountAmount);
        jdbcTemplate.update("update orders set status = 'PAID' where order_no = ?", orderNo);
        return orderNo;
    }

    private String savedOrder(Integer discountAmount) {
        Order order = Order.builder()
                .memberId(MEMBER_ID)
                .orderedAt(LocalDateTime.now().minusMinutes(1))
                .expiresAt(LocalDateTime.now().plusMinutes(10))
                .build();
        order.addLine(1L, "유기농 우유 900ml", 3_900, 2);
        if (discountAmount != null) {
            order.applyCoupon(MEMBER_COUPON_ID, discountAmount);
        }
        orderRepository.save(order);
        return order.getOrderNo();
    }

    // 취소 흐름을 거치지 않고 취소 중 상태를 직접 만든다 - 취소를 시작한 지 오래된 경우를 검증하기 위해
    private void changeToCancelling(String orderNo, LocalDateTime cancelRequestedAt, LocalDateTime nextRetryAt) {
        jdbcTemplate.update("update orders set status = 'CANCELLING', cancel_requested_at = ?, next_retry_at = ? where order_no = ?",
                cancelRequestedAt, nextRetryAt, orderNo);
    }

    private LocalDateTime nextRetryAtOf(String orderNo) {
        return jdbcTemplate.queryForObject("select next_retry_at from orders where order_no = ?", LocalDateTime.class, orderNo);
    }

    private OrderStatus statusOf(String orderNo) {
        return orderRepository.findByOrderNo(orderNo).map(Order::getStatus).orElseThrow();
    }
}
