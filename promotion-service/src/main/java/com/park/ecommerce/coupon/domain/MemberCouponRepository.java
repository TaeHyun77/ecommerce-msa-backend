package com.park.ecommerce.coupon.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface MemberCouponRepository extends JpaRepository<MemberCoupon, Long> {
    boolean existsByCouponIdAndMemberId(Long couponId, Long memberId);

    // 목록에서 쿠폰 이름, 할인 정보까지 보여주므로 함께 조회 - 발급 내역마다 쿠폰을 따로 조회하는 N+1을 막음
    @EntityGraph(attributePaths = "coupon")
    List<MemberCoupon> findAllWithCouponByMemberIdOrderByIdDesc(Long memberId);

    // 선점 시 사용 기간 확인과 할인액 계산에 쿠폰 정보가 필요하므로 함께 조회
    @EntityGraph(attributePaths = "coupon")
    Optional<MemberCoupon> findWithCouponByIdAndMemberId(Long id, Long memberId);

    Optional<MemberCoupon> findByOrderNo(String orderNo);

    long countByCouponId(Long couponId);

    @Query("select mc.slotNo from MemberCoupon mc where mc.coupon.id = :couponId and mc.slotNo is not null")
    List<Integer> findSlotNosByCouponId(Long couponId);

    // 같은 쿠폰의 동시 선점은 상태 조건으로 하나만 성공
    @Modifying
    @Query("update MemberCoupon mc set mc.status = RESERVED, mc.orderNo = :orderNo, mc.reservedUntil = :reservedUntil, mc.updatedAt = current_timestamp where mc.id = :id and mc.status = ISSUED")
    int reserve(Long id, String orderNo, LocalDateTime reservedUntil);

    @Modifying
    @Query("update MemberCoupon mc set mc.status = USED, mc.usedAt = :now, mc.updatedAt = current_timestamp where mc.id = :id and mc.orderNo = :orderNo and mc.status = RESERVED and mc.reservedUntil > :now")
    int confirm(Long id, String orderNo, LocalDateTime now);

    // 확정된 쿠폰도 해제 대상
    @Modifying
    @Query("update MemberCoupon mc set mc.status = ISSUED, mc.orderNo = null, mc.reservedUntil = null, mc.usedAt = null, mc.updatedAt = current_timestamp where mc.id = :id and mc.orderNo = :orderNo and mc.status in (RESERVED, USED)")
    int release(Long id, String orderNo);

    // 확정된 쿠폰은 결제 기한이 지나도 해제하지 않음 - PG 승인 중일 수 있어 승인 결과에 따라 order-service가 해제
    @Query("select mc.id from MemberCoupon mc where mc.status = RESERVED and mc.reservedUntil <= :now order by mc.id")
    List<Long> findExpiredIds(LocalDateTime now, Pageable pageable);

    // 스케줄러가 대상을 조회한 뒤 그 사이 확정, 해제됐을 수 있어 같은 조건을 UPDATE에서 다시 확인
    @Modifying
    @Query("update MemberCoupon mc set mc.status = ISSUED, mc.orderNo = null, mc.reservedUntil = null, mc.updatedAt = current_timestamp where mc.id = :id and mc.status = RESERVED and mc.reservedUntil <= :now")
    int expire(Long id, LocalDateTime now);
}
