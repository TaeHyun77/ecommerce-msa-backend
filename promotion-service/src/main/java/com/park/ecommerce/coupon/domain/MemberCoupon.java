package com.park.ecommerce.coupon.domain;

import com.park.ecommerce.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// 회원이 다운로드한 쿠폰 1장 - 쿠폰당 1인 1장과 선착순 발급 번호는 유니크 제약으로 보장해 동시 발급 요청에도 중복되지 않음
@Getter
@Entity
@Table(uniqueConstraints = {
        @UniqueConstraint(name = MemberCoupon.UK_COUPON_MEMBER, columnNames = {"coupon_id", "member_id"}),
        @UniqueConstraint(name = MemberCoupon.UK_COUPON_SLOT, columnNames = {"coupon_id", "slot_no"})
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberCoupon extends BaseTimeEntity {
    // 중복 발급 예외를 다른 제약 위반과 구분할 때도 쓰므로 상수로 둠
    public static final String UK_COUPON_MEMBER = "uk_member_coupon_coupon_member";
    public static final String UK_COUPON_SLOT = "uk_member_coupon_slot";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "coupon_id", nullable = false)
    private Coupon coupon;

    @Column(nullable = false)
    private Long memberId;

    // 선착순 쿠폰의 발급 번호 (1~수량), 기본 쿠폰은 null
    @Column(updatable = false)
    private Integer slotNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private MemberCouponStatus status;

    @Column(updatable = false)
    private String orderNo;

    @Column(updatable = false)
    private LocalDateTime usedAt;

    @Builder
    private MemberCoupon(Coupon coupon, Long memberId, Integer slotNo) {
        this.coupon = coupon;
        this.memberId = memberId;
        this.slotNo = slotNo;
        this.status = MemberCouponStatus.ISSUED;
    }
}
