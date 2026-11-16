package com.park.ecommerce.order;

import com.park.ecommerce.client.payment.PaymentApiClient;
import com.park.ecommerce.client.product.ProductApiClient;
import com.park.ecommerce.client.promotion.PromotionApiClient;
import com.park.ecommerce.exception.OrderErrorCode;
import com.park.ecommerce.exception.OrderException;
import com.park.ecommerce.order.domain.Order;
import com.park.ecommerce.order.domain.OrderRepository;
import com.park.ecommerce.order.domain.OrderStatus;
import com.park.ecommerce.order.dto.OrderCancelResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// 결제 완료 주문의 취소 - 전액 환불 → 쿠폰 복구 → 재고 복구 → 취소 완료
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCancelService {
    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final PaymentApiClient paymentApiClient;
    private final PromotionApiClient promotionApiClient;
    private final ProductApiClient productApiClient;

    public OrderCancelResponse cancelOrder(Long memberId, String orderNo) {
        Order order = findOwnOrder(memberId, orderNo);

        if (!orderService.startCancel(orderNo)) {
            return respondToNotPaid(findOwnOrder(memberId, orderNo));
        }
        return new OrderCancelResponse(orderNo, processCancel(order));
    }

    private OrderStatus processCancel(Order order) {
        String orderNo = order.getOrderNo();
        try {
            // 0원 주문은 PG 승인 없이 완료됐으므로 환불할 결제가 없음
            if (order.getPaymentAmount() > 0) paymentApiClient.cancel(orderNo);
            if (order.hasCoupon()) promotionApiClient.releaseCoupon(orderNo);
            productApiClient.releaseReservation(orderNo);

            orderService.markCancelled(orderNo);
            log.debug("[주문 취소] 취소 완료 orderNo={}", orderNo);
            return OrderStatus.CANCELLED;
        } catch (RuntimeException e) {
            log.warn("[주문 취소] 취소 중 오류로 취소 중 유지 orderNo={}", orderNo, e);
            orderService.markCancelUnknown(orderNo);
            return OrderStatus.CANCELLING;
        }
    }

    // 취소 중으로 멈춘 주문을 처음부터 다시 처리
    public OrderStatus recoverCancel(String orderNo) {
        Order order = orderRepository.findByOrderNo(orderNo).orElseThrow();
        if (order.getStatus() != OrderStatus.CANCELLING) {
            return order.getStatus();
        }
        return processCancel(order);
    }

    // 이미 취소했거나 취소 중인 주문의 재요청은 오류 없이 현재 상태로 응답
    private OrderCancelResponse respondToNotPaid(Order order) {
        if (order.getStatus() == OrderStatus.CANCELLED || order.getStatus() == OrderStatus.CANCELLING) {
            return new OrderCancelResponse(order.getOrderNo(), order.getStatus());
        }
        throw new OrderException(OrderErrorCode.ORDER_NOT_CANCELABLE);
    }

    // 다른 회원의 주문은 존재 여부도 드러내지 않도록 없는 주문과 같은 404로 응답
    private Order findOwnOrder(Long memberId, String orderNo) {
        return orderRepository.findByOrderNoAndMemberId(orderNo, memberId)
                .orElseThrow(() -> new OrderException(OrderErrorCode.ORDER_NOT_FOUND));
    }
}
