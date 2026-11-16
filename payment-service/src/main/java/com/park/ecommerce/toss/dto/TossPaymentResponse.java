package com.park.ecommerce.toss.dto;

import java.time.OffsetDateTime;
import java.util.List;

// 토스페이먼츠 Payment 객체 중 승인 결과 판단에 필요한 필드만 직접 정의
// status: READY, IN_PROGRESS, DONE, CANCELED, ABORTED, EXPIRED 등 / approvedAt: 승인 전에는 null / cancels: 취소 전에는 null
public record TossPaymentResponse(
        String paymentKey,
        String orderId,
        String status,
        String method,
        Integer totalAmount,
        OffsetDateTime approvedAt,
        List<Cancel> cancels
) {
    public record Cancel(OffsetDateTime canceledAt) {
    }

    // 전액 취소만 하므로 취소 내역은 1건
    public OffsetDateTime canceledAt() {
        return cancels.get(0).canceledAt();
    }
}
