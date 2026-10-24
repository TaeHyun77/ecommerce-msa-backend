package com.park.ecommerce.order;

import com.park.ecommerce.exception.OrderErrorCode;
import com.park.ecommerce.exception.OrderException;
import com.park.ecommerce.exception.PaymentResultUnknownException;
import com.park.ecommerce.exception.ProductServiceUnavailableException;
import com.park.ecommerce.order.domain.Order;
import com.park.ecommerce.order.domain.OrderRepository;
import com.park.ecommerce.order.domain.OrderStatus;
import com.park.ecommerce.order.dto.OrderPaymentRequest;
import com.park.ecommerce.order.dto.OrderPaymentResponse;
import com.park.ecommerce.client.payment.PaymentApiClient;
import com.park.ecommerce.client.payment.dto.PaymentApproveRequest;
import com.park.ecommerce.client.payment.dto.PaymentApproveResponse;
import com.park.ecommerce.client.product.ProductApiClient;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

// 상태 전이는 조건부 UPDATE의 조건이 핵심이라 실제 MySQL로 확인하고, 외부 서비스인 product-service·payment-service만 대체한다
@Testcontainers
@SpringBootTest(properties = {
        "order.payment-waiting.ttl=10m",
        "order.approval-recovery.stale-after=2m",
        "order.approval-recovery.give-up-after=15m",
        // 테스트 중 스케줄러 개입 방지 - 복구 스케줄러는 테스트에서 직접 호출
        "order.payment-waiting.expire-poll-delay=1h",
        "order.approval-recovery.poll-delay=1h"
})
class OrderPaymentServiceTest {
    private static final Long MEMBER_ID = 1L;
    private static final String PAYMENT_KEY = "tgen_pay_0001";
    private static final int AMOUNT = 7_800;

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.0");

    @Autowired
    private OrderPaymentService orderPaymentService;

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

    @Test
    @DisplayName("결제를 승인하면 선점을 먼저 확정한 뒤 결제 승인을 요청하고 결제 완료로 바꾼다")
    void confirmsReservationBeforeApprovingPayment() {
        String orderNo = paymentWaitingOrder();
        given(productApiClient.confirmReservation(orderNo)).willReturn(true);
        given(paymentApiClient.approve(any())).willReturn(done(orderNo));

        OrderPaymentResponse response = orderPaymentService.approve(MEMBER_ID, orderNo, request(AMOUNT));

        assertThat(response.status()).isEqualTo(OrderStatus.PAID);
        assertThat(statusOf(orderNo)).isEqualTo(OrderStatus.PAID);
        InOrder inOrder = inOrder(productApiClient, paymentApiClient);
        inOrder.verify(productApiClient).confirmReservation(orderNo);
        inOrder.verify(paymentApiClient).approve(new PaymentApproveRequest(orderNo, PAYMENT_KEY, AMOUNT));
    }

    @Test
    @DisplayName("선점이 만료돼 확정하지 못하면 결제를 요청하지 않고 주문을 실패 처리한다 - 청구 전에 차단")
    void failsWithoutPaymentWhenReservationExpired() {
        String orderNo = paymentWaitingOrder();
        given(productApiClient.confirmReservation(orderNo)).willReturn(false);

        assertRejected(orderNo, request(AMOUNT), OrderErrorCode.ORDER_EXPIRED);

        assertThat(statusOf(orderNo)).isEqualTo(OrderStatus.FAILED);
        verify(paymentApiClient, never()).approve(any());
    }

    @Test
    @DisplayName("결제가 거절되면 확정한 선점을 해제하고 주문을 실패 처리한다")
    void releasesReservationWhenPaymentFailed() {
        String orderNo = paymentWaitingOrder();
        given(productApiClient.confirmReservation(orderNo)).willReturn(true);
        given(paymentApiClient.approve(any())).willReturn(failed(orderNo));

        assertRejected(orderNo, request(AMOUNT), OrderErrorCode.PAYMENT_FAILED);

        assertThat(statusOf(orderNo)).isEqualTo(OrderStatus.FAILED);
        verify(productApiClient).releaseReservation(orderNo);
    }

