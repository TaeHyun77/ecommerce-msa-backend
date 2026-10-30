package com.park.ecommerce.coupon.dto;

import com.park.ecommerce.coupon.domain.Coupon;
import com.park.ecommerce.coupon.domain.MemberCoupon;
import com.park.ecommerce.promotion.DiscountType;

import java.time.LocalDateTime;

// 내 쿠폰 1장 - 마이페이지 탭 구분은 클라이언트가 status로 함
public record MemberCouponResponse(
        Long memberCouponId,
        Long couponId,
        String name,
        DiscountType discountType,
        Integer discountValue,
        LocalDateTime useStartAt,
        LocalDateTime useEndAt,
        LocalDateTime issuedAt,
        Status status
) {
    public enum Status {
        AVAILABLE,   // 사용 가능
        NOT_STARTED, // 사용 기간 시작 전
        EXPIRED,     // 쓰지 않은 채 사용 기간 종료
        RESERVED,    // 진행 중인 주문에 묶임
        USED         // 사용 완료
    }

    public static MemberCouponResponse from(MemberCoupon memberCoupon, LocalDateTime now) {
        Coupon coupon = memberCoupon.getCoupon();
        return new MemberCouponResponse(
                memberCoupon.getId(),
                coupon.getId(),
                coupon.getName(),
                coupon.getDiscount().getDiscountType(),
                coupon.getDiscount().getDiscountValue(),
                coupon.getUseStartAt(),
                coupon.getUseEndAt(),
                memberCoupon.getCreatedAt(),
                toStatus(memberCoupon, now)
        );
    }

    // 만료는 저장된 상태가 아니라 사용 기간으로 판단 - 예약·사용된 쿠폰은 기간이 지나도 그 상태를 그대로 보여줌
    private static Status toStatus(MemberCoupon memberCoupon, LocalDateTime now) {
        Coupon coupon = memberCoupon.getCoupon();
        return switch (memberCoupon.getStatus()) {
            case RESERVED -> Status.RESERVED;
            case USED -> Status.USED;
            case ISSUED -> {
                if (now.isBefore(coupon.getUseStartAt())) yield Status.NOT_STARTED;
                if (coupon.isInUsePeriod(now)) yield Status.AVAILABLE;
                yield Status.EXPIRED;
            }
        };
    }
}
