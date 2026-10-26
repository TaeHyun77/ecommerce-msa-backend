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
        LocalDateTime approvedAt
) {
    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getOrderNo(),
                payment.getStatus(),
                payment.getAmount(),
                payment.getMethod(),
                payment.getApprovedAt()
        );
    }
}