    @Test
    @DisplayName("결제 결과를 모르면 선점을 해제하지 않고 승인 중으로 남겨 처리 중을 응답한다")
    void staysApprovingWhenPaymentResultUnknown() {
        String orderNo = paymentWaitingOrder();
        given(productApiClient.confirmReservation(orderNo)).willReturn(true);
        given(paymentApiClient.approve(any())).willThrow(new PaymentResultUnknownException("payment-service 응답 status=503"));

        OrderPaymentResponse response = orderPaymentService.approve(MEMBER_ID, orderNo, request(AMOUNT));

        assertThat(response.status()).isEqualTo(OrderStatus.APPROVING);
        assertThat(statusOf(orderNo)).isEqualTo(OrderStatus.APPROVING);
        assertThat(approvalUnknownAtOf(orderNo)).isNotNull();
        verify(productApiClient, never()).releaseReservation(anyString());
    }

    @Test
    @DisplayName("선점 확정 중 product-service 장애가 나면 결제를 요청하지 않고 승인 중으로 남긴다")
    void staysApprovingWhenConfirmFails() {
        String orderNo = paymentWaitingOrder();
        given(productApiClient.confirmReservation(orderNo)).willThrow(new ProductServiceUnavailableException());

        OrderPaymentResponse response = orderPaymentService.approve(MEMBER_ID, orderNo, request(AMOUNT));

        assertThat(response.status()).isEqualTo(OrderStatus.APPROVING);
        assertThat(statusOf(orderNo)).isEqualTo(OrderStatus.APPROVING);
        verify(paymentApiClient, never()).approve(any());
    }

    @Test
    @DisplayName("결제 거절 뒤 선점 해제에 실패하면 실패 처리하지 않고 승인 중으로 남긴다 - 복구에서 다시 해제")
    void staysApprovingWhenReleaseFails() {
        String orderNo = paymentWaitingOrder();
        given(productApiClient.confirmReservation(orderNo)).willReturn(true);
        given(paymentApiClient.approve(any())).willReturn(failed(orderNo));
        willThrow(new ProductServiceUnavailableException()).given(productApiClient).releaseReservation(orderNo);

        OrderPaymentResponse response = orderPaymentService.approve(MEMBER_ID, orderNo, request(AMOUNT));

        assertThat(response.status()).isEqualTo(OrderStatus.APPROVING);
        assertThat(statusOf(orderNo)).isEqualTo(OrderStatus.APPROVING);
    }

    @Test
    @DisplayName("결제 금액이 주문 금액과 다르면 상태를 바꾸지 않고 원격 호출도 하지 않는다 - 조작된 successUrl 차단")
    void rejectsAmountMismatch() {
        String orderNo = paymentWaitingOrder();

        assertRejected(orderNo, request(100), OrderErrorCode.PAYMENT_AMOUNT_MISMATCH);

        assertThat(statusOf(orderNo)).isEqualTo(OrderStatus.PAYMENT_WAITING);
        verifyNoInteractions(productApiClient, paymentApiClient);
    }

    @Test
    @DisplayName("결제 기한이 지난 주문은 만료 처리 전이어도 승인하지 않는다")
    void rejectsOrderPastExpiresAt() {
        String orderNo = savedOrder(LocalDateTime.now().minusSeconds(1));

        assertRejected(orderNo, request(AMOUNT), OrderErrorCode.ORDER_EXPIRED);

        verifyNoInteractions(productApiClient, paymentApiClient);
    }

    @Test
    @DisplayName("이미 결제 완료된 주문을 다시 요청하면 원격 호출 없이 결제 완료를 응답한다 - 성공 페이지 새로고침")
    void returnsPaidForAlreadyPaidOrder() {
        String orderNo = paymentWaitingOrder();
        changeStatus(orderNo, "PAID", LocalDateTime.now());

        OrderPaymentResponse response = orderPaymentService.approve(MEMBER_ID, orderNo, request(AMOUNT));

        assertThat(response.status()).isEqualTo(OrderStatus.PAID);
        verifyNoInteractions(productApiClient, paymentApiClient);
    }

    @Test
    @DisplayName("승인 중인 주문을 다시 요청하면 처리 중으로 거절한다 - 같은 결제가 동시에 두 번 진행되지 않도록")
    void rejectsOrderAlreadyApproving() {
        String orderNo = paymentWaitingOrder();
        changeStatus(orderNo, "APPROVING", LocalDateTime.now());

        assertRejected(orderNo, request(AMOUNT), OrderErrorCode.PAYMENT_IN_PROGRESS);

        verifyNoInteractions(productApiClient, paymentApiClient);
    }

    @Test
    @DisplayName("실패한 주문은 결제할 수 없다")
    void rejectsFailedOrder() {
        String orderNo = paymentWaitingOrder();
        changeStatus(orderNo, "FAILED", null);

        assertRejected(orderNo, request(AMOUNT), OrderErrorCode.ORDER_NOT_PAYABLE);
    }

