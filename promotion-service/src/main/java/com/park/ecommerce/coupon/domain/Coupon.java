package com.park.ecommerce.coupon.domain;

import com.park.ecommerce.common.BaseTimeEntity;
import com.park.ecommerce.promotion.Discount;
import com.park.ecommerce.promotion.DiscountType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Coupon extends BaseTimeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Embedded
    private Discount discount;

    // 선착순 or 기간 내 무제한 발급 쿠폰
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CouponType type;

    // 총 발급 수량, 선착순 쿠폰만 값이 있고 기본 쿠폰은 null - 남은 수량은 Redis가 관리
    @Column(updatable = false)
    private Integer quantity;

    // 발급 기간 ( 종료일은 포함되지 않는 조건 )
    @Column(nullable = false)
    private LocalDateTime issueStartAt;

    @Column(nullable = false)
    private LocalDateTime issueEndAt;

    // 사용 기한
    @Column(nullable = false)
    private LocalDateTime useStartAt;

    @Column(nullable = false)
    private LocalDateTime useEndAt;

    @Builder
    private Coupon(
            String name, DiscountType discountType, Integer discountValue, CouponType type, Integer quantity,
            LocalDateTime issueStartAt, LocalDateTime issueEndAt,
            LocalDateTime useStartAt, LocalDateTime useEndAt
    ) {
        validateQuantity(type, quantity);
        validatePeriod(issueStartAt, issueEndAt, useStartAt, useEndAt);

        this.name = name;
        this.discount = new Discount(discountType, discountValue);
        this.type = type;
        this.quantity = quantity;
        this.issueStartAt = issueStartAt;
        this.issueEndAt = issueEndAt;
        this.useStartAt = useStartAt;
        this.useEndAt = useEndAt;
    }

    // 수량 유효성 체크 - 생성자
    private static void validateQuantity(CouponType type, Integer quantity) {
        if (type == null) {
            throw new IllegalArgumentException("쿠폰 유형은 필수입니다.");
        }
        if (type == CouponType.NORMAL && quantity != null) {
            throw new IllegalArgumentException("기본 쿠폰은 발급 수량을 지정할 수 없습니다.");
        }
        if (type == CouponType.FIRST_COME && (quantity == null || quantity <= 0)) {
            throw new IllegalArgumentException("선착순 쿠폰의 발급 수량은 0보다 커야 합니다.");
        }
    }

    // 발급 기한 유효성 체크 - 생성자
    private static void validatePeriod(
            LocalDateTime issueStartAt, LocalDateTime issueEndAt,
            LocalDateTime useStartAt, LocalDateTime useEndAt
    ) {
        if (issueStartAt == null || issueEndAt == null || useStartAt == null || useEndAt == null) {
            throw new IllegalArgumentException("발급 기간과 사용 기간은 필수입니다.");
        }
        if (!issueStartAt.isBefore(issueEndAt)) {
            throw new IllegalArgumentException("발급 시작일시는 발급 종료일시보다 이전이어야 합니다.");
        }
        if (!useStartAt.isBefore(useEndAt)) {
            throw new IllegalArgumentException("사용 시작일시는 사용 종료일시보다 이전이어야 합니다.");
        }
        // 발급은 되는데 이미 사용할 수 없는 쿠폰이 생기지 않도록
        if (issueEndAt.isAfter(useEndAt)) {
            throw new IllegalArgumentException("발급 종료일시는 사용 종료일시보다 늦을 수 없습니다.");
        }
    }

    // 발급 기한 유효성 체크
    public boolean isInIssuePeriod(LocalDateTime now) {
        return !now.isBefore(issueStartAt) && now.isBefore(issueEndAt);
    }

    // 사용 기한 유효성 체크
    public boolean isInUsePeriod(LocalDateTime now) {
        return !now.isBefore(useStartAt) && now.isBefore(useEndAt);
    }

    // 유니크 제약은 같은 번호의 중복만 막으므로, 번호 없음과 범위 밖 번호는 여기서 걸러야 수량을 넘지 않음
    public boolean isIssuableSlot(Integer slotNo) {
        if (type == CouponType.NORMAL) {
            return slotNo == null;
        }
        return slotNo != null && slotNo >= 1 && slotNo <= quantity;
    }
}
