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

    // 주문번호로 결제 기록을 찾거나 새로 만든 뒤, 토스에 승인을 요청하고 그 결과(완료/실패)를 기록해 응답
    public PaymentApproveResponse approve(PaymentApproveRequest request) {
        // 같은 주문번호가 동시에 처음 들어오면 유니크 제약으로 한쪽이 실패하고, order-service가 결과 불명으로 보고 다시 요청함
        Payment payment = paymentRepository.findByOrderNo(request.orderNo())
                .orElseGet(() -> paymentRepository.save(request.toEntity()));

        if (!payment.isRequestedWith(request.paymentKey(), request.amount())) {
            throw new PaymentException(PaymentErrorCode.PAYMENT_REQUEST_MISMATCH);
        }

        // 이미 확정된 결과는 토스를 다시 호출하지 않고 그대로 응답
        if (payment.isCompleted()) return PaymentApproveResponse.from(payment);

        try {
            apply(payment, tossPaymentsClient.confirm(payment.getPaymentKey(), payment.getOrderNo(), payment.getAmount()), null);
        } catch (TossPaymentsException e) {
            // 실패 응답이어도 실제로는 승인됐을 수 있으므로 (타임아웃, 이미 처리된 결제 등) 오류 코드로 판단하지 않고 결제 상태를 조회해 결과를 확정하도록 함
            resolveByInquiry(payment, e);
        }

        paymentRepository.save(payment);
        log.info("[결제] 승인 결과 확정 orderNo={}, 상태={}", payment.getOrderNo(), payment.getStatus());
        return PaymentApproveResponse.from(payment);
    }

    // 결제 정보 조회
    public PaymentResponse getPayment(String orderNo) {
        Payment payment = paymentRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND));

        return PaymentResponse.from(payment);
    }

    // 승인 요청이 실패했을 때 토스에 결제 상태를 조회해 결과를 확정
    private void resolveByInquiry(Payment payment, TossPaymentsException confirmError) {
        TossPaymentResponse found;
        try {
            found = tossPaymentsClient.find(payment.getPaymentKey());
        } catch (TossPaymentsException e) {
            // 토스에 없는 결제는 앞으로도 승인될 수 없으므로 실패로 확정함 - 인증을 거치지 않은 결제 키가 들어온 경우
            // 결과 불명으로 남기면 order-service에서 확정된 재고 선점이 풀리지 않고 복구가 계속 반복됨
            if (NOT_FOUND_PAYMENT.equals(e.getCode())) {
                payment.fail(NOT_FOUND_PAYMENT + " / " + describe(confirmError));
                return;
            }
            throw resultUnknown(payment, "승인 실패(" + confirmError.getMessage() + ") 후 결제 조회도 실패: " + e.getMessage());
        }
        apply(payment, found, confirmError);
    }

    // 토스가 알려준 결제 상태를 보고 결제 기록의 결과를 정함
    private void apply(Payment payment, TossPaymentResponse tossPayment, TossPaymentsException confirmError) {
        switch (tossPayment.status()) {
            case "DONE" -> payment.approve(tossPayment.method(), inServerTimeZone(tossPayment.approvedAt()));
            case "ABORTED", "EXPIRED" -> payment.fail(failReason(tossPayment, confirmError));
            // IN_PROGRESS 등 - 승인 요청이 도달하지 않았거나 처리 중
            // 인증 유효 시간 안이면 다시 승인할 수 있고, 지나면 토스가 EXPIRED로 바꾸므로 다음 요청에서 확정됨
            default -> throw resultUnknown(payment, "결제 상태 " + tossPayment.status());
        }
    }

    // 토스는 +09:00으로 응답하지만 Jackson이 OffsetDateTime을 읽으며 UTC로 바꾸므로, 오프셋을 떼지 않고 같은 순간을 서버 시간대 시각으로 바꾼다
    // (toLocalDateTime()을 쓰면 UTC 벽시계 시간이 저장되어 9시간 어긋난다)
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
