package com.park.ecommerce.order.dto;

import com.park.ecommerce.client.payment.dto.PaymentResponse;
import com.park.ecommerce.order.domain.Order;
import com.park.ecommerce.order.domain.OrderStatus;

import java.time.LocalDateTime;
import java.util.List;

public record OrderDetailResponse(
        String orderNo,
        OrderStatus status,
        String orderName,
        Integer totalAmount, // 할인 전 상품 합계 (원)
        Integer discountAmount, // 쿠폰 할인액 (원)
        Integer paymentAmount, // 결제 금액 (원)
        LocalDateTime orderedAt,
        LocalDateTime expiresAt,
        List<Line> lines,
        Payment payment // 결제 승인을 요청하기 전이거나 결제 정보를 가져오지 못하면 null
) {
    public record Line(Long productId, String productName, Integer unitPrice, Integer quantity) {
    }

    public record Payment(String status, Integer amount, String method, LocalDateTime approvedAt) {
    }

    public static OrderDetailResponse from(Order order) {
        List<Line> lines = order.getLines().stream()
                .map(line -> new Line(line.getProductId(), line.getProductName(), line.getUnitPrice(), line.getQuantity()))
                .toList();
        return new OrderDetailResponse(
                order.getOrderNo(),
                order.getStatus(),
                order.getOrderName(),
                order.getTotalAmount(),
                order.getDiscountAmount(),
                order.getPaymentAmount(),
                order.getOrderedAt(),
                order.getExpiresAt(),
                lines,
                null
        );
    }

    public OrderDetailResponse withPayment(PaymentResponse payment) {
        if (payment == null) return this;

        return new OrderDetailResponse(
                orderNo, status, orderName, totalAmount, discountAmount, paymentAmount, orderedAt, expiresAt, lines,
                new Payment(payment.status(), payment.amount(), payment.method(), payment.approvedAt())
        );
    }
}