    @Test
    @DisplayName("다른 회원의 주문은 결제할 수 없다 - 없는 주문과 같은 404")
    void rejectsOtherMembersOrder() {
        String orderNo = paymentWaitingOrder();

        assertThatThrownBy(() -> orderPaymentService.approve(2L, orderNo, request(AMOUNT)))
                .isInstanceOf(OrderException.class)
                .extracting("errorCode")
                .isEqualTo(OrderErrorCode.ORDER_NOT_FOUND);
    }

    @Test
    @DisplayName("오래 멈춘 승인 중 주문은 복구 스케줄러가 저장된 결제 키로 처음부터 다시 처리해 결제 완료로 확정한다")
    void recoversStaleApprovingOrderToPaid() {
        String orderNo = staleApprovingOrder();
        given(productApiClient.confirmReservation(orderNo)).willReturn(true);
        given(paymentApiClient.approve(new PaymentApproveRequest(orderNo, PAYMENT_KEY, AMOUNT))).willReturn(done(orderNo));

        orderApprovalRecoveryScheduler.recoverApprovals();

        assertThat(statusOf(orderNo)).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("복구 중 결제 거절이 확정되면 선점을 해제하고 주문을 실패 처리한다")
    void recoversStaleApprovingOrderToFailed() {
        String orderNo = staleApprovingOrder();
        given(productApiClient.confirmReservation(orderNo)).willReturn(true);
        given(paymentApiClient.approve(any())).willReturn(failed(orderNo));

        orderApprovalRecoveryScheduler.recoverApprovals();

        assertThat(statusOf(orderNo)).isEqualTo(OrderStatus.FAILED);
        verify(productApiClient).releaseReservation(orderNo);
    }

    @Test
    @DisplayName("방금 승인을 시작한 주문은 복구 대상이 아니다 - 아직 진행 중인 요청과 겹치지 않도록")
    void skipsRecentlyStartedApproval() {
        String orderNo = paymentWaitingOrder();
        changeStatus(orderNo, "APPROVING", LocalDateTime.now());

        orderApprovalRecoveryScheduler.recoverApprovals();

        assertThat(statusOf(orderNo)).isEqualTo(OrderStatus.APPROVING);
        verify(productApiClient, never()).confirmReservation(orderNo);
    }

    @Test
    @DisplayName("결과 불명으로 표시된 주문은 승인을 시작한 지 얼마 안 됐어도 바로 복구한다 - 요청이 이미 끝나 겹치지 않음")
    void recoversMarkedOrderWithoutWaitingStaleAfter() {
        String orderNo = unknownMarkedOrder(LocalDateTime.now().minusSeconds(30));
        given(productApiClient.confirmReservation(orderNo)).willReturn(true);
        given(paymentApiClient.approve(any())).willReturn(done(orderNo));

        orderApprovalRecoveryScheduler.recoverApprovals();

        assertThat(statusOf(orderNo)).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("복구 중에도 결과를 모르면 승인 중으로 남기고 다음 주기에 다시 복구한다")
    void keepsMarkWhenRecoveryStillUnknown() {
        String orderNo = unknownMarkedOrder(LocalDateTime.now().minusSeconds(30));
        given(productApiClient.confirmReservation(orderNo)).willReturn(true);
        given(paymentApiClient.approve(any())).willThrow(new PaymentResultUnknownException("payment-service 응답 status=503"));

        orderApprovalRecoveryScheduler.recoverApprovals();

        assertThat(statusOf(orderNo)).isEqualTo(OrderStatus.APPROVING);
        assertThat(approvalUnknownAtOf(orderNo)).isNotNull();
    }

    @Test
    @DisplayName("승인 시작 후 재시도 상한이 지난 주문은 더 이상 복구하지 않고 승인 중으로 둔다 - 수동 확인 대상")
    void stopsRetryingAfterGiveUpAfter() {
        String orderNo = unknownMarkedOrder(LocalDateTime.now().minusMinutes(16));

        orderApprovalRecoveryScheduler.recoverApprovals();

        assertThat(statusOf(orderNo)).isEqualTo(OrderStatus.APPROVING);
        verifyNoInteractions(productApiClient, paymentApiClient);
    }

    @Test
    @DisplayName("상한이 지난 주문은 표시가 없어도 오래 멈춘 주문으로 다시 복구하지 않는다")
    void doesNotRetryStaleOrderPastGiveUpAfter() {
        String orderNo = paymentWaitingOrder();
        changeStatus(orderNo, "APPROVING", LocalDateTime.now().minusMinutes(16));

        orderApprovalRecoveryScheduler.recoverApprovals();

        assertThat(statusOf(orderNo)).isEqualTo(OrderStatus.APPROVING);
        verifyNoInteractions(productApiClient, paymentApiClient);
    }

    @Test
    @DisplayName("상한이 지난 주문은 표시를 해제해 한 번만 알린다 - 매 주기마다 같은 오류 로그가 쌓이지 않도록")
    void clearsMarkAfterGivingUp() {
        String orderNo = unknownMarkedOrder(LocalDateTime.now().minusMinutes(16));

        orderApprovalRecoveryScheduler.recoverApprovals();

        assertThat(approvalUnknownAtOf(orderNo)).isNull();
    }

    @Test
    @DisplayName("승인 복구 스케줄러가 주기 작업으로 등록된다 - 테스트는 직접 호출하므로 등록 누락을 따로 확인")
    void registersRecoverySchedulerAsScheduledTask() {
        List<String> scheduledTasks = applicationContext.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                .flatMap(holder -> holder.getScheduledTasks().stream())
                .map(task -> task.getTask().toString())
                .toList();

        assertThat(scheduledTasks).anyMatch(task -> task.endsWith("OrderApprovalRecoveryScheduler.recoverApprovals"));
    }

    private void assertRejected(String orderNo, OrderPaymentRequest request, OrderErrorCode errorCode) {
        assertThatThrownBy(() -> orderPaymentService.approve(MEMBER_ID, orderNo, request))
                .isInstanceOf(OrderException.class)
                .extracting("errorCode")
                .isEqualTo(errorCode);
    }

    private String paymentWaitingOrder() {
        return savedOrder(LocalDateTime.now().plusMinutes(10));
    }

    // 결제 승인을 시작한 지 오래된 주문 - 결과를 모른 채 서버가 멈춘 상황
    private String staleApprovingOrder() {
        String orderNo = paymentWaitingOrder();
        changeStatus(orderNo, "APPROVING", LocalDateTime.now().minusMinutes(3));
        return orderNo;
    }

    // 결과 불명이 확정되어 바로 재시도할 수 있는 승인 중 주문
    private String unknownMarkedOrder(LocalDateTime approvalRequestedAt) {
        String orderNo = paymentWaitingOrder();
        changeStatus(orderNo, "APPROVING", approvalRequestedAt);
        jdbcTemplate.update("update orders set approval_unknown_at = ? where order_no = ?", LocalDateTime.now(), orderNo);
        return orderNo;
    }

    private String savedOrder(LocalDateTime expiresAt) {
        Order order = Order.builder()
                .memberId(MEMBER_ID)
                .orderedAt(LocalDateTime.now().minusMinutes(1))
                .expiresAt(expiresAt)
                .build();
        order.addLine(1L, "유기농 우유 900ml", 3_900, 2);
        orderRepository.save(order);
        return order.getOrderNo();
    }

    // 결제 승인 이후의 상태를 직접 만든다 - 승인 흐름을 거치지 않고 특정 상태에서 시작하는 경우를 검증하기 위해
    private void changeStatus(String orderNo, String status, LocalDateTime approvalRequestedAt) {
        jdbcTemplate.update("update orders set status = ?, payment_key = ?, approval_requested_at = ? where order_no = ?",
                status, PAYMENT_KEY, approvalRequestedAt, orderNo);
    }

    private LocalDateTime approvalUnknownAtOf(String orderNo) {
        return jdbcTemplate.queryForObject("select approval_unknown_at from orders where order_no = ?", LocalDateTime.class, orderNo);
    }

    private OrderStatus statusOf(String orderNo) {
        return orderRepository.findByOrderNo(orderNo).map(Order::getStatus).orElseThrow();
    }

    private static OrderPaymentRequest request(int amount) {
        return new OrderPaymentRequest(PAYMENT_KEY, amount);
    }

    private static PaymentApproveResponse done(String orderNo) {
        return new PaymentApproveResponse(orderNo, "DONE", null);
    }

    private static PaymentApproveResponse failed(String orderNo) {
        return new PaymentApproveResponse(orderNo, "FAILED", "ABORTED / REJECT_CARD_COMPANY: 카드사에서 거절했습니다.");
    }
}
