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
import com.park.ecommerce.client.product.ProductApiClient;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// 결제 승인 흐름 조율 - 선점 확정(product-service)과 PG 승인(payment-service)처럼 오래 걸릴 수 있는 원격 호출을 DB 트랜잭션 밖에서 하고,
// 각 상태 변경은 OrderService의 짧은 트랜잭션으로 바로 커밋해 중간에 멈춰도 복구 스케줄러가 이어서 처리할 수 있도록
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderPaymentService {
    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final ProductApiClient productApiClient;
    private final PaymentApiClient paymentApiClient;

    // 결제창 인증을 마친 주문의 승인 - 결과를 모르면 APPROVING을 돌려주고, 클라이언트는 주문 조회로 최종 결과를 확인한다
    public OrderPaymentResponse approve(Long memberId, String orderNo, OrderPaymentRequest request) {
        Order order = findOwnOrder(memberId, orderNo);
        // 결제를 마친 뒤 성공 페이지를 새로고침하는 등 같은 요청이 다시 온 경우
        if (order.getStatus() == OrderStatus.PAID) {
            return new OrderPaymentResponse(orderNo, OrderStatus.PAID);
        }
        // successUrl의 금액은 브라우저를 거쳐 조작될 수 있어, 주문 금액과 같을 때만 승인한다 - 토스 연동 가이드의 필수 검증
        if (!order.getTotalAmount().equals(request.amount())) {
            throw new OrderException(OrderErrorCode.PAYMENT_AMOUNT_MISMATCH);
        }
        if (!orderService.startApproval(orderNo, request.paymentKey())) {
            return rejectNotPayable(findOwnOrder(memberId, orderNo));
        }

        return switch (proceedApproval(orderNo, request.paymentKey(), order.getTotalAmount())) {
            case PAID -> new OrderPaymentResponse(orderNo, OrderStatus.PAID);
            case RESERVATION_EXPIRED -> throw new OrderException(OrderErrorCode.ORDER_EXPIRED);
            case PAYMENT_FAILED -> throw new OrderException(OrderErrorCode.PAYMENT_FAILED);
            case UNKNOWN -> new OrderPaymentResponse(orderNo, OrderStatus.APPROVING);
        };
    }

    // 승인 중으로 멈춘 주문을 처음부터 다시 처리한다 - 선점 확정·결제 승인·선점 해제가 모두 멱등이라 다시 실행해도 안전
    public OrderStatus recover(String orderNo) {
        Order order = orderRepository.findByOrderNo(orderNo).orElseThrow();
        if (order.getStatus() != OrderStatus.APPROVING) {
            return order.getStatus();
        }

        return switch (proceedApproval(orderNo, order.getPaymentKey(), order.getTotalAmount())) {
            case PAID -> OrderStatus.PAID;
            case RESERVATION_EXPIRED, PAYMENT_FAILED -> OrderStatus.CANCELLED;
            case UNKNOWN -> OrderStatus.APPROVING;
        };
    }

    private ApprovalOutcome proceedApproval(String orderNo, String paymentKey, int amount) {
        try {
            // PG 승인 전에 선점을 확정한다 - 선점이 만료된 주문은 청구 전에 막고, 확정된 선점은 더 이상 만료되지 않는다
            if (!productApiClient.confirmReservation(orderNo)) {
                orderService.cancelApproving(orderNo);
                log.info("[결제] 재고 선점 만료로 주문 취소 orderNo={}", orderNo);
                return ApprovalOutcome.RESERVATION_EXPIRED;
            }

            if (paymentApiClient.approve(new PaymentApproveRequest(orderNo, paymentKey, amount)).isDone()) {
                orderService.markPaid(orderNo);
                log.info("[결제] 결제 완료 orderNo={}", orderNo);
                return ApprovalOutcome.PAID;
            }

            // 승인이 거절되면 확정한 선점을 해제해 재고를 되돌린 뒤 취소한다 - 해제에 실패하면 승인 중으로 남아 복구에서 다시 해제
            productApiClient.releaseReservation(orderNo);
            orderService.cancelApproving(orderNo);
            log.info("[결제] 결제 거절로 선점 해제 후 주문 취소 orderNo={}", orderNo);
            return ApprovalOutcome.PAYMENT_FAILED;
        } catch (ProductServiceUnavailableException | CallNotPermittedException | PaymentResultUnknownException e) {
            // 원격 호출의 결과를 모르는 경우 - 승인 중으로 남겨 두면 복구 스케줄러가 같은 흐름을 다시 실행한다
            log.warn("[결제] 결과 불명으로 승인 중 유지 orderNo={}, 사유={}", orderNo, e.getMessage());
            return ApprovalOutcome.UNKNOWN;
        }
    }

    private OrderPaymentResponse rejectNotPayable(Order order) {
        // 동시에 들어온 같은 요청이 먼저 결제를 끝낸 경우
        if (order.getStatus() == OrderStatus.PAID) {
            return new OrderPaymentResponse(order.getOrderNo(), OrderStatus.PAID);
        }
        throw new OrderException(switch (order.getStatus()) {
            case APPROVING -> OrderErrorCode.PAYMENT_IN_PROGRESS;
            case PAYMENT_WAITING -> OrderErrorCode.ORDER_EXPIRED; // 결제 대기인데 승인으로 못 바꿨다면 결제 기한이 지난 것
            default -> OrderErrorCode.ORDER_NOT_PAYABLE;
        });
    }

    // 다른 회원의 주문은 존재 여부도 드러내지 않도록 없는 주문과 같은 404로 응답
    private Order findOwnOrder(Long memberId, String orderNo) {
        return orderRepository.findByOrderNoAndMemberId(orderNo, memberId)
                .orElseThrow(() -> new OrderException(OrderErrorCode.ORDER_NOT_FOUND));
    }

    private enum ApprovalOutcome {
        PAID,
        RESERVATION_EXPIRED,
        PAYMENT_FAILED,
        UNKNOWN // 원격 호출 결과를 몰라 승인 중으로 남은 경우
    }
}
