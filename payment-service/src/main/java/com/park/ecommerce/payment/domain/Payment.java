package com.park.ecommerce.payment.domain;

import com.park.ecommerce.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Objects;

// 주문 1건의 토스페이먼츠 결제 승인 기록 - 승인 결과를 모르는 동안은 REQUESTED 상태로 같은 요청이 오면 승인을 다시 시도함
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
public class Payment extends BaseTimeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // order-service의 주문번호 - 토스페이먼츠 orderId이자 승인 멱등키, 주문 1건에 결제 1건
    @Column(nullable = false, unique = true, length = 36)
    private String orderNo;

    @Column(nullable = false, length = 200) // 토스페이먼츠 paymentKey 최대 길이
    private String paymentKey;

    @Column(nullable = false)
    private Integer amount; // 결제 금액 (원)

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    private String method; // 결제 수단 (카드, 간편결제 등) - 승인 전에는 null

    private LocalDateTime approvedAt; // 승인 전에는 null

    @Column(length = 500)
    private String failReason; // 승인 실패 전에는 null

    @Builder
    private Payment(String orderNo, String paymentKey, Integer amount) {
        this.orderNo = orderNo;
        this.paymentKey = paymentKey;
        this.amount = amount;
        this.status = PaymentStatus.REQUESTED;
    }

    public boolean isCompleted() {
        return status != PaymentStatus.REQUESTED;
    }

    public void approve(String method, LocalDateTime approvedAt) {
        validateNotCompleted();
        this.status = PaymentStatus.DONE;
        this.method = method;
        this.approvedAt = approvedAt;
    }

    public void fail(String reason) {
        validateNotCompleted();
        this.status = PaymentStatus.FAILED;
        this.failReason = reason;
    }

    // 토스에서 확정된 결과는 바뀌지 않으므로, 이미 확정된 결제를 다시 바꾸려는 것은 처리 흐름의 오류로 판단
    private void validateNotCompleted() {
        if (isCompleted()) {
            throw new IllegalStateException("이미 결과가 확정된 결제입니다. orderNo=" + orderNo);
        }
    }
}
