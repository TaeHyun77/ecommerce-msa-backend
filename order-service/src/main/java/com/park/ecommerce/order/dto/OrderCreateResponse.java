package com.park.ecommerce.order.dto;

import com.park.ecommerce.order.domain.Order;
import com.park.ecommerce.order.domain.OrderStatus;

import java.time.LocalDateTime;

// 클라이언트가 토스페이먼츠 결제창을 띄우는 데 필요한 값 - orderNo는 orderId, orderName과 amount는 그대로 결제 요청에 사용
// 결제 금액이 0원인 주문은 결제창 없이 생성 요청에서 바로 처리하므로 status로 결과(PAID, APPROVING)를 알림
public record OrderCreateResponse(
        String orderNo,
        String orderName,
        Integer amount, // 결제 금액 (원) - 쿠폰 할인 후
        LocalDateTime expiresAt,
        OrderStatus status
) {
    public static OrderCreateResponse from(Order order) {
        return of(order, OrderStatus.PAYMENT_WAITING);
    }

    public static OrderCreateResponse of(Order order, OrderStatus status) {
        return new OrderCreateResponse(order.getOrderNo(), order.getOrderName(), order.getPaymentAmount(), order.getExpiresAt(), status);
    }
}
