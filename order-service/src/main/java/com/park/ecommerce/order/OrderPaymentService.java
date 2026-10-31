package com.park.ecommerce.order;

import com.park.ecommerce.exception.OrderErrorCode;
import com.park.ecommerce.exception.OrderException;
import com.park.ecommerce.exception.PaymentResultUnknownException;
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
import com.park.ecommerce.client.payment.dto.PaymentResponse;
import com.park.ecommerce.client.product.ProductApiClient;
import com.park.ecommerce.client.promotion.PromotionApiClient;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// 2. 재고 선점 확정 → 쿠폰 확정 → 결제 승인 처리 (payment-service)
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
    public OrderDetailResponse getOrder(Long memberId, String orderNo) {
        OrderDetailResponse order = orderService.getOrder(memberId, orderNo);
        if (order.status() == OrderStatus.PAYMENT_WAITING || order.status() == OrderStatus.APPROVING) { // 결제 안된 주문은 단순히 주문 정보만 반환
            return order;
        }

        return order.withPayment(findPayment(orderNo));
    }

    // 주문에 대한 결제 정보 조회
    // 결제 정보를 가져오지 못해도 주문 상태는 응답하도록 함 - 클라이언트는 승인 중인 주문의 최종 상태를 주문 조회로 확인하므로 결제 정보 때문에 조회까지 실패하면 안 됨
    private PaymentResponse findPayment(String orderNo) {
        try {
            return paymentApiClient.findPayment(orderNo).orElse(null);
        } catch (PaymentResultUnknownException | CallNotPermittedException e) {
            log.warn("[주문] 결제 정보 조회 실패로 결제 정보 없이 응답 orderNo={}, 사유={}", orderNo, e.getMessage());
            return null;
        }
    }

    // 주문 검증 및 APPROVING로 전환
    public OrderPaymentResponse approve(Long memberId, String orderNo, OrderPaymentRequest request) {
        Order order = findOwnOrder(memberId, orderNo);

        // 가격 검증 - 토스 연동 가이드 필수 항목
        if (order.getPaymentAmount() != request.amount()) {
            throw new OrderException(OrderErrorCode.PAYMENT_AMOUNT_MISMATCH);
        }

        // 주문 상태를 APPROVING으로 전환 - 성공한 요청만 결제를 진행하고, 실패하면 현재 상태에 맞게 응답
        if (!orderService.startApproval(orderNo, request.paymentKey())) {
            return rejectNotPayable(findOwnOrder(memberId, orderNo));
        }

        return toResponse(orderNo, proceedApproval(order, request.paymentKey()));
    }

    // 결제 금액이 0원인 주문 - 토스 결제창을 띄울 수 없어 주문 생성 직후 PG 승인 없이 선점만 확정
    public OrderPaymentResponse approveWithoutPayment(Order order) {
        if (!orderService.startApproval(order.getOrderNo(), null)) {
            throw new OrderException(OrderErrorCode.ORDER_EXPIRED);
        }
        return toResponse(order.getOrderNo(), proceedApproval(order, null));
    }

    // 승인 처리
    private ApprovalOutcome proceedApproval(Order order, String paymentKey) {
        String orderNo = order.getOrderNo();
        try {
            // 1. PG 승인 전에 선점을 확정 - 선점이 만료된 주문은 청구 전에 막고, 확정된 선점은 더 이상 만료되지 않음
            if (!productApiClient.confirmReservation(orderNo)) {
                orderService.failApproving(orderNo);
                log.debug("[결제] 재고 선점 만료로 주문 실패 처리 orderNo={}", orderNo);
                return ApprovalOutcome.RESERVATION_EXPIRED;
            }

            // 2. 쿠폰 확정 - 재고와 결제 기한이 같지만 두 확정 사이에 기한이 지날 수 있음
            if (order.hasCoupon() && !promotionApiClient.confirmCoupon(orderNo)) {
                // 확정된 재고 선점은 만료 해제 대상이 아니므로 직접 해제해야 재고가 돌아옴
                productApiClient.releaseReservation(orderNo);
                orderService.failApproving(orderNo);
                log.debug("[결제] 쿠폰 선점 만료로 재고 선점 해제 후 주문 실패 처리 orderNo={}", orderNo);
                return ApprovalOutcome.RESERVATION_EXPIRED;
            }

            // 3. 승인 요청 - 0원 주문은 청구할 금액이 없어 PG 승인을 생략
            if (order.getPaymentAmount() == 0
                    || paymentApiClient.approve(new PaymentApproveRequest(orderNo, paymentKey, order.getPaymentAmount())).isDone()) {
                orderService.markPaid(orderNo);
                log.debug("[결제] 결제 완료 orderNo={}", orderNo);
                return ApprovalOutcome.PAID;
            }

            // 승인 거절 시 쿠폰과 재고 선점을 해제하고 주문을 실패 처리
            // 쿠폰을 먼저 해제 - 재고를 먼저 풀고 쿠폰 해제가 실패하면, 복구 때 재고 확정이 실패해 쿠폰이 사용 상태로 남음
            if (order.hasCoupon()) promotionApiClient.releaseCoupon(orderNo);
            productApiClient.releaseReservation(orderNo);
            orderService.failApproving(orderNo);
            log.debug("[결제] 결제 거절로 선점 해제 후 주문 실패 처리 orderNo={}", orderNo);
            return ApprovalOutcome.PAYMENT_FAILED;
        } catch (ProductServiceUnavailableException | PromotionServiceUnavailableException | CallNotPermittedException | PaymentResultUnknownException e) {
            log.warn("[결제] 결과 불명으로 승인 중 유지 orderNo={}, 사유={}", orderNo, e.getMessage());
            orderService.markApprovalUnknown(orderNo);
            return ApprovalOutcome.UNKNOWN;
        }
    }

    private OrderPaymentResponse rejectNotPayable(Order order) {
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
    public OrderStatus recover(String orderNo) {
        Order order = orderRepository.findByOrderNo(orderNo).orElseThrow();
        if (order.getStatus() != OrderStatus.APPROVING) {
            return order.getStatus();
        }

        return switch (proceedApproval(order, order.getPaymentKey())) {
            case PAID -> OrderStatus.PAID;
            case RESERVATION_EXPIRED, PAYMENT_FAILED -> OrderStatus.FAILED;
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
            case UNKNOWN -> new OrderPaymentResponse(orderNo, OrderStatus.APPROVING); // 타임 아웃 등등
        };
    }

    private enum ApprovalOutcome {
        PAID,
        RESERVATION_EXPIRED,
        PAYMENT_FAILED,
        UNKNOWN // 원격 호출 결과를 몰라 승인 중으로 남은 경우
    }
}
