package com.park.ecommerce.coupon.domain;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface MemberCouponRepository extends JpaRepository<MemberCoupon, Long> {
    boolean existsByCouponIdAndMemberId(Long couponId, Long memberId);

    // 목록에서 쿠폰 이름, 할인 정보까지 보여주므로 함께 조회 - 발급 내역마다 쿠폰을 따로 조회하는 N+1을 막음
    @EntityGraph(attributePaths = "coupon")
    List<MemberCoupon> findAllWithCouponByMemberIdOrderByIdDesc(Long memberId);

    long countByCouponId(Long couponId);

    @Query("select mc.slotNo from MemberCoupon mc where mc.coupon.id = :couponId and mc.slotNo is not null")
    List<Integer> findSlotNosByCouponId(Long couponId);
}
