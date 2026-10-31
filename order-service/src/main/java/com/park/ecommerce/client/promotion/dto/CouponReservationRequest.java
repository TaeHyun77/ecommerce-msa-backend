package com.park.ecommerce.client.promotion.dto;

import com.park.ecommerce.order.domain.Order;

import java.time.LocalDateTime;

// promotion-service 쿠폰 선점 API의 요청 형식을 그대로 옮긴 DTO - 필드 이름이 곧 서비스 간 계약
public record CouponReservationRequest(
        Long memberCouponId,
        Long memberId,
        String orderNo,
        Integer orderAmount, // 할인 전 상품 합계 (원)
        LocalDateTime expiresAt
) {
    // 할인 기준이 상품 합계이므로 품목을 모두 담은 뒤 호출해야 함
    public static CouponReservationRequest of(Order order, Long memberCouponId) {
        return new CouponReservationRequest(memberCouponId, order.getMemberId(), order.getOrderNo(), order.getTotalAmount(), order.getExpiresAt());
    }
}
