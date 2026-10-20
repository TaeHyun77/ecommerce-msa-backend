package com.park.ecommerce.client.product;

import com.park.ecommerce.exception.ProductServiceUnavailableException;
import com.park.ecommerce.search.dto.ProductSummaryResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import java.util.Collection;
import java.util.List;

@RequiredArgsConstructor
@CircuitBreaker(name = "productService")
@Component
public class ProductApiClient {
    private final RestClient productServiceRestClient;

    // 등록되지 않은 상품 식별자는 응답에서 빠짐 - 서킷이 열려 있으면 호출 없이 CallNotPermittedException이 발생하여 503으로 응답
    public List<ProductSummaryResponse> findProducts(Collection<Long> productIds) {
        try {
            return productServiceRestClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/internal/products")
                            .queryParam("ids", productIds)
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<ProductSummaryResponse>>() {});
        } catch (ResourceAccessException e) {
            // 타임아웃, 커넥션 거부 등 HTTP 상태 코드 이전 단계의 통신 실패 - defaultStatusHandler로는 잡히지 않음
            throw new ProductServiceUnavailableException();
        }
    }
}
