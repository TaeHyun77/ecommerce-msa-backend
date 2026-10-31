package com.park.ecommerce.coupon;

import com.park.ecommerce.coupon.dto.CouponReserveRequest;
import com.park.ecommerce.coupon.dto.CouponReserveResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 서비스 간 내부 통신 전용 API로, 게이트웨이에서 외부 접근 차단 필요
@RestController
@RequestMapping("/internal/coupon-reservations")
@RequiredArgsConstructor
public class CouponReservationController {
    private final CouponReservationFacade couponReservationFacade;
    private final CouponService couponService;

    // 선점 - 주문에 적용할 할인액을 응답, 같은 주문번호로 다시 요청해도 같은 할인액으로 응답
    @PostMapping
    public CouponReserveResponse reserve(@Valid @RequestBody CouponReserveRequest request) {
        return couponReservationFacade.reserve(request);
    }

    // 쿠폰 적용 확정
    @PostMapping("/{orderNo}/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirm(@PathVariable String orderNo) {
        couponService.confirm(orderNo);
    }

    // 쿠폰 적용 해제
    @PostMapping("/{orderNo}/release")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@PathVariable String orderNo) {
        couponService.release(orderNo);
    }
}
