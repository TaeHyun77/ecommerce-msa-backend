package com.park.ecommerce.order;

import com.park.ecommerce.exception.OrderErrorCode;
import com.park.ecommerce.exception.OrderException;
import com.park.ecommerce.exception.PaymentResultUnknownException;
import com.park.ecommerce.exception.PaymentServiceUnavailableException;
import com.park.ecommerce.exception.ProductServiceUnavailableException;
import com.park.ecommerce.exception.PromotionServiceUnavailableException;
import com.park.ecommerce.order.domain.Order;
import com.park.ecommerce.order.domain.OrderRepository;
import com.park.ecommerce.order.domain.OrderStatus;
import com.park.ecommerce.order.dto.OrderDetailResponse;
import com.park.ecommerce.order.dto.OrderPaymentRequest;
import com.park.ecommerce.order.dto.OrderPaymentResponse;
import com.park.ecommerce.client.payment.PaymentApiClient;
import com.park.ecommerce.client.payment.dto.PaymentApproveRequest;
import com.park.ecommerce.client.payment.dto.PaymentApproveResponse;
import com.park.ecommerce.client.payment.dto.PaymentResponse;
import com.park.ecommerce.client.product.ProductApiClient;
import com.park.ecommerce.client.promotion.PromotionApiClient;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
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
import org.springframework.web.client.RestClientException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

