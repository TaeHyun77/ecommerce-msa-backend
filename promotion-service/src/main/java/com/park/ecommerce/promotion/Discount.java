package com.park.ecommerce.promotion;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 할인 유형과 할인값을 함께 다루는 값 객체
 * 할인값의 단위가 할인 유형에 따라 달라지므로 두 값을 분리하지 않음
 */
@Getter
@Embeddable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Discount {
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DiscountType discountType;

    @Column(nullable = false)
    private Integer discountValue; // discountType이 PERCENT면 %, AMOUNT면 원 단위

    public Discount(DiscountType discountType, Integer discountValue) {
        validate(discountType, discountValue);

        this.discountType = discountType;
        this.discountValue = discountValue;
    }

    private static void validate(DiscountType discountType, Integer discountValue) {
        if (discountType == null) {
            throw new IllegalArgumentException("할인 유형은 필수입니다.");
        }
        if (discountValue == null || discountValue <= 0) {
            throw new IllegalArgumentException("할인값은 0보다 커야 합니다.");
        }
        if (discountType == DiscountType.PERCENT && discountValue > 100) {
            throw new IllegalArgumentException("정률 할인은 100%를 초과할 수 없습니다.");
        }
    }

    public int calculateAmount(int price) {
        return switch (discountType) {
            // 1원 미만 내림 - (판매가 × 할인율)이 int 범위를 넘을 수 있어 long으로 계산
            case PERCENT -> (int) ((long) price * discountValue / 100);
            // 결제 금액이 음수가 되지 않도록 판매가까지만 할인
            case AMOUNT -> Math.min(discountValue, price);
        };
    }
}
