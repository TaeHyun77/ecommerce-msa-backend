package com.park.ecommerce.payment.dto;

import com.park.ecommerce.payment.domain.Payment;
import com.park.ecommerce.payment.domain.PaymentStatus;

// order-service에 돌려주는 결제 승인 결과
public record PaymentApproveResponse(String orderNo, PaymentStatus status, String failReason) {
    public static PaymentApproveResponse from(Payment payment) {
        return new PaymentApproveResponse(payment.getOrderNo(), payment.getStatus(), payment.getFailReason());
    }
}
