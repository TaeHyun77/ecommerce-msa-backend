package com.park.ecommerce.payment.dto;

import com.park.ecommerce.payment.domain.Payment;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

// order-service가 결제 인증을 마친 주문의 승인을 요청
public record PaymentApproveRequest(
        @NotBlank(message = "주문번호는 필수입니다.")
        String orderNo,

        @NotBlank(message = "결제 키는 필수입니다.")
        String paymentKey,

        @NotNull(message = "결제 금액은 필수입니다.")
        @Positive(message = "결제 금액은 0원보다 커야 합니다.")
        Integer amount
) {
    public Payment toEntity() {
        return Payment.builder()
                .orderNo(orderNo)
                .paymentKey(paymentKey)
                .amount(amount)
                .build();
    }
}
