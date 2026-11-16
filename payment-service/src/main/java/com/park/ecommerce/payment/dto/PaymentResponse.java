package com.park.ecommerce.payment.dto;

import com.park.ecommerce.payment.domain.Payment;
import com.park.ecommerce.payment.domain.PaymentStatus;

import java.time.LocalDateTime;

// 주문 상세에 보여줄 결제 정보
public record PaymentResponse(
        String orderNo,
        PaymentStatus status,
        Integer amount,
        String method,
        LocalDateTime approvedAt,
        LocalDateTime canceledAt // 취소 전에는 null
) {
    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getOrderNo(),
                payment.getStatus(),
                payment.getAmount(),
                payment.getMethod(),
                payment.getApprovedAt(),
                payment.getCanceledAt()
        );
    }
}
