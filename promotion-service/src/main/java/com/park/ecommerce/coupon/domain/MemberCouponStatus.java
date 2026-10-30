package com.park.ecommerce.coupon.domain;

public enum MemberCouponStatus {
    ISSUED, // 발급 - 다운로드했고 아직 주문에 쓰지 않은 상태
    RESERVED, // 선점 - 주문 생성 시 해당 주문에 묶여 다른 주문에 쓸 수 없는 상태
    USED // 사용 확정 - PG 승인 직전에 확정되어 만료되지 않는 상태, 승인이 거절되면 ISSUED로 돌아감
}
