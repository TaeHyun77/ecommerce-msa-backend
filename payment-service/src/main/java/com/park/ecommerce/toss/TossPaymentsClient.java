package com.park.ecommerce.toss;

import com.park.ecommerce.toss.dto.TossConfirmRequest;
import com.park.ecommerce.toss.dto.TossErrorResponse;
import com.park.ecommerce.toss.dto.TossPaymentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.function.Supplier;

// 토스페이먼츠 결제 승인, 조회 API 호출
@Component
@RequiredArgsConstructor
public class TossPaymentsClient {
    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final RestClient tossPaymentsRestClient;

    // 결제 결과 조회
    public TossPaymentResponse find(String paymentKey) {
        return call(() -> tossPaymentsRestClient.get()
                .uri("/v1/payments/{paymentKey}", paymentKey)
                .retrieve()
                .body(TossPaymentResponse.class)
        );
    }

    // 결제 최종 승인 요청
    // 주문번호를 멱등키로 사용하여 재요청 시 중복 승인 없이 최초 요청 결과를 반환
    public TossPaymentResponse confirm(String paymentKey, String orderId, int amount) {
        return call(() -> tossPaymentsRestClient.post()
                .uri("/v1/payments/confirm")
                .header(IDEMPOTENCY_KEY_HEADER, orderId)
                .body(new TossConfirmRequest(paymentKey, orderId, amount))
                .retrieve()
                .body(TossPaymentResponse.class)
        );
    }

    // 토스 호출 실패를 공통 예외로 통일하는 래퍼 구현
    private TossPaymentResponse call(Supplier<TossPaymentResponse> request) {
        try {
            return request.get();
        } catch (RestClientResponseException e) {
            TossErrorResponse error = e.getResponseBodyAs(TossErrorResponse.class);
            if (error == null) {
                throw new TossPaymentsException(null, "토스페이먼츠 오류 응답 status=" + e.getStatusCode().value());
            }
            throw new TossPaymentsException(error.code(), error.message());
        } catch (ResourceAccessException e) {
            // 타임아웃, 연결 실패 - 요청이 토스에 도달해 처리됐는지 알 수 없다
            throw TossPaymentsException.noResponse("토스페이먼츠 호출 실패: " + e.getMessage());
        }
    }
}
