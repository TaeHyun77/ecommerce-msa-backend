package com.park.ecommerce.promotion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class DiscountTest {

    @Nested
    @DisplayName("생성 검증")
    class Create {

        @ParameterizedTest
        @CsvSource({"PERCENT, 0", "PERCENT, 101", "AMOUNT, 0", "AMOUNT, -1000"})
        @DisplayName("할인값이 유형별 허용 범위를 벗어나면 예외가 발생한다")
        void rejectsDiscountValueOutOfRange(DiscountType discountType, int discountValue) {
            assertThatIllegalArgumentException().isThrownBy(() -> new Discount(discountType, discountValue));
        }

        @Test
        @DisplayName("정률 할인 100%는 허용한다")
        void allowsFullPercentDiscount() {
            assertThatCode(() -> new Discount(DiscountType.PERCENT, 100)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("할인 유형이 없으면 예외가 발생한다")
        void rejectsNullDiscountType() {
            assertThatIllegalArgumentException().isThrownBy(() -> new Discount(null, 10));
        }

        @Test
        @DisplayName("할인값이 없으면 예외가 발생한다")
        void rejectsNullDiscountValue() {
            assertThatIllegalArgumentException().isThrownBy(() -> new Discount(DiscountType.PERCENT, null));
        }
    }

    @Nested
    @DisplayName("할인 금액 계산")
    class CalculateAmount {

        @Test
        @DisplayName("정률 할인은 1원 미만을 내림한다")
        void floorsPercentDiscount() {
            // 10,990원 × 15% = 1,648.5원
            assertThat(new Discount(DiscountType.PERCENT, 15).calculateAmount(10_990)).isEqualTo(1_648);
        }

        @Test
        @DisplayName("정률 할인은 판매가와 할인율의 곱이 int 범위를 넘어도 정확히 계산한다")
        void calculatesPercentDiscountWithoutOverflow() {
            // 30,000,000 × 80 = 2,400,000,000 - int 최댓값 초과
            assertThat(new Discount(DiscountType.PERCENT, 80).calculateAmount(30_000_000)).isEqualTo(24_000_000);
        }

        @Test
        @DisplayName("정액 할인은 할인값만큼 할인한다")
        void appliesAmountDiscount() {
            assertThat(new Discount(DiscountType.AMOUNT, 3_000).calculateAmount(10_000)).isEqualTo(3_000);
        }

        @Test
        @DisplayName("정액 할인값이 판매가보다 크면 판매가까지만 할인한다")
        void capsAmountDiscountAtPrice() {
            assertThat(new Discount(DiscountType.AMOUNT, 5_000).calculateAmount(3_000)).isEqualTo(3_000);
        }
    }
}
