package com.park.ecommerce.promotion;

import com.park.ecommerce.common.BaseTimeEntity;
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
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Promotion extends BaseTimeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name; // 프로모션 이름

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PromotionTargetType targetType; // 프로모션 타켓

    private Long targetId; // targetType이 ALL이면 null, CATEGORY/PRODUCT면 해당 식별자와 매칭

    @Embedded
    private Discount discount;

    @Column(nullable = false)
    private LocalDateTime startAt;

    @Column(nullable = false)
    private LocalDateTime endAt; // 이 시각은 기간에 포함하지 않음 — 이어지는 프로모션 사이에 빈틈이 생기지 않도록

    // 할인 금액이 같은 프로모션이 겹칠 때 사용하는 우선순위 (값이 클수록 우선)
    @Column(nullable = false)
    private Integer priority;

    @Builder
    private Promotion(
            String name, PromotionTargetType targetType, Long targetId,
            DiscountType discountType, Integer discountValue,
            LocalDateTime startAt, LocalDateTime endAt, Integer priority
    ) {
        validateTarget(targetType, targetId);
        validatePeriod(startAt, endAt);

        this.name = name;
        this.targetType = targetType;
        this.targetId = targetId;
        this.discount = new Discount(discountType, discountValue);
        this.startAt = startAt;
        this.endAt = endAt;
        this.priority = priority != null ? priority : 0;
    }

    private static void validateTarget(PromotionTargetType targetType, Long targetId) {
        if (targetType == null) {
            throw new IllegalArgumentException("프로모션 대상 유형은 필수입니다.");
        }
        if (targetType == PromotionTargetType.ALL && targetId != null) {
            throw new IllegalArgumentException("전체 할인은 targetId를 지정할 수 없습니다.");
        }
        if (targetType != PromotionTargetType.ALL && targetId == null) {
            throw new IllegalArgumentException("전체 할인이 아닌 경우 targetId는 필수입니다.");
        }
    }

    private static void validatePeriod(LocalDateTime startAt, LocalDateTime endAt) {
        if (startAt == null || endAt == null) {
            throw new IllegalArgumentException("프로모션 시작일시와 종료일시는 필수입니다.");
        }
        if (!startAt.isBefore(endAt)) {
            throw new IllegalArgumentException("시작일시는 종료일시보다 이전이어야 합니다.");
        }
    }

    public boolean isActivePromotion(LocalDateTime now) {
        return !now.isBefore(startAt) && now.isBefore(endAt);
    }

    public int calculateDiscountAmount(int price) {
        return discount.calculateAmount(price);
    }

    /**
     * 겹치는 프로모션 중 실제로 적용할 1개를 선택
     * 할인 금액 → priority → id(나중에 등록된 것) 순
     * candidates는 기간과 대상 조건을 이미 통과한, 저장된 프로모션이어야 함
     */
    public static Optional<Promotion> selectBestPromotion(List<Promotion> candidates, int price) {
        return candidates.stream()
                .max(Comparator.comparingInt((Promotion promotion) -> promotion.calculateDiscountAmount(price))
                        .thenComparingInt(Promotion::getPriority)
                        .thenComparingLong(Promotion::getId));
    }
}
