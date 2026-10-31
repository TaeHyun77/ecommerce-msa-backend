package com.park.ecommerce.coupon;

import com.park.ecommerce.coupon.dto.CouponReserveRequest;
import com.park.ecommerce.coupon.dto.CouponReserveResponse;
import com.park.ecommerce.exception.coupon.CouponErrorCode;
import com.park.ecommerce.exception.coupon.CouponException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// 동시 선점으로 실패하면 새 트랜잭션에서 재시도해 먼저 요청한 트랜잭션의 커밋 결과를 확인함
@Service
@RequiredArgsConstructor
public class CouponReservationFacade {
    private final CouponService couponService;

    public CouponReserveResponse reserve(CouponReserveRequest request) {
        try {
            return couponService.reserve(request);
        } catch (CouponException e) {
            if (e.getErrorCode() != CouponErrorCode.COUPON_NOT_AVAILABLE) throw e;
            // 같은 주문이 먼저 선점했으면 이번 시도에서 같은 할인액으로 응답하고, 다른 주문이 쓰고 있으면 그대로 거절됨
            return couponService.reserve(request);
        }
    }
}
