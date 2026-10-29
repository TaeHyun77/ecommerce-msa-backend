package com.park.ecommerce.coupon.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface MemberCouponRepository extends JpaRepository<MemberCoupon, Long> {
    boolean existsByCouponIdAndMemberId(Long couponId, Long memberId);

    long countByCouponId(Long couponId);

    @Query("select mc.slotNo from MemberCoupon mc where mc.coupon.id = :couponId and mc.slotNo is not null")
    List<Integer> findSlotNosByCouponId(Long couponId);
}
