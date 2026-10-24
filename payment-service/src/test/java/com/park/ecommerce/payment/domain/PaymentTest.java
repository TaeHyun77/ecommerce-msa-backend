package com.park.ecommerce.payment.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class PaymentTest {
    private static final LocalDateTime APPROVED_AT = LocalDateTime.of(2026, 9, 22, 12, 0, 5);

    @Test
    @DisplayName("새 결제는 승인 요청 상태로 시작하고 아직 완료되지 않았다")
    void startsRequested() {
        Payment payment = payment();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REQUESTED);
        assertThat(payment.isCompleted()).isFalse();
    }

    @Test
    @DisplayName("승인되면 결제 수단과 승인 시각을 기록하고 완료된다")
    void approves() {
        Payment payment = payment();

        payment.approve("카드", APPROVED_AT);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DONE);
        assertThat(payment.getMethod()).isEqualTo("카드");
        assertThat(payment.getApprovedAt()).isEqualTo(APPROVED_AT);
        assertThat(payment.isCompleted()).isTrue();
    }

    @Test
    @DisplayName("승인에 실패하면 사유를 기록하고 완료된다")
    void fails() {
        Payment payment = payment();

        payment.fail("REJECT_CARD_COMPANY: 카드사에서 거절했습니다.");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailReason()).isEqualTo("REJECT_CARD_COMPANY: 카드사에서 거절했습니다.");
        assertThat(payment.isCompleted()).isTrue();
    }

    @Test
    @DisplayName("결과가 확정된 결제는 다른 결과로 바꿀 수 없다")
    void rejectsChangingCompletedResult() {
        Payment payment = payment();
        payment.approve("카드", APPROVED_AT);

        assertThatIllegalStateException().isThrownBy(() -> payment.fail("뒤늦은 실패"));
    }

    private static Payment payment() {
        return Payment.builder()
                .orderNo("ORDER-0001")
                .paymentKey("tgen_pay_0001")
                .amount(7_800)
                .build();
    }
}
