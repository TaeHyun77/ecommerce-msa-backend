package com.park.ecommerce.order;

import com.park.ecommerce.exception.OrderErrorCode;
import com.park.ecommerce.exception.OrderException;
import com.park.ecommerce.exception.PaymentResultUnknownException;
import com.park.ecommerce.order.domain.Order;
import com.park.ecommerce.order.domain.OrderRepository;
import com.park.ecommerce.order.domain.OrderStatus;
import com.park.ecommerce.order.dto.OrderDetailResponse;
import com.park.ecommerce.order.dto.OrderPaymentRequest;
import com.park.ecommerce.order.dto.OrderPaymentResponse;
import com.park.ecommerce.client.payment.PaymentApiClient;
import com.park.ecommerce.client.payment.dto.PaymentApproveRequest;
import com.park.ecommerce.client.payment.dto.PaymentResponse;
import com.park.ecommerce.client.product.ProductApiClient;
import com.park.ecommerce.client.promotion.PromotionApiClient;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderPaymentService {
    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final ProductApiClient productApiClient;
    private final PaymentApiClient paymentApiClient;
    private final PromotionApiClient promotionApiClient;

    // 주문 상세 조회 ( 결제 정보 포함 )
    public OrderDetailResponse getOrderWithPayment(Long memberId, String orderNo) {
        OrderDetailResponse order = orderService.getOrder(memberId, orderNo);
        if (order.status() == OrderStatus.PAYMENT_WAITING || order.status() == OrderStatus.APPROVING) { // 결제 안된 주문은 단순히 주문 정보만 반환
            return order;
        }

        return order.withPayment(findPayment(orderNo));
    }

    // 주문에 대한 결제 정보 조회
    private PaymentResponse findPayment(String orderNo) {
        try {
            return paymentApiClient.findPayment(orderNo).orElse(null);
        } catch (PaymentResultUnknownException | CallNotPermittedException e) {
            log.warn("[주문] 결제 정보 조회 실패로 결제 정보 없이 응답 orderNo={}, 사유={}", orderNo, e.getMessage());
            return null;
        }
    }

    // 주문 결제 승인 (주문 검증 및 APPROVING로 전환)
    public OrderPaymentResponse approvePayment(Long memberId, String orderNo, OrderPaymentRequest request) {
        Order order = findOwnOrder(memberId, orderNo);

        if (order.getPaymentAmount() != request.amount()) { // 가격 검증 - 토스 연동 가이드 필수 항목
            throw new OrderException(OrderErrorCode.PAYMENT_AMOUNT_MISMATCH);
        }

        // 주문 상태를 APPROVING으로 전환
        if (!orderService.startApproval(orderNo, request.paymentKey())) {
            return respondToNotPayable(findOwnOrder(memberId, orderNo));
        }

        return toResponse(orderNo, processApproval(order, request.paymentKey(), ApprovalTrigger.USER));
    }

    // 승인 처리
    private ApprovalOutcome processApproval(Order order, String paymentKey, ApprovalTrigger trigger) {
        String orderNo = order.getOrderNo();
        try {
            // 1. 재고 선점 확정
            if (!productApiClient.confirmReservation(orderNo)) {
                orderService.failApproving(orderNo);
                log.debug("[결제] 재고 선점 만료로 주문 실패 처리 orderNo={}", orderNo);
                return ApprovalOutcome.RESERVATION_EXPIRED;
            }

            // 2. 쿠폰 선점 확정
            if (order.hasCoupon() && !promotionApiClient.confirmCoupon(orderNo)) {
                productApiClient.releaseReservation(orderNo);
                orderService.failApproving(orderNo);
                log.debug("[결제] 쿠폰 선점 만료로 재고 선점 해제 후 주문 실패 처리 orderNo={}", orderNo);
                return ApprovalOutcome.RESERVATION_EXPIRED;
            }

            // 3. 승인 요청 - 0원 주문은 청구할 금액이 없어 PG 승인을 생략
            boolean approved;
            try {
                approved = order.getPaymentAmount() == 0
                        || paymentApiClient.approve(new PaymentApproveRequest(orderNo, paymentKey, order.getPaymentAmount())).isDone();
            } catch (CallNotPermittedException e) {
                // 복구 중이면 이전 승인 요청이 이미 결제됐을 수 있어 실패로 확정하지 않음 - 바깥 catch에서 결과 불명으로 남김
                if (trigger == ApprovalTrigger.RECOVERY) throw e;
                return failWithoutApproval(order);
            }

            if (approved) {
                orderService.markPaid(orderNo);
                log.debug("[결제] 결제 완료 orderNo={}", orderNo);
                return ApprovalOutcome.PAID;
            }

            // 승인 거절 시, 쿠폰 해제한 후 재고 선점을 해제하여, 쿠폰이 사용 상태로 남는 문제를 방지
            if (order.hasCoupon()) promotionApiClient.releaseCoupon(orderNo);
            productApiClient.releaseReservation(orderNo);
            orderService.failApproving(orderNo);
            log.debug("[결제] 결제 거절로 선점 해제 후 주문 실패 처리 orderNo={}", orderNo);
            return ApprovalOutcome.PAYMENT_FAILED;

        // 승인 시작 후 발생한 예외는 결제 성공 여부를 알 수 없으므로 복구 작업이 재처리
        } catch (RuntimeException e) {
            log.warn("[결제] 승인 중 오류로 승인 중 유지 orderNo={}", orderNo, e);
            orderService.markApprovalUnknown(orderNo);
            return ApprovalOutcome.UNKNOWN;
        }
    }

    // 서킷이 열려 최초 승인 요청이 전달되지 않은 경우 주문을 실패 처리한 후 쿠폰과 재고 선점을 해제. 해제에 실패하더라도 승인 중 상태로 되돌리지 않으며, 로그를 통해 확인 후 수동으로 해제
    private ApprovalOutcome failWithoutApproval(Order order) {
        String orderNo = order.getOrderNo();
        orderService.failApproving(orderNo);

        // 승인 거절 처리와 같은 순서로 쿠폰을 먼저 해제
        if (order.hasCoupon()) {
            try {
                promotionApiClient.releaseCoupon(orderNo);
            } catch (RuntimeException e) {
                log.error("[결제] 실패 처리한 주문의 쿠폰 해제 실패, 수동 해제 필요 orderNo={}, 사유={}", orderNo, e.getMessage());
            }
        }
        try {
            productApiClient.releaseReservation(orderNo);
        } catch (RuntimeException e) {
            log.error("[결제] 실패 처리한 주문의 재고 선점 해제 실패, 수동 해제 필요 orderNo={}, 사유={}", orderNo, e.getMessage());
        }

        log.warn("[결제] payment-service 서킷 열림으로 승인 요청 없이 주문 실패 처리 orderNo={}", orderNo);
        return ApprovalOutcome.PAYMENT_SERVICE_UNAVAILABLE;
    }

    // 결제 금액이 0원인 주문 - 토스 결제창을 띄울 수 없어 주문 생성 직후 PG 승인 없이 선점만 확정
    public OrderPaymentResponse approveFreeOrder(Order order) {
        if (!orderService.startApproval(order.getOrderNo(), null)) {
            throw new OrderException(OrderErrorCode.ORDER_EXPIRED);
        }
        return toResponse(order.getOrderNo(), processApproval(order, null, ApprovalTrigger.USER));
    }

    private OrderPaymentResponse respondToNotPayable(Order order) {
        // 이미 완료된 결제에 오류를 내지 않고 성공 결과를 응답
        if (order.getStatus() == OrderStatus.PAID) {
            return new OrderPaymentResponse(order.getOrderNo(), OrderStatus.PAID);
        }

        throw new OrderException(switch (order.getStatus()) {
            case APPROVING -> OrderErrorCode.PAYMENT_IN_PROGRESS; // 결제 승인 진행 중
            case PAYMENT_WAITING -> OrderErrorCode.ORDER_EXPIRED; // 결제 기한이 지난 것
            default -> OrderErrorCode.ORDER_NOT_PAYABLE;
        });
    }

    // 승인 중으로 멈춘 주문을 처음부터 다시 처리 - 재고·쿠폰 확정, 결제 승인, 해제가 모두 멱등이라 다시 실행해도 안전
    public OrderStatus recoverApproval(String orderNo) {
        Order order = orderRepository.findByOrderNo(orderNo).orElseThrow();
        if (order.getStatus() != OrderStatus.APPROVING) {
            return order.getStatus();
        }

        return switch (processApproval(order, order.getPaymentKey(), ApprovalTrigger.RECOVERY)) {
            case PAID -> OrderStatus.PAID;
            case RESERVATION_EXPIRED, PAYMENT_FAILED, PAYMENT_SERVICE_UNAVAILABLE -> OrderStatus.FAILED;
            case UNKNOWN -> OrderStatus.APPROVING;
        };
    }

    // 다른 회원의 주문은 존재 여부도 드러내지 않도록 없는 주문과 같은 404로 응답
    private Order findOwnOrder(Long memberId, String orderNo) {
        return orderRepository.findByOrderNoAndMemberId(orderNo, memberId)
                .orElseThrow(() -> new OrderException(OrderErrorCode.ORDER_NOT_FOUND));
    }

    private OrderPaymentResponse toResponse(String orderNo, ApprovalOutcome outcome) {
        return switch (outcome) {
            case PAID -> new OrderPaymentResponse(orderNo, OrderStatus.PAID);
            case RESERVATION_EXPIRED -> throw new OrderException(OrderErrorCode.ORDER_EXPIRED);
            case PAYMENT_FAILED -> throw new OrderException(OrderErrorCode.PAYMENT_FAILED);
            case PAYMENT_SERVICE_UNAVAILABLE -> throw new OrderException(OrderErrorCode.PAYMENT_SERVICE_UNAVAILABLE);
            case UNKNOWN -> new OrderPaymentResponse(orderNo, OrderStatus.APPROVING); // 타임 아웃 등등
        };
    }

    private enum ApprovalOutcome {
        PAID,
        RESERVATION_EXPIRED,
        PAYMENT_FAILED,
        PAYMENT_SERVICE_UNAVAILABLE, // 서킷 열림으로 승인 요청을 보내지 못해 결제 없이 실패 처리한 경우
        UNKNOWN // 원격 호출 결과를 몰라 승인 중으로 남은 경우
    }

    // 승인을 누가 시작했는지 - 서킷 열림을 실패로 확정해도 되는지가 달라짐
    private enum ApprovalTrigger {
        USER, // startApproval 직후라 이 주문의 첫 승인 요청임이 보장됨
        RECOVERY // 이전 승인 요청이 이미 payment-service에 도달했을 수 있음
    }
}
