package com.park.ecommerce.coupon.domain;

public enum MemberCouponStatus {
    ISSUED, // 발급 - 다운로드했고 아직 주문에 쓰지 않은 상태
    RESERVED, // 사용 예약 - 주문 생성 시 해당 주문에 묶여 다른 주문에 쓸 수 없는 상태
    USED // 사용 완료 - 묶인 주문의 결제가 완료된 상태
}
