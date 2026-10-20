package com.park.ecommerce.client.payment.dto;

// payment-service는 결과가 확정된 경우에만 응답한다 - status는 DONE 또는 FAILED
public record PaymentApproveResponse(String orderNo, String status, String failReason) {
    private static final String DONE = "DONE";

    public boolean isDone() {
        return DONE.equals(status);
    }
}
