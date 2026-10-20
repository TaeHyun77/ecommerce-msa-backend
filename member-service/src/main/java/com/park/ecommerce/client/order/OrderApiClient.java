package com.park.ecommerce.client.order;

import com.park.ecommerce.exception.OrderServiceUnavailableException;
import com.park.ecommerce.client.order.dto.MemberOrderResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.List;

@RequiredArgsConstructor
@CircuitBreaker(name = "orderService")
@Component
public class OrderApiClient {
    private final RestClient orderServiceRestClient;

    // Circuit이 OPEN이면 실제 호출 없이 CallNotPermittedException 발생
    // → GlobalExceptionHandler에서 HTTP 503으로 변환
    public List<MemberOrderResponse> findOrdersByMemberId(Long memberId) {
        try {
            return orderServiceRestClient.get()
                    .uri("/internal/orders?memberId={memberId}", memberId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<MemberOrderResponse>>() {});
        } catch (ResourceAccessException e) {
            // 타임아웃, 커넥션 거부 등 HTTP 상태 코드 이전 단계의 통신 실패 - defaultStatusHandler로는 잡히지 않음
            throw new OrderServiceUnavailableException();
        }
    }
}