// 상태 전이는 조건부 UPDATE의 조건이 핵심이라 실제 MySQL로 확인하고, 외부 서비스인 product·promotion·payment-service만 대체한다
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
    private static final Long MEMBER_COUPON_ID = 10L;
    private static final int DISCOUNT = 2_000;
    private static final int DISCOUNTED_AMOUNT = AMOUNT - DISCOUNT;

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.0");

    @Autowired
    private OrderPaymentService orderPaymentService;

    @Autowired
    private OrderService orderService;

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
    @DisplayName("결제를 승인하면 선점을 먼저 확정한 뒤 결제 승인을 요청하고 결제 완료로 바꾼다")
    void confirmsReservationBeforeApprovingPayment() {
        String orderNo = paymentWaitingOrder();
        given(productApiClient.confirmReservation(orderNo)).willReturn(true);
        given(paymentApiClient.approve(any())).willReturn(done(orderNo));

        OrderPaymentResponse response = orderPaymentService.approvePayment(MEMBER_ID, orderNo, request(AMOUNT));

        assertThat(response.status()).isEqualTo(OrderStatus.PAID);
        assertThat(statusOf(orderNo)).isEqualTo(OrderStatus.PAID);
        InOrder inOrder = inOrder(productApiClient, paymentApiClient);
        inOrder.verify(productApiClient).confirmReservation(orderNo);
        inOrder.verify(paymentApiClient).approve(new PaymentApproveRequest(orderNo, PAYMENT_KEY, AMOUNT));
        verifyNoInteractions(promotionApiClient); // 쿠폰을 쓰지 않은 주문
    }

    @Test
    @DisplayName("쿠폰 주문은 재고 확정 → 쿠폰 확정 → 할인된 결제 금액으로 승인 순서로 처리한다")
    void confirmsCouponBeforeApprovingDiscountedAmount() {
        String orderNo = couponOrder();
        given(productApiClient.confirmReservation(orderNo)).willReturn(true);
        given(promotionApiClient.confirmCoupon(orderNo)).willReturn(true);
        given(paymentApiClient.approve(any())).willReturn(done(orderNo));

        OrderPaymentResponse response = orderPaymentService.approvePayment(MEMBER_ID, orderNo, request(DISCOUNTED_AMOUNT));

        assertThat(response.status()).isEqualTo(OrderStatus.PAID);
        InOrder inOrder = inOrder(productApiClient, promotionApiClient, paymentApiClient);
        inOrder.verify(productApiClient).confirmReservation(orderNo);
        inOrder.verify(promotionApiClient).confirmCoupon(orderNo);
        inOrder.verify(paymentApiClient).approve(new PaymentApproveRequest(orderNo, PAYMENT_KEY, DISCOUNTED_AMOUNT));
    }

    @Test
    @DisplayName("쿠폰 주문에 할인 전 금액으로 결제를 요청하면 금액 불일치로 거절한다")
    void rejectsUndiscountedAmountForCouponOrder() {
        String orderNo = couponOrder();

        assertRejected(orderNo, request(AMOUNT), OrderErrorCode.PAYMENT_AMOUNT_MISMATCH);

        verifyNoInteractions(productApiClient, promotionApiClient, paymentApiClient);
    }

    @Test
    @DisplayName("쿠폰 선점이 만료돼 확정하지 못하면 확정한 재고 선점을 해제하고 결제 없이 실패 처리한다")
    void releasesStockWhenCouponReservationExpired() {
        String orderNo = couponOrder();
        given(productApiClient.confirmReservation(orderNo)).willReturn(true);
        given(promotionApiClient.confirmCoupon(orderNo)).willReturn(false);

        assertRejected(orderNo, request(DISCOUNTED_AMOUNT), OrderErrorCode.ORDER_EXPIRED);

        assertThat(statusOf(orderNo)).isEqualTo(OrderStatus.FAILED);
        verify(productApiClient).releaseReservation(orderNo);
        verify(paymentApiClient, never()).approve(any());
    }

    @Test
    @DisplayName("쿠폰 주문의 결제가 거절되면 쿠폰을 먼저 해제한 뒤 재고 선점을 해제하고 실패 처리한다")
    void releasesCouponThenStockWhenPaymentFailed() {
        String orderNo = couponOrder();
        given(productApiClient.confirmReservation(orderNo)).willReturn(true);
        given(promotionApiClient.confirmCoupon(orderNo)).willReturn(true);
        given(paymentApiClient.approve(any())).willReturn(failed(orderNo));

        assertRejected(orderNo, request(DISCOUNTED_AMOUNT), OrderErrorCode.PAYMENT_FAILED);

        assertThat(statusOf(orderNo)).isEqualTo(OrderStatus.FAILED);
        InOrder inOrder = inOrder(promotionApiClient, productApiClient);
        inOrder.verify(promotionApiClient).releaseCoupon(orderNo);
        inOrder.verify(productApiClient).releaseReservation(orderNo);
    }

    @Test
    @DisplayName("결제 거절 뒤 쿠폰 해제에 실패하면 재고를 해제하지 않고 승인 중으로 남긴다 - 복구에서 처음부터 다시 처리")
    void staysApprovingWhenCouponReleaseFails() {
        String orderNo = couponOrder();
        given(productApiClient.confirmReservation(orderNo)).willReturn(true);
        given(promotionApiClient.confirmCoupon(orderNo)).willReturn(true);
        given(paymentApiClient.approve(any())).willReturn(failed(orderNo));
        willThrow(new PromotionServiceUnavailableException()).given(promotionApiClient).releaseCoupon(orderNo);

        OrderPaymentResponse response = orderPaymentService.approvePayment(MEMBER_ID, orderNo, request(DISCOUNTED_AMOUNT));

        assertThat(response.status()).isEqualTo(OrderStatus.APPROVING);
        assertThat(nextRetryAtOf(orderNo)).isBeforeOrEqualTo(LocalDateTime.now());
        verify(productApiClient, never()).releaseReservation(anyString());
    }

    @Test
    @DisplayName("쿠폰 확정 중 promotion-service 장애가 나면 결제를 요청하지 않고 승인 중으로 남긴다")
    void staysApprovingWhenCouponConfirmFails() {
        String orderNo = couponOrder();
        given(productApiClient.confirmReservation(orderNo)).willReturn(true);
        given(promotionApiClient.confirmCoupon(orderNo)).willThrow(new PromotionServiceUnavailableException());

        OrderPaymentResponse response = orderPaymentService.approvePayment(MEMBER_ID, orderNo, request(DISCOUNTED_AMOUNT));

        assertThat(response.status()).isEqualTo(OrderStatus.APPROVING);
        assertThat(nextRetryAtOf(orderNo)).isBeforeOrEqualTo(LocalDateTime.now());
        verify(paymentApiClient, never()).approve(any());
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

        OrderPaymentResponse response = orderPaymentService.approvePayment(MEMBER_ID, orderNo, request(AMOUNT));

        assertThat(response.status()).isEqualTo(OrderStatus.APPROVING);
        assertThat(statusOf(orderNo)).isEqualTo(OrderStatus.APPROVING);
        assertThat(nextRetryAtOf(orderNo)).isBeforeOrEqualTo(LocalDateTime.now());
        verify(productApiClient, never()).releaseReservation(anyString());
    }

    @Test
    @DisplayName("승인 중 예상하지 못한 예외가 나도 오류로 응답하지 않고 승인 중으로 남겨 처리 중을 응답한다 - 이미 결제됐을 수 있음")
    void staysApprovingOnUnexpectedException() {
        String orderNo = paymentWaitingOrder();
        given(productApiClient.confirmReservation(orderNo)).willReturn(true);
        given(paymentApiClient.approve(any())).willThrow(new RestClientException("응답 본문 읽기 실패"));

        OrderPaymentResponse response = orderPaymentService.approvePayment(MEMBER_ID, orderNo, request(AMOUNT));

        assertThat(response.status()).isEqualTo(OrderStatus.APPROVING);
        assertThat(statusOf(orderNo)).isEqualTo(OrderStatus.APPROVING);
        assertThat(nextRetryAtOf(orderNo)).isBeforeOrEqualTo(LocalDateTime.now());
        verify(productApiClient, never()).releaseReservation(anyString());
    }

    @Test
    @DisplayName("결제 승인 요청 때 서킷이 열려 있으면 주문을 실패 처리하고 쿠폰을 먼저 해제한 뒤 재고 선점을 해제한다 - 요청이 나가지 않아 결제되지 않음이 확실")
    void failsAndReleasesWhenPaymentCircuitOpen() {
        String orderNo = couponOrder();
        given(productApiClient.confirmReservation(orderNo)).willReturn(true);
        given(promotionApiClient.confirmCoupon(orderNo)).willReturn(true);
        given(paymentApiClient.approve(any())).willThrow(paymentCircuitOpen());

        assertRejected(orderNo, request(DISCOUNTED_AMOUNT), OrderErrorCode.PAYMENT_SERVICE_UNAVAILABLE);

        assertThat(statusOf(orderNo)).isEqualTo(OrderStatus.FAILED);
        assertThat(nextRetryAtOf(orderNo)).isNull();
        InOrder inOrder = inOrder(promotionApiClient, productApiClient);
        inOrder.verify(promotionApiClient).releaseCoupon(orderNo);
        inOrder.verify(productApiClient).releaseReservation(orderNo);
    }

    @Test
    @DisplayName("서킷이 열려 실패 처리할 때 선점 해제에 실패해도 주문은 실패로 남긴다 - 승인 중으로 두면 복구가 실제 승인을 보내 이중 결제가 될 수 있음")
    void staysFailedWhenReleaseFailsAfterPaymentCircuitOpen() {
        String orderNo = couponOrder();
        given(productApiClient.confirmReservation(orderNo)).willReturn(true);
        given(promotionApiClient.confirmCoupon(orderNo)).willReturn(true);
        given(paymentApiClient.approve(any())).willThrow(paymentCircuitOpen());
        willThrow(new PromotionServiceUnavailableException()).given(promotionApiClient).releaseCoupon(orderNo);
        willThrow(new ProductServiceUnavailableException()).given(productApiClient).releaseReservation(orderNo);

        assertRejected(orderNo, request(DISCOUNTED_AMOUNT), OrderErrorCode.PAYMENT_SERVICE_UNAVAILABLE);

        assertThat(statusOf(orderNo)).isEqualTo(OrderStatus.FAILED);
        // 쿠폰 해제가 실패해도 재고 해제는 시도
        verify(productApiClient).releaseReservation(orderNo);
    }

    @Test
    @DisplayName("선점 확정 중 product-service 장애가 나면 결제를 요청하지 않고 승인 중으로 남긴다")
    void staysApprovingWhenConfirmFails() {
        String orderNo = paymentWaitingOrder();
        given(productApiClient.confirmReservation(orderNo)).willThrow(new ProductServiceUnavailableException());

        OrderPaymentResponse response = orderPaymentService.approvePayment(MEMBER_ID, orderNo, request(AMOUNT));

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

        OrderPaymentResponse response = orderPaymentService.approvePayment(MEMBER_ID, orderNo, request(AMOUNT));

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

        OrderPaymentResponse response = orderPaymentService.approvePayment(MEMBER_ID, orderNo, request(AMOUNT));

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

        assertThatThrownBy(() -> orderPaymentService.approvePayment(2L, orderNo, request(AMOUNT)))
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
    @DisplayName("쿠폰 주문을 복구하면 쿠폰을 다시 확정하고 할인된 결제 금액으로 승인을 다시 요청한다")
    void recoversCouponOrderWithDiscountedAmount() {
        String orderNo = couponOrder();
        changeStatus(orderNo, "APPROVING", LocalDateTime.now().minusMinutes(3));
        given(productApiClient.confirmReservation(orderNo)).willReturn(true);
        given(promotionApiClient.confirmCoupon(orderNo)).willReturn(true);
        given(paymentApiClient.approve(new PaymentApproveRequest(orderNo, PAYMENT_KEY, DISCOUNTED_AMOUNT))).willReturn(done(orderNo));

        orderApprovalRecoveryScheduler.recoverApprovals();

        assertThat(statusOf(orderNo)).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("결제 키 없이 승인 중으로 남은 0원 주문을 복구하면 PG 승인 없이 결제 완료로 확정한다")
    void recoversZeroAmountOrderWithoutPayment() {
        String orderNo = savedOrder(LocalDateTime.now().plusMinutes(10), AMOUNT);
        jdbcTemplate.update("update orders set status = 'APPROVING', approval_requested_at = ?, next_retry_at = ? where order_no = ?",
                LocalDateTime.now().minusSeconds(30), LocalDateTime.now(), orderNo);
        given(productApiClient.confirmReservation(orderNo)).willReturn(true);
        given(promotionApiClient.confirmCoupon(orderNo)).willReturn(true);

        orderApprovalRecoveryScheduler.recoverApprovals();

        assertThat(statusOf(orderNo)).isEqualTo(OrderStatus.PAID);
        verifyNoInteractions(paymentApiClient);
    }

    @Test
    @DisplayName("방금 승인을 시작한 주문은 복구 대상이 아니다 - 아직 진행 중인 요청과 겹치지 않도록 다음 시도 시각을 2분 뒤로 적음")
    void skipsRecentlyStartedApproval() {
        String orderNo = paymentWaitingOrder();
        orderService.startApproval(orderNo, PAYMENT_KEY);

        orderApprovalRecoveryScheduler.recoverApprovals();

        assertThat(statusOf(orderNo)).isEqualTo(OrderStatus.APPROVING);
        assertThat(nextRetryAtOf(orderNo)).isAfter(LocalDateTime.now().plusSeconds(90));
        verify(productApiClient, never()).confirmReservation(orderNo);
    }

    @Test
    @DisplayName("결과 불명으로 끝난 주문은 승인을 시작한 지 얼마 안 됐어도 바로 복구한다 - 요청이 이미 끝나 겹치지 않음")
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
        assertThat(nextRetryAtOf(orderNo)).isBeforeOrEqualTo(LocalDateTime.now());
    }

    @Test
    @DisplayName("복구 중 예상하지 못한 예외가 나도 승인 중으로 남기고 다음 시도 시각을 다시 적는다 - 다음 주기에 다시 복구")
    void reschedulesOnUnexpectedExceptionDuringRecovery() {
        String orderNo = staleApprovingOrder();
        LocalDateTime previousNextRetryAt = nextRetryAtOf(orderNo);
        given(productApiClient.confirmReservation(orderNo)).willReturn(true);
        given(paymentApiClient.approve(any())).willThrow(new RestClientException("응답 본문 읽기 실패"));

        orderApprovalRecoveryScheduler.recoverApprovals();

        assertThat(statusOf(orderNo)).isEqualTo(OrderStatus.APPROVING);
        assertThat(nextRetryAtOf(orderNo)).isAfter(previousNextRetryAt).isBeforeOrEqualTo(LocalDateTime.now());
    }

    @Test
    @DisplayName("복구 중 서킷이 열려 있으면 실패 처리하지 않고 승인 중으로 남긴다 - 이전 승인 요청이 이미 결제됐을 수 있음")
    void keepsApprovingWhenPaymentCircuitOpenDuringRecovery() {
        String orderNo = unknownMarkedOrder(LocalDateTime.now().minusSeconds(30));
        given(productApiClient.confirmReservation(orderNo)).willReturn(true);
        given(paymentApiClient.approve(any())).willThrow(paymentCircuitOpen());

        orderApprovalRecoveryScheduler.recoverApprovals();

        assertThat(statusOf(orderNo)).isEqualTo(OrderStatus.APPROVING);
        assertThat(nextRetryAtOf(orderNo)).isBeforeOrEqualTo(LocalDateTime.now());
        verify(productApiClient, never()).releaseReservation(anyString());
    }

    @Test
    @DisplayName("재시도 상한이 지난 주문도 한 번은 다시 처리한다 - 서버가 오래 멈췄다 켜져도 이미 결제된 주문을 놓치지 않도록")
    void retriesOnceEvenAfterGiveUpAfter() {
        String orderNo = paymentWaitingOrder();
        changeStatus(orderNo, "APPROVING", LocalDateTime.now().minusMinutes(16));
        given(productApiClient.confirmReservation(orderNo)).willReturn(true);
        given(paymentApiClient.approve(any())).willReturn(done(orderNo));

        orderApprovalRecoveryScheduler.recoverApprovals();

        assertThat(statusOf(orderNo)).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("재시도 상한이 지난 주문을 다시 처리해도 결과를 모르면 다음 시도 시각을 비워 재시도를 멈춘다 - 수동 확인 대상")
    void givesUpWhenStillUnknownAfterGiveUpAfter() {
        String orderNo = unknownMarkedOrder(LocalDateTime.now().minusMinutes(16));
        given(productApiClient.confirmReservation(orderNo)).willReturn(true);
        given(paymentApiClient.approve(any())).willThrow(new PaymentResultUnknownException("payment-service 응답 status=503"));

        orderApprovalRecoveryScheduler.recoverApprovals();
        orderApprovalRecoveryScheduler.recoverApprovals();

        assertThat(statusOf(orderNo)).isEqualTo(OrderStatus.APPROVING);
        assertThat(nextRetryAtOf(orderNo)).isNull();
        verify(productApiClient, times(1)).confirmReservation(orderNo);
    }

    @Test
    @DisplayName("다음 시도 시각이 이른 주문부터 다시 처리한다 - 먼저 만든 주문이 계속 결과 불명이어도 뒤 주문의 차례가 밀리지 않도록")
    void recoversInNextRetryAtOrder() {
        String retriedJustNow = unknownMarkedOrder(LocalDateTime.now().minusSeconds(30));
        String waitingLonger = staleApprovingOrder();

        orderApprovalRecoveryScheduler.recoverApprovals();

        InOrder inOrder = inOrder(productApiClient);
        inOrder.verify(productApiClient).confirmReservation(waitingLonger);
        inOrder.verify(productApiClient).confirmReservation(retriedJustNow);
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

    @Test
    @DisplayName("결제 대기 주문을 조회하면 결제 기록이 없으므로 payment-service를 호출하지 않는다")
    void skipsPaymentLookupForPaymentWaitingOrder() {
        String orderNo = paymentWaitingOrder();

        OrderDetailResponse response = orderPaymentService.getOrderWithPayment(MEMBER_ID, orderNo);

        assertThat(response.payment()).isNull();
        verify(paymentApiClient, never()).findPayment(anyString());
    }

    @Test
    @DisplayName("승인 중 주문을 조회하면 결제가 확정되지 않았으므로 payment-service를 호출하지 않는다 - 폴링마다 호출하지 않도록")
    void skipsPaymentLookupForApprovingOrder() {
        String orderNo = staleApprovingOrder();

        OrderDetailResponse response = orderPaymentService.getOrderWithPayment(MEMBER_ID, orderNo);

        assertThat(response.status()).isEqualTo(OrderStatus.APPROVING);
        assertThat(response.payment()).isNull();
        verify(paymentApiClient, never()).findPayment(anyString());
    }

    @Test
    @DisplayName("결제된 주문을 조회하면 payment-service의 결제 정보를 함께 돌려준다")
    void attachesPaymentToPaidOrder() {
        String orderNo = paymentWaitingOrder();
        changeStatus(orderNo, "PAID", LocalDateTime.now());
        LocalDateTime approvedAt = LocalDateTime.of(2026, 9, 22, 12, 5);
        given(paymentApiClient.findPayment(orderNo)).willReturn(Optional.of(new PaymentResponse("DONE", AMOUNT, "카드", approvedAt)));

        OrderDetailResponse response = orderPaymentService.getOrderWithPayment(MEMBER_ID, orderNo);

        assertThat(response.status()).isEqualTo(OrderStatus.PAID);
        assertThat(response.payment()).isEqualTo(new OrderDetailResponse.Payment("DONE", AMOUNT, "카드", approvedAt));
    }

    @Test
    @DisplayName("결제 거절로 실패한 주문은 결제 실패 정보를 함께 돌려준다")
    void attachesPaymentToFailedOrder() {
        String orderNo = paymentWaitingOrder();
        changeStatus(orderNo, "FAILED", LocalDateTime.now());
        given(paymentApiClient.findPayment(orderNo)).willReturn(Optional.of(new PaymentResponse("FAILED", AMOUNT, null, null)));

        OrderDetailResponse response = orderPaymentService.getOrderWithPayment(MEMBER_ID, orderNo);

        assertThat(response.status()).isEqualTo(OrderStatus.FAILED);
        assertThat(response.payment()).isEqualTo(new OrderDetailResponse.Payment("FAILED", AMOUNT, null, null));
    }

    @Test
    @DisplayName("결제 기록이 없는 실패 주문은 결제 정보 없이 돌려준다")
    void returnsFailedOrderWithoutPaymentWhenNoRecord() {
        String orderNo = paymentWaitingOrder();
        changeStatus(orderNo, "FAILED", null);
        given(paymentApiClient.findPayment(orderNo)).willReturn(Optional.empty());

        assertThat(orderPaymentService.getOrderWithPayment(MEMBER_ID, orderNo).payment()).isNull();
    }

    @Test
    @DisplayName("payment-service 장애로 결제 정보를 가져오지 못해도 주문 상태는 응답한다 - 폴링이 최종 상태(PAID)를 이 조회로 확인하므로")
    void respondsWithoutPaymentWhenPaymentServiceFails() {
        String orderNo = paymentWaitingOrder();
        changeStatus(orderNo, "PAID", LocalDateTime.now());
        given(paymentApiClient.findPayment(orderNo)).willThrow(new PaymentServiceUnavailableException("연결 실패"));

        OrderDetailResponse response = orderPaymentService.getOrderWithPayment(MEMBER_ID, orderNo);

        assertThat(response.status()).isEqualTo(OrderStatus.PAID);
        assertThat(response.payment()).isNull();
    }

    @Test
    @DisplayName("서킷이 열려 호출이 차단돼도 주문 상태는 응답한다")
    void respondsWithoutPaymentWhenCircuitOpen() {
        String orderNo = paymentWaitingOrder();
        changeStatus(orderNo, "PAID", LocalDateTime.now());
        given(paymentApiClient.findPayment(orderNo))
                .willThrow(CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("paymentService")));

        assertThat(orderPaymentService.getOrderWithPayment(MEMBER_ID, orderNo).payment()).isNull();
    }

    private void assertRejected(String orderNo, OrderPaymentRequest request, OrderErrorCode errorCode) {
        assertThatThrownBy(() -> orderPaymentService.approvePayment(MEMBER_ID, orderNo, request))
                .isInstanceOf(OrderException.class)
                .extracting("errorCode")
                .isEqualTo(errorCode);
    }

    private String paymentWaitingOrder() {
        return savedOrder(LocalDateTime.now().plusMinutes(10));
    }

    // 결제 승인을 시작한 지 오래된 주문 - 결과를 모른 채 서버가 멈춰, 다음 시도 시각이 승인 시작 때 적은 값 그대로 지난 상황
    private String staleApprovingOrder() {
        String orderNo = paymentWaitingOrder();
        changeStatus(orderNo, "APPROVING", LocalDateTime.now().minusMinutes(3));
        return orderNo;
    }

    // 결과 불명으로 끝나 다음 시도 시각이 지금으로 당겨진 승인 중 주문
    private String unknownMarkedOrder(LocalDateTime approvalRequestedAt) {
        String orderNo = paymentWaitingOrder();
        changeStatus(orderNo, "APPROVING", approvalRequestedAt);
        jdbcTemplate.update("update orders set next_retry_at = ? where order_no = ?", LocalDateTime.now(), orderNo);
        return orderNo;
    }

    private String savedOrder(LocalDateTime expiresAt) {
        return savedOrder(expiresAt, null);
    }

    // 쿠폰으로 DISCOUNT만큼 할인된 결제 대기 주문
    private String couponOrder() {
        return savedOrder(LocalDateTime.now().plusMinutes(10), DISCOUNT);
    }

    private String savedOrder(LocalDateTime expiresAt, Integer discountAmount) {
        Order order = Order.builder()
                .memberId(MEMBER_ID)
                .orderedAt(LocalDateTime.now().minusMinutes(1))
                .expiresAt(expiresAt)
                .build();
        order.addLine(1L, "유기농 우유 900ml", 3_900, 2);
        if (discountAmount != null) {
            order.applyCoupon(MEMBER_COUPON_ID, discountAmount);
        }
        orderRepository.save(order);
        return order.getOrderNo();
    }

    // 결제 승인 이후의 상태를 직접 만든다 - 승인 흐름을 거치지 않고 특정 상태에서 시작하는 경우를 검증하기 위해
    // 승인 중이면 다음 시도 시각을 승인 시작 때와 같이 2분 뒤(stale-after)로 적는다
    private void changeStatus(String orderNo, String status, LocalDateTime approvalRequestedAt) {
        LocalDateTime nextRetryAt = "APPROVING".equals(status) ? approvalRequestedAt.plusMinutes(2) : null;
        jdbcTemplate.update("update orders set status = ?, payment_key = ?, approval_requested_at = ?, next_retry_at = ? where order_no = ?",
                status, PAYMENT_KEY, approvalRequestedAt, nextRetryAt, orderNo);
    }

    private LocalDateTime nextRetryAtOf(String orderNo) {
        return jdbcTemplate.queryForObject("select next_retry_at from orders where order_no = ?", LocalDateTime.class, orderNo);
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

    private static CallNotPermittedException paymentCircuitOpen() {
        return CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("paymentService"));
    }

    private static PaymentApproveResponse failed(String orderNo) {
        return new PaymentApproveResponse(orderNo, "FAILED", "ABORTED / REJECT_CARD_COMPANY: 카드사에서 거절했습니다.");
    }
}
