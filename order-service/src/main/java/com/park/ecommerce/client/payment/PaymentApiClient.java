package com.park.ecommerce.client.payment;

import com.park.ecommerce.exception.PaymentServiceUnavailableException;
import com.park.ecommerce.client.payment.dto.PaymentApproveRequest;
import com.park.ecommerce.client.payment.dto.PaymentApproveResponse;
import com.park.ecommerce.client.payment.dto.PaymentResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import java.util.Optional;

@RequiredArgsConstructor
@CircuitBreaker(name = "paymentService")
@Component
public class PaymentApiClient {
    private final RestClient paymentServiceRestClient;

    // 결과가 확정되면 DONE or FAILED 응답, 결과를 모르면 PaymentResultUnknownException을 던짐
    // 승인 요청은 멱등적이므로 중복 요청해도 안전
    // 서킷이 열리면 호출 없이 CallNotPermittedException - 사용자 요청의 첫 승인이면 주문을 실패 처리하고, 복구 중이면 결과 불명으로 남겨 다시 처리
    public PaymentApproveResponse approve(PaymentApproveRequest request) {
        try {
            return paymentServiceRestClient.post()
                    .uri("/internal/payments/approve")
                    .body(request)
                    .retrieve()
                    .body(PaymentApproveResponse.class);
        } catch (ResourceAccessException e) {
            throw new PaymentServiceUnavailableException("payment-service 호출 실패: " + e.getMessage());
        }
    }

    // 결제 정보 조회
    public Optional<PaymentResponse> findPayment(String orderNo) {
        try {
            ResponseEntity<PaymentResponse> response = paymentServiceRestClient.get()
                    .uri("/internal/payments/{orderNo}", orderNo)
                    .retrieve()
                    .onStatus(status -> status == HttpStatus.NOT_FOUND, (request, res) -> {
                    })
                    .toEntity(PaymentResponse.class);

            if (response.getStatusCode() == HttpStatus.NOT_FOUND) return Optional.empty();
            return Optional.ofNullable(response.getBody());
        } catch (ResourceAccessException e) {
            throw new PaymentServiceUnavailableException("payment-service 호출 실패: " + e.getMessage());
        }
    }
}
