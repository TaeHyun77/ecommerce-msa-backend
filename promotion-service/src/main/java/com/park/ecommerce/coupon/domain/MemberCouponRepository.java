package com.park.ecommerce.coupon.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberCouponRepository extends JpaRepository<MemberCoupon, Long> {
    boolean existsByCouponIdAndMemberId(Long couponId, Long memberId);

    long countByCouponId(Long couponId);
}
