package com.park.ecommerce.payment;

import com.park.ecommerce.exception.PaymentErrorCode;
import com.park.ecommerce.exception.PaymentException;
import com.park.ecommerce.payment.domain.Payment;
import com.park.ecommerce.payment.domain.PaymentRepository;
import com.park.ecommerce.payment.dto.PaymentApproveRequest;
import com.park.ecommerce.payment.dto.PaymentApproveResponse;
import com.park.ecommerce.payment.dto.PaymentResponse;
import com.park.ecommerce.toss.TossPaymentsClient;
import com.park.ecommerce.toss.TossPaymentsException;
import com.park.ecommerce.toss.dto.TossPaymentResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

// 토스페이먼츠 결제 승인 - 결과를 모르는 order-service가 같은 요청을 다시 보내도 안전하도록 멱등하게 동작함
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {
    private static final String NOT_FOUND_PAYMENT = "NOT_FOUND_PAYMENT"; // 토스 결제 조회 - 존재하지 않는 결제

    private final PaymentRepository paymentRepository;
    private final TossPaymentsClient tossPaymentsClient;

    // 결제 정보 조회
    public PaymentResponse getPayment(String orderNo) {
        Payment payment = paymentRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND));

        return PaymentResponse.from(payment);
    }

    // 결제 승인
    public PaymentApproveResponse approve(PaymentApproveRequest request) {
        Payment payment = paymentRepository.findByOrderNo(request.orderNo())
                .orElseGet(() -> paymentRepository.save(request.toEntity()));

        // 이미 결과가 확정된 건이라면 바로 응답 - DONE or FAIL
        if (payment.isCompleted()) return PaymentApproveResponse.from(payment);

        try {
            // 결제 승인 요청
            applyTossStatus(payment, tossPaymentsClient.confirm(payment.getPaymentKey(), payment.getOrderNo(), payment.getAmount()), null);
        } catch (TossPaymentsException e) { // 읽기/연결 타임아웃, 4xx 거절 (카드사 거절, 인증 시간 만료 ..), 5xx 거절 (내부 오류 ..)
            if (e.isNoResponse()) throw resultUnknown(payment, "승인 응답 없음: " + e.getMessage());
            // 오류 응답에도 처리 중(409), 이미 처리됨(400)처럼 실패가 아닌 코드가 섞여 있어 결제 상태로 확정
            resolveByInquiry(payment, e);
        }

        paymentRepository.save(payment);
        log.debug("[결제] 승인 결과 확정 orderNo={}, 상태={}", payment.getOrderNo(), payment.getStatus());
        return PaymentApproveResponse.from(payment);
    }

    // 승인 실패 시 토스에 결제 상태를 재조회하고, 이때도 결과를 확인할 수 없으면 UNKNOWN 반환
    private void resolveByInquiry(Payment payment, TossPaymentsException confirmError) {
        TossPaymentResponse found;
        try {
            found = tossPaymentsClient.find(payment.getPaymentKey()); // 결제 결과 조회
        } catch (TossPaymentsException e) {
            if (NOT_FOUND_PAYMENT.equals(e.getCode())) {
                payment.fail(NOT_FOUND_PAYMENT + " / " + describe(confirmError));
                return;
            }
            throw resultUnknown(payment, "승인 실패(" + confirmError.getMessage() + ") 후 결제 조회도 실패: " + e.getMessage());
        }

        applyTossStatus(payment, found, confirmError);
    }

    private void applyTossStatus(Payment payment, TossPaymentResponse tossPayment, TossPaymentsException confirmError) {
        switch (tossPayment.status()) {
            case "DONE" -> payment.approve(tossPayment.method(), inServerTimeZone(tossPayment.approvedAt()));
            case "ABORTED", "EXPIRED" -> payment.fail(failReason(tossPayment, confirmError)); // 승인 실패 or 만료
            default -> throw resultUnknown(payment, "결제 상태 " + tossPayment.status()); // 결과 모름
        }
    }

    // UTC로 변환된 시간을 서버 시간대 (+09:00)로 변환하여 저장 ( toLocalDateTime() 사용 시 9시간 차이 발생 )
    private static LocalDateTime inServerTimeZone(OffsetDateTime dateTime) {
        return LocalDateTime.ofInstant(dateTime.toInstant(), ZoneId.systemDefault());
    }

    private static String failReason(TossPaymentResponse tossPayment, TossPaymentsException confirmError) {
        return confirmError == null ? tossPayment.status() : tossPayment.status() + " / " + describe(confirmError);
    }

    private static String describe(TossPaymentsException error) {
        return error.getCode() == null ? error.getMessage() : error.getCode() + ": " + error.getMessage();
    }

    private static PaymentException resultUnknown(Payment payment, String reason) {
        log.warn("[결제] 승인 결과 불명 orderNo={}, 사유={}", payment.getOrderNo(), reason);
        return new PaymentException(PaymentErrorCode.PAYMENT_RESULT_UNKNOWN);
    }
}
