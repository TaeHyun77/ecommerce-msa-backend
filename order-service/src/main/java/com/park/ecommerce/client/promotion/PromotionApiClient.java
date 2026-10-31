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

@RequiredArgsConstructor
@CircuitBreaker(name = "promotionService")
@Component
public class PromotionApiClient {
    private final RestClient promotionServiceRestClient;

    // 쿠폰을 주문에 선점하고 할인액(원)을 돌려줌
    // 새 주문번호와 검증된 값으로만 호출하므로 4xx는 쿠폰 상태를 뜻한다 - 호출별 처리가 공통 4xx 처리(계약 불일치)보다 먼저 적용됨
    public int reserveCoupon(CouponReservationRequest request) {
        try {
            return promotionServiceRestClient.post()
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
                    .body(CouponReservationResponse.class)
                    .discountAmount();
        } catch (ResourceAccessException e) {
            throw new PromotionServiceUnavailableException();
        }
    }

    // PG 승인 직전에 호출 - 이미 확정된 쿠폰은 promotion-service가 성공으로 응답하므로 복구 중 같은 확정을 다시 요청해도 true
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

    // 확정된 쿠폰도 풀어 다시 쓸 수 있게 한다 - 선점이 없으면 promotion-service가 성공으로 응답하므로 다시 요청해도 안전
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
