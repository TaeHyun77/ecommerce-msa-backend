package com.park.ecommerce.payment;

import com.park.ecommerce.exception.PaymentErrorCode;
import com.park.ecommerce.exception.PaymentException;
import com.park.ecommerce.payment.domain.Payment;
import com.park.ecommerce.payment.domain.PaymentRepository;
import com.park.ecommerce.payment.domain.PaymentStatus;
import com.park.ecommerce.payment.dto.PaymentApproveRequest;
import com.park.ecommerce.payment.dto.PaymentApproveResponse;
import com.park.ecommerce.payment.dto.PaymentResponse;
import com.park.ecommerce.toss.TossPaymentsClient;
import com.park.ecommerce.toss.TossPaymentsException;
import com.park.ecommerce.toss.dto.TossPaymentResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// 승인 기록과 재요청 시의 멱등 처리는 실제 DB로 확인하고, 외부 서비스인 토스페이먼츠만 대체한다
@Testcontainers
@SpringBootTest(properties = "toss.payments.secret-key=test_sk_dummy")
class PaymentServiceTest {
    private static final String PAYMENT_KEY = "tgen_pay_0001";
    private static final int AMOUNT = 7_800;
    private static final OffsetDateTime APPROVED_AT = OffsetDateTime.of(2026, 9, 22, 12, 0, 5, 0, ZoneOffset.ofHours(9));

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.0");

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    @MockitoBean
    private TossPaymentsClient tossPaymentsClient;

