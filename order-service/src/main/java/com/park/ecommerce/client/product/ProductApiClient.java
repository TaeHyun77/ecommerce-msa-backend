package com.park.ecommerce.client.product;

import com.park.ecommerce.exception.OrderErrorCode;
import com.park.ecommerce.exception.OrderException;
import com.park.ecommerce.exception.ProductServiceUnavailableException;
import com.park.ecommerce.client.product.dto.ProductSummaryResponse;
import com.park.ecommerce.client.product.dto.StockReservationRequest;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
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

    // 상품 정보 조회
    public List<ProductSummaryResponse> findProducts(Collection<Long> productIds) {
        try {
            return productServiceRestClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/internal/products")
                            .queryParam("ids", productIds)
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
        } catch (ResourceAccessException e) {
            throw new ProductServiceUnavailableException();
        }
    }

    // 한 상품이라도 재고가 부족하면 product-service가 전체를 롤백하고 409로 응답
    public void reserve(StockReservationRequest request) {
        try {
            productServiceRestClient.post()
                    .uri("/internal/stock-reservations")
                    .body(request)
                    .retrieve()
                    // 새 주문번호로만 호출하므로 409는 재고 부족을 뜻한다 - 호출별 처리가 공통 4xx 처리(계약 불일치)보다 먼저 적용됨
                    .onStatus(status -> status.value() == HttpStatus.CONFLICT.value(), (req, res) -> {
                        throw new OrderException(OrderErrorCode.INSUFFICIENT_STOCK);
                    })
                    .toBodilessEntity();
        } catch (ResourceAccessException e) {
            throw new ProductServiceUnavailableException();
        }
    }

    // PG 승인 직전에 호출 - 이미 확정된 선점은 product-service가 성공으로 응답하므로 복구 중 같은 확정을 다시 요청해도 true
    public boolean confirmReservation(String orderNo) {
        try {
            return productServiceRestClient.post()
                    .uri("/internal/stock-reservations/{orderNo}/confirm", orderNo)
                    .retrieve()
                    // 409는 선점이 만료·해제되어 확정할 수 없다는 뜻 - 예외 대신 false로 돌려 product-service 장애와 구분
                    .onStatus(status -> status.value() == HttpStatus.CONFLICT.value(), (req, res) -> {
                    })
                    .toBodilessEntity()
                    .getStatusCode()
                    .is2xxSuccessful();
        } catch (ResourceAccessException e) {
            throw new ProductServiceUnavailableException();
        }
    }

    // PG 승인이 거절되면 확정한 선점을 풀어 재고를 되돌린다 - 이미 해제된 선점도 성공으로 응답하므로 다시 요청해도 안전
    public void releaseReservation(String orderNo) {
        try {
            productServiceRestClient.post()
                    .uri("/internal/stock-reservations/{orderNo}/release", orderNo)
                    .retrieve()
                    .toBodilessEntity();
        } catch (ResourceAccessException e) {
            throw new ProductServiceUnavailableException();
        }
    }
}
