package com.park.ecommerce.promotion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class PromotionTest {

    private static final LocalDateTime START_AT = LocalDateTime.of(2026, 9, 1, 0, 0);
    private static final LocalDateTime END_AT = LocalDateTime.of(2026, 10, 1, 0, 0); // 종료 시각 미포함 — 9월 한 달 프로모션

    @Nested
    @DisplayName("생성 검증")
    class Create {

        @Test
        @DisplayName("전체 할인에 targetId를 지정하면 예외가 발생한다")
        void rejectsTargetIdForAllTarget() {
            assertThatIllegalArgumentException().isThrownBy(() ->
                    validPromotion().targetType(PromotionTargetType.ALL).targetId(1L).build());
        }

        @Test
        @DisplayName("대상 유형이 없으면 대상 유형 누락 예외가 발생한다")
        void rejectsNullTargetType() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> validPromotion().targetType(null).build())
                    .withMessageContaining("대상 유형");
        }

        @Test
        @DisplayName("시작일시가 없으면 예외가 발생한다")
        void rejectsNullStartAt() {
            assertThatIllegalArgumentException().isThrownBy(() -> validPromotion().startAt(null).build());
        }

        @Test
        @DisplayName("종료일시가 없으면 예외가 발생한다")
        void rejectsNullEndAt() {
            assertThatIllegalArgumentException().isThrownBy(() -> validPromotion().endAt(null).build());
        }

        @Test
        @DisplayName("시작일시와 종료일시가 같으면 예외가 발생한다")
        void rejectsEmptyPeriod() {
            assertThatIllegalArgumentException().isThrownBy(() -> validPromotion().startAt(START_AT).endAt(START_AT).build());
        }
    }

    @Nested
    @DisplayName("적용 기간")
    class ActivePeriod {

        @Test
        @DisplayName("시작 시각은 기간에 포함한다")
        void includesStartAt() {
            Promotion promotion = validPromotion().build();

            assertThat(promotion.isActivePromotion(START_AT.minusNanos(1))).isFalse();
            assertThat(promotion.isActivePromotion(START_AT)).isTrue();
        }

        @Test
        @DisplayName("종료 시각은 기간에 포함하지 않는다")
        void excludesEndAt() {
            Promotion promotion = validPromotion().build();

            assertThat(promotion.isActivePromotion(END_AT.minusNanos(1))).isTrue();
            assertThat(promotion.isActivePromotion(END_AT)).isFalse();
        }
    }

    @Nested
    @DisplayName("적용 프로모션 선택")
    class SelectBest {

        @Test
        @DisplayName("할인 금액이 가장 큰 프로모션을 우선한다")
        void prefersLargestDiscountAmount() {
            // 50,000원 기준 정률 10% = 5,000원, 정액 = 3,000원
            Promotion percent = promotion(1L, DiscountType.PERCENT, 10, 0);
            Promotion amount = promotion(2L, DiscountType.AMOUNT, 3_000, 9);

            assertThat(Promotion.selectBestPromotion(List.of(amount, percent), 50_000)).containsSame(percent);
        }

        @Test
        @DisplayName("할인 금액이 같으면 priority가 높은 프로모션을 우선한다")
        void prefersHigherPriorityOnSameDiscountAmount() {
            // 10,000원 기준 정률 10% = 정액 1,000원
            Promotion lowPriority = promotion(2L, DiscountType.PERCENT, 10, 1);
            Promotion highPriority = promotion(1L, DiscountType.AMOUNT, 1_000, 5);

            assertThat(Promotion.selectBestPromotion(List.of(lowPriority, highPriority), 10_000)).containsSame(highPriority);
        }

        @Test
        @DisplayName("할인 금액과 priority가 같으면 id가 큰(나중에 등록된) 프로모션을 우선한다")
        void prefersLatestOnTie() {
            Promotion older = promotion(1L, DiscountType.AMOUNT, 1_000, 0);
            Promotion newer = promotion(2L, DiscountType.AMOUNT, 1_000, 0);

            assertThat(Promotion.selectBestPromotion(List.of(older, newer), 10_000)).containsSame(newer);
        }
    }

    private static Promotion.PromotionBuilder validPromotion() {
        return Promotion.builder()
                .name("가을 정기 세일")
                .targetType(PromotionTargetType.ALL)
                .discountType(DiscountType.PERCENT)
                .discountValue(10)
                .startAt(START_AT)
                .endAt(END_AT);
    }

    private static Promotion promotion(Long id, DiscountType discountType, int discountValue, int priority) {
        Promotion promotion = validPromotion()
                .discountType(discountType)
                .discountValue(discountValue)
                .priority(priority)
                .build();
        ReflectionTestUtils.setField(promotion, "id", id); // id는 저장 시점에 할당되므로 테스트에서 직접 주입
        return promotion;
    }
}