    @Test
    @DisplayName("토스가 승인하면 결제 수단·승인 시각과 함께 완료로 기록하고 완료를 응답한다")
    void recordsApprovedPayment() {
        String orderNo = newOrderNo();
        given(tossPaymentsClient.confirm(PAYMENT_KEY, orderNo, AMOUNT)).willReturn(tossPayment(orderNo, "DONE"));

        PaymentApproveResponse response = paymentService.approve(request(orderNo));

        assertThat(response.status()).isEqualTo(PaymentStatus.DONE);
        Payment payment = paymentOf(orderNo);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DONE);
        assertThat(payment.getMethod()).isEqualTo("카드");
        assertThat(payment.getApprovedAt()).isEqualTo(inServerTimeZone(APPROVED_AT));
    }

    @Test
    @DisplayName("승인 시각은 응답의 오프셋과 관계없이 같은 순간을 서버 시간대 시각으로 기록한다 - 역직렬화에서 UTC로 바뀌어 와도")
    void recordsApprovedAtInServerTimeZone() {
        String orderNo = newOrderNo();
        // 한국 시각 14:38:18(+09:00)과 같은 순간 - Jackson은 OffsetDateTime을 읽을 때 기본으로 UTC로 바꾼다
        OffsetDateTime approvedAtInUtc = OffsetDateTime.of(2026, 9, 22, 5, 38, 18, 0, ZoneOffset.UTC);
        given(tossPaymentsClient.confirm(PAYMENT_KEY, orderNo, AMOUNT))
                .willReturn(new TossPaymentResponse(PAYMENT_KEY, orderNo, "DONE", "간편결제", AMOUNT, approvedAtInUtc));

        paymentService.approve(request(orderNo));

        assertThat(paymentOf(orderNo).getApprovedAt()).isEqualTo(inServerTimeZone(approvedAtInUtc));
    }

    @Test
    @DisplayName("승인이 거절되면 오류 코드로 판단하지 않고 결제를 조회해, 실패가 확정되면 실패로 기록한다")
    void recordsFailureConfirmedByInquiry() {
        String orderNo = newOrderNo();
        given(tossPaymentsClient.confirm(PAYMENT_KEY, orderNo, AMOUNT))
                .willThrow(new TossPaymentsException("REJECT_CARD_COMPANY", "카드사에서 거절했습니다."));
        given(tossPaymentsClient.find(PAYMENT_KEY)).willReturn(tossPayment(orderNo, "ABORTED"));

        PaymentApproveResponse response = paymentService.approve(request(orderNo));

        assertThat(response.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(response.failReason()).isEqualTo("ABORTED / REJECT_CARD_COMPANY: 카드사에서 거절했습니다.");
        assertThat(paymentOf(orderNo).getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    @DisplayName("승인 응답을 받지 못했어도 조회 결과 승인됐으면 완료로 기록한다 - 타임아웃 뒤 실제로는 승인된 경우")
    void recordsApprovalFoundByInquiry() {
        String orderNo = newOrderNo();
        given(tossPaymentsClient.confirm(PAYMENT_KEY, orderNo, AMOUNT))
                .willThrow(new TossPaymentsException(null, "토스페이먼츠 호출 실패: Read timed out"));
        given(tossPaymentsClient.find(PAYMENT_KEY)).willReturn(tossPayment(orderNo, "DONE"));

        PaymentApproveResponse response = paymentService.approve(request(orderNo));

        assertThat(response.status()).isEqualTo(PaymentStatus.DONE);
        assertThat(paymentOf(orderNo).getStatus()).isEqualTo(PaymentStatus.DONE);
    }

    @Test
    @DisplayName("조회 결과 아직 승인 전이면 결과 불명으로 알리고 승인 요청 상태로 남긴다")
    void leavesRequestedWhenStillInProgress() {
        String orderNo = newOrderNo();
        given(tossPaymentsClient.confirm(PAYMENT_KEY, orderNo, AMOUNT))
                .willThrow(new TossPaymentsException("IDEMPOTENT_REQUEST_PROCESSING", "이전 멱등 요청이 처리 중입니다."));
        given(tossPaymentsClient.find(PAYMENT_KEY)).willReturn(tossPayment(orderNo, "IN_PROGRESS"));

        assertResultUnknown(orderNo);
    }

    @Test
    @DisplayName("결제 조회마저 실패하면 결과 불명으로 알리고 승인 요청 상태로 남긴다")
    void leavesRequestedWhenInquiryFails() {
        String orderNo = newOrderNo();
        given(tossPaymentsClient.confirm(PAYMENT_KEY, orderNo, AMOUNT))
                .willThrow(new TossPaymentsException(null, "토스페이먼츠 호출 실패: Read timed out"));
        given(tossPaymentsClient.find(PAYMENT_KEY))
                .willThrow(new TossPaymentsException(null, "토스페이먼츠 호출 실패: Connection refused"));

        assertResultUnknown(orderNo);
    }

    @Test
    @DisplayName("결제 조회 결과 토스에 없는 결제면 실패로 확정한다 - 인증을 거치지 않은 결제 키로 재고를 계속 묶지 못하도록")
    void failsWhenPaymentDoesNotExistInToss() {
        String orderNo = newOrderNo();
        given(tossPaymentsClient.confirm(PAYMENT_KEY, orderNo, AMOUNT))
                .willThrow(new TossPaymentsException("NOT_FOUND_PAYMENT", "존재하지 않는 결제 정보 입니다."));
        given(tossPaymentsClient.find(PAYMENT_KEY))
                .willThrow(new TossPaymentsException("NOT_FOUND_PAYMENT", "존재하지 않는 결제 정보 입니다."));

        PaymentApproveResponse response = paymentService.approve(request(orderNo));

        assertThat(response.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(paymentOf(orderNo).getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    @DisplayName("결과 불명으로 남은 주문을 다시 요청하면 승인을 다시 시도해 결과를 확정한다")
    void retriesRequestedPayment() {
        String orderNo = newOrderNo();
        given(tossPaymentsClient.confirm(PAYMENT_KEY, orderNo, AMOUNT))
                .willThrow(new TossPaymentsException(null, "토스페이먼츠 호출 실패: Read timed out"))
                .willReturn(tossPayment(orderNo, "DONE"));
        given(tossPaymentsClient.find(PAYMENT_KEY)).willReturn(tossPayment(orderNo, "IN_PROGRESS"));
        assertResultUnknown(orderNo);

        PaymentApproveResponse response = paymentService.approve(request(orderNo));

        assertThat(response.status()).isEqualTo(PaymentStatus.DONE);
        assertThat(paymentOf(orderNo).getStatus()).isEqualTo(PaymentStatus.DONE);
    }

    @Test
    @DisplayName("이미 승인이 확정된 주문을 다시 요청하면 토스를 다시 호출하지 않고 같은 결과를 응답한다")
    void returnsCompletedResultWithoutCallingToss() {
        String orderNo = newOrderNo();
        paymentRepository.save(completed(orderNo));

        PaymentApproveResponse response = paymentService.approve(request(orderNo));

        assertThat(response.status()).isEqualTo(PaymentStatus.DONE);
        verify(tossPaymentsClient, never()).confirm(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("결제 정보를 조회하면 상태·금액·결제 수단·승인 시각을 돌려준다")
    void findsPayment() {
        String orderNo = newOrderNo();
        paymentRepository.save(completed(orderNo));

        PaymentResponse response = paymentService.getPayment(orderNo);

        assertThat(response.orderNo()).isEqualTo(orderNo);
        assertThat(response.status()).isEqualTo(PaymentStatus.DONE);
        assertThat(response.amount()).isEqualTo(AMOUNT);
        assertThat(response.method()).isEqualTo("카드");
        assertThat(response.approvedAt()).isEqualTo(APPROVED_AT.toLocalDateTime());
    }

    @Test
    @DisplayName("결제 기록이 없는 주문의 결제 정보를 조회하면 404 예외를 던진다")
    void throwsNotFoundWhenNoPayment() {
        assertThatThrownBy(() -> paymentService.getPayment(newOrderNo()))
                .isInstanceOf(PaymentException.class)
                .extracting("errorCode")
                .isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND);
    }

    private void assertResultUnknown(String orderNo) {
        assertThatThrownBy(() -> paymentService.approve(request(orderNo)))
                .isInstanceOf(PaymentException.class)
                .extracting("errorCode")
                .isEqualTo(PaymentErrorCode.PAYMENT_RESULT_UNKNOWN);

        assertThat(paymentOf(orderNo).getStatus()).isEqualTo(PaymentStatus.REQUESTED);
    }

    private static LocalDateTime inServerTimeZone(OffsetDateTime dateTime) {
        return LocalDateTime.ofInstant(dateTime.toInstant(), ZoneId.systemDefault());
    }

    private Payment paymentOf(String orderNo) {
        return paymentRepository.findByOrderNo(orderNo).orElseThrow();
    }

    private static Payment completed(String orderNo) {
        Payment payment = request(orderNo).toEntity();
        payment.approve("카드", APPROVED_AT.toLocalDateTime());
        return payment;
    }

    private static PaymentApproveRequest request(String orderNo) {
        return new PaymentApproveRequest(orderNo, PAYMENT_KEY, AMOUNT);
    }

    private static TossPaymentResponse tossPayment(String orderNo, String status) {
        OffsetDateTime approvedAt = "DONE".equals(status) ? APPROVED_AT : null;
        String method = "DONE".equals(status) ? "카드" : null;
        return new TossPaymentResponse(PAYMENT_KEY, orderNo, status, method, AMOUNT, approvedAt);
    }

    private static String newOrderNo() {
        return UUID.randomUUID().toString();
    }
}
