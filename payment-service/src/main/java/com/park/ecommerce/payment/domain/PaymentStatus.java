package com.park.ecommerce.payment.domain;

public enum PaymentStatus {
    REQUESTED, // 승인 요청 - 토스 승인 결과를 아직 확정하지 못한 상태, 같은 요청이 다시 오면 승인을 다시 시도
    DONE, // 승인 완료
    FAILED, // 승인 실패 - 토스 결제 상태가 ABORTED/EXPIRED로 확정된 경우
    CANCELED // 승인 후 전액 취소 - 환불이 끝난 상태
}
