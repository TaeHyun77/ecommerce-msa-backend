package com.park.ecommerce.exception.coupon;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum CouponErrorCode {
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),
    COUPON_NOT_FOUND(HttpStatus.NOT_FOUND, "쿠폰을 찾을 수 없습니다."),
    NOT_IN_ISSUE_PERIOD(HttpStatus.BAD_REQUEST, "쿠폰 발급 기간이 아닙니다."),
    COUPON_SOLD_OUT(HttpStatus.CONFLICT, "쿠폰이 모두 소진되었습니다."),
    ALREADY_ISSUED(HttpStatus.CONFLICT, "이미 발급받은 쿠폰입니다.");

    private final HttpStatus status;
    private final String message;

    CouponErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }
}
