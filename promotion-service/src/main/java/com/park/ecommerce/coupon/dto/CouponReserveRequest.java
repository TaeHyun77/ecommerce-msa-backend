package com.park.ecommerce.coupon.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDateTime;

// order-service가 주문을 만들 때 보내는 쿠폰 선점 요청
// 만료 시각은 주문의 결제 기한과 같아야 하므로 order-service가 정해서 보내도록 함
public record CouponReserveRequest(
        @NotNull(message = "회원 쿠폰은 필수입니다.")
        Long memberCouponId,

        @NotNull(message = "회원은 필수입니다.")
        Long memberId,

        @NotBlank(message = "주문번호는 필수입니다.")
        String orderNo,

        @NotNull(message = "주문 금액은 필수입니다.")
        @Positive(message = "주문 금액은 0보다 커야 합니다.")
        Integer orderAmount, // 할인 전 주문 상품 합계 (원)

        @NotNull(message = "선점 만료 시각은 필수입니다.")
        @Future(message = "선점 만료 시각은 현재 이후여야 합니다.")
        LocalDateTime expiresAt
) {
}
