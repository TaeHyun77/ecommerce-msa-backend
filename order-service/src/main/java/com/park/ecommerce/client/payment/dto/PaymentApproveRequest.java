package com.park.ecommerce.client.payment.dto;

// payment-service 결제 승인 API의 요청 형식을 그대로 옮긴 DTO - 필드 이름이 곧 서비스 간 계약
public record PaymentApproveRequest(String orderNo, String paymentKey, Integer amount) {
}
