package com.park.ecommerce.client.payment.dto;

import java.time.LocalDateTime;

// payment-service의 결제 정보 응답 중 주문 상세에 필요한 필드만 직접 정의한 DTO - 승인 전에는 method, approvedAt이 null
public record PaymentResponse(String status, Integer amount, String method, LocalDateTime approvedAt) {
}
