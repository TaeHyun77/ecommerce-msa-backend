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

    // 결과가 확정되면 DONE·FAILED 응답을 돌려주고, 결과를 모르면 PaymentResultUnknownException을 던짐
    // payment-service는 같은 요청을 다시 받아도 확정된 결과를 그대로 주므로 결과를 모를 때 다시 보내도 안전함
    // 서킷이 열리면 호출 없이 CallNotPermittedException - 호출하는 쪽에서 결과 불명과 같이 다뤄 복구 스케줄러가 다시 처리함
    public PaymentApproveResponse approve(PaymentApproveRequest request) {
        try {
            return paymentServiceRestClient.post()
                    .uri("/internal/payments/approve")
                    .body(request)
                    .retrieve()
                    .body(PaymentApproveResponse.class);
        } catch (ResourceAccessException e) {
            // 타임아웃, 연결 실패 - payment-service가 승인을 처리했는지 알 수 없음
            throw new PaymentServiceUnavailableException("payment-service 호출 실패: " + e.getMessage());
        }
    }

    // 결제 정보 조회 - 승인을 요청하기 전의 주문은 결제 기록이 없으므로 404를 빈 값으로 돌려줌
    public Optional<PaymentResponse> findPayment(String orderNo) {
        try {
            ResponseEntity<PaymentResponse> response = paymentServiceRestClient.get()
                    .uri("/internal/payments/{orderNo}", orderNo)
                    .retrieve()
                    .onStatus(status -> status == HttpStatus.NOT_FOUND, (request, res) -> {
                    })
                    .toEntity(PaymentResponse.class);

            if (response.getStatusCode() == HttpStatus.NOT_FOUND) {
                return Optional.empty();
            }
            return Optional.ofNullable(response.getBody());
        } catch (ResourceAccessException e) {
            throw new PaymentServiceUnavailableException("payment-service 호출 실패: " + e.getMessage());
        }
    }
}
