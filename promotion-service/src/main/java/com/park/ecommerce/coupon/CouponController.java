package com.park.ecommerce.coupon;

import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/coupons")
@RequiredArgsConstructor
public class CouponController {
    private static final String MEMBER_ID_HEADER = "X-Member-Id";
    private final CouponIssueFacade couponIssueFacade;

    // 쿠폰 다운로드 - 잘못된 회원 식별자로 쿠폰 행 잠금까지 가지 않도록 헤더를 먼저 검증
    @PostMapping("/{couponId}/issue")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void issue(
            @RequestHeader(MEMBER_ID_HEADER) @Positive(message = "회원 식별자가 올바르지 않습니다.") Long memberId,
            @PathVariable Long couponId
    ) {
        couponIssueFacade.issue(couponId, memberId);
    }
}
