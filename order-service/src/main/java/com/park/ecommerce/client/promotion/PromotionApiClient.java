package com.park.ecommerce.client.promotion;

import com.park.ecommerce.client.promotion.dto.CouponReservationRequest;
import com.park.ecommerce.client.promotion.dto.CouponReservationResponse;
import com.park.ecommerce.exception.OrderErrorCode;
import com.park.ecommerce.exception.OrderException;
import com.park.ecommerce.exception.PromotionServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.Objects;

@RequiredArgsConstructor
@CircuitBreaker(name = "promotionService")
@Component
public class PromotionApiClient {
    private final RestClient promotionServiceRestClient;

    // 쿠폰 선점, 선점 후 할인액을 반환
    public int reserveCoupon(CouponReservationRequest request) {
        try {
            return Objects.requireNonNull(promotionServiceRestClient.post()
                            .uri("/internal/coupon-reservations")
                            .body(request)
                            .retrieve()
                            .onStatus(status -> status.value() == HttpStatus.NOT_FOUND.value(), (req, res) -> {
                                throw new OrderException(OrderErrorCode.COUPON_NOT_FOUND);
                            })
                            .onStatus(status -> status.value() == HttpStatus.BAD_REQUEST.value(), (req, res) -> {
                                throw new OrderException(OrderErrorCode.COUPON_NOT_IN_USE_PERIOD);
                            })
                            .onStatus(status -> status.value() == HttpStatus.CONFLICT.value(), (req, res) -> {
                                throw new OrderException(OrderErrorCode.COUPON_NOT_AVAILABLE);
                            })
                            .body(CouponReservationResponse.class))
                    .discountAmount();
        } catch (ResourceAccessException e) {
            throw new PromotionServiceUnavailableException();
        }
    }

    // 쿠폰 선점 확정
    public boolean confirmCoupon(String orderNo) {
        try {
            return promotionServiceRestClient.post()
                    .uri("/internal/coupon-reservations/{orderNo}/confirm", orderNo)
                    .retrieve()
                    // 409는 선점이 만료, 해제되어 확정할 수 없다는 뜻 - 예외 대신 false로 promotion-service 장애와 구분
                    .onStatus(status -> status.value() == HttpStatus.CONFLICT.value(), (req, res) -> {
                    })
                    .toBodilessEntity()
                    .getStatusCode()
                    .is2xxSuccessful();
        } catch (ResourceAccessException e) {
            throw new PromotionServiceUnavailableException();
        }
    }

    // 쿠폰 선점 해제
    public void releaseCoupon(String orderNo) {
        try {
            promotionServiceRestClient.post()
                    .uri("/internal/coupon-reservations/{orderNo}/release", orderNo)
                    .retrieve()
                    .toBodilessEntity();
        } catch (ResourceAccessException e) {
            throw new PromotionServiceUnavailableException();
        }
    }
}
