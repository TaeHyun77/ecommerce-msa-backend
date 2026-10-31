package com.park.ecommerce.order.domain;

public enum OrderStatus {
    PAYMENT_WAITING, // 결제 대기 - 재고를 선점하고 결제를 기다리는 상태, 결제 기한이 지나면 실패
    APPROVING, // 결제 승인 중 - 선점 확정과 PG 승인을 진행하는 상태, 결과를 모르면 이 상태로 남아 복구 스케줄러가 다시 처리
    PAID, // 결제 완료
    FAILED // 실패 - 결제 기한 만료(확정 시점의 재고·쿠폰 선점 만료 포함), 결제 거절 - 선점에 실패한 주문은 저장하지 않음
}
