package com.park.ecommerce.order.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class OrderTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 22, 12, 0);

    @Test
    @DisplayName("품목을 담으면 총액은 품목별 단가 × 수량의 합이다")
    void sumsLineAmounts() {
        Order order = order();

        order.addLine(1L, "유기농 우유 900ml", 3_900, 2);
        order.addLine(2L, "국산콩 두부 300g", 2_500, 1);

        assertThat(order.getTotalAmount()).isEqualTo(10_300);
    }

    @Test
    @DisplayName("쿠폰이 없으면 할인액은 0원이고 결제 금액은 상품 합계와 같다")
    void paysTotalWithoutCoupon() {
        Order order = order();
        order.addLine(1L, "유기농 우유 900ml", 3_900, 2);

        assertThat(order.hasCoupon()).isFalse();
        assertThat(order.getDiscountAmount()).isZero();
        assertThat(order.getPaymentAmount()).isEqualTo(7_800);
    }

    @Test
    @DisplayName("쿠폰을 적용하면 상품 합계는 그대로 두고 결제 금액만 할인액만큼 줄어든다")
    void appliesCouponDiscount() {
        Order order = order();
        order.addLine(1L, "유기농 우유 900ml", 3_900, 2);

        order.applyCoupon(10L, 2_000);

        assertThat(order.hasCoupon()).isTrue();
        assertThat(order.getTotalAmount()).isEqualTo(7_800);
        assertThat(order.getPaymentAmount()).isEqualTo(5_800);
    }

    @Test
    @DisplayName("상품 합계 전액을 할인하면 결제 금액은 0원이다")
    void allowsFullDiscount() {
        Order order = order();
        order.addLine(1L, "유기농 우유 900ml", 3_900, 2);

        order.applyCoupon(10L, 7_800);

        assertThat(order.getPaymentAmount()).isZero();
    }

    @Test
    @DisplayName("할인액이 상품 합계보다 크거나 음수면 예외가 발생한다 - 결제 금액이 음수가 되지 않도록")
    void rejectsDiscountOutOfRange() {
        Order order = order();
        order.addLine(1L, "유기농 우유 900ml", 3_900, 2);

        assertThatIllegalArgumentException().isThrownBy(() -> order.applyCoupon(10L, 7_801));
        assertThatIllegalArgumentException().isThrownBy(() -> order.applyCoupon(10L, -1));
    }

    @Test
    @DisplayName("새 주문은 결제 대기 상태로 시작한다")
    void startsPaymentWaiting() {
        assertThat(order().getStatus()).isEqualTo(OrderStatus.PAYMENT_WAITING);
    }

    @Test
    @DisplayName("주문번호는 토스페이먼츠 orderId 규격(영문·숫자·-·_ 6~64자)을 만족하고 주문마다 다르다")
    void generatesTossCompatibleOrderNo() {
        String orderNo = order().getOrderNo();

        assertThat(orderNo).matches("[A-Za-z0-9_-]{6,64}");
        assertThat(order().getOrderNo()).isNotEqualTo(orderNo);
    }

    @Test
    @DisplayName("품목이 하나면 주문명은 상품명이다")
    void namesSingleLineOrder() {
        Order order = order();
        order.addLine(1L, "유기농 우유 900ml", 3_900, 2);

        assertThat(order.getOrderName()).isEqualTo("유기농 우유 900ml");
    }

    @Test
    @DisplayName("품목이 여러 개면 주문명은 '첫 상품명 외 N건'이다")
    void namesMultiLineOrder() {
        Order order = order();
        order.addLine(1L, "유기농 우유 900ml", 3_900, 2);
        order.addLine(2L, "국산콩 두부 300g", 2_500, 1);
        order.addLine(3L, "무항생제 계란 10구", 5_900, 1);

        assertThat(order.getOrderName()).isEqualTo("유기농 우유 900ml 외 2건");
    }

    @Test
    @DisplayName("회원이 없으면 예외가 발생한다")
    void rejectsNullMember() {
        assertThatIllegalArgumentException().isThrownBy(() -> Order.builder()
                .orderedAt(NOW)
                .expiresAt(NOW.plusMinutes(10))
                .build());
    }

    private static Order order() {
        return Order.builder()
                .memberId(1L)
                .orderedAt(NOW)
                .expiresAt(NOW.plusMinutes(10))
                .build();
    }
}
