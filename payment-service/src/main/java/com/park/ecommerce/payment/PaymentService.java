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
// 토스 호출이 최대 60초 걸릴 수 있어 DB 트랜잭션 없이 동작하도록 함
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {
    private static final String NOT_FOUND_PAYMENT = "NOT_FOUND_PAYMENT"; // 토스 결제 조회 - 존재하지 않는 결제

    private final PaymentRepository paymentRepository;
    private final TossPaymentsClient tossPaymentsClient;

    // 결제 승인
    public PaymentApproveResponse approve(PaymentApproveRequest request) {
        // 기존 결제 정보가 있으면 재사용하고, 없으면 저장 (orderNo 유니크 제약조건으로 중복 생성 방지)
        Payment payment = paymentRepository.findByOrderNo(request.orderNo())
                .orElseGet(() -> paymentRepository.save(request.toEntity()));

        // 이미 결과가 확정된 건이라면 바로 응답 - DONE or FAIL
        if (payment.isCompleted()) return PaymentApproveResponse.from(payment);

        try {
            applyTossStatus(payment, tossPaymentsClient.confirm(payment.getPaymentKey(), payment.getOrderNo(), payment.getAmount()), null);
        } catch (TossPaymentsException e) {
            // 타임아웃 등으로 실패 응답을 받아도 실제 승인이 완료됐을 수 있으므로, 오류 코드 대신 결제 상태를 재조회하여 최종 결과를 확인
            resolveByInquiry(payment, e);
        }

        paymentRepository.save(payment);
        log.debug("[결제] 승인 결과 확정 orderNo={}, 상태={}", payment.getOrderNo(), payment.getStatus());
        return PaymentApproveResponse.from(payment);
    }

    // 결제 정보 조회
    public PaymentResponse getPayment(String orderNo) {
        Payment payment = paymentRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND));

        return PaymentResponse.from(payment);
    }

    // 승인 실패 시 토스에 결제 상태를 재조회하고, 결과를 확인할 수 없으면 UNKNOWN 반환
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

    // 확정되지 않은 상태(IN_PROGRESS 등)는 기록하지 않고 결과 불명 예외를 던짐
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
