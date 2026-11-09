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

    // 재고 선점
    // 한 상품이라도 재고가 부족하면 product-service가 전체를 롤백하고 409로 응답
    public void reserveStock(StockReservationRequest request) {
        try {
            productServiceRestClient.post()
                    .uri("/internal/stock-reservations")
                    .body(request)
                    .retrieve()
                    .onStatus(status -> status.value() == HttpStatus.CONFLICT.value(), (req, res) -> {
                        throw new OrderException(OrderErrorCode.INSUFFICIENT_STOCK);
                    })
                    .toBodilessEntity();
        } catch (ResourceAccessException e) {
            throw new ProductServiceUnavailableException();
        }
    }

    // 재고 선점 확정
    public boolean confirmReservation(String orderNo) {
        try {
            return productServiceRestClient.post()
                    .uri("/internal/stock-reservations/{orderNo}/confirm", orderNo)
                    .retrieve()
                    // 409는 선점이 만료, 해제되어 확정할 수 없다는 뜻 - 예외 대신 false로 product-service 장애와 구분
                    .onStatus(status -> status.value() == HttpStatus.CONFLICT.value(), (req, res) -> {
                    })
                    .toBodilessEntity()
                    .getStatusCode()
                    .is2xxSuccessful();
        } catch (ResourceAccessException e) {
            throw new ProductServiceUnavailableException();
        }
    }

    // 재고 선점 해제
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
