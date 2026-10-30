package com.park.ecommerce.coupon.dto;

// 결제 금액은 order-service가 주문 금액에서 이 할인액을 빼서 계산
public record CouponReserveResponse(
        int discountAmount // 원
) {
}
