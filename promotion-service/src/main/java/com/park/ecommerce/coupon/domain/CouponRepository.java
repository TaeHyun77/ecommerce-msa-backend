package com.park.ecommerce.coupon.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface CouponRepository extends JpaRepository<Coupon, Long> {
    // 남은 수량이 있을 때만 차감
    @Modifying
    @Query("update Coupon c set c.quantity = c.quantity - 1, c.updatedAt = current_timestamp "
            + "where c.id = :couponId and c.quantity > 0")
    int decreaseRemainingQuantity(Long couponId);
}
