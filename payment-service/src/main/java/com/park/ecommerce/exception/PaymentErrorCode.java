package com.park.ecommerce.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum PaymentErrorCode {
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "결제 정보를 찾을 수 없습니다."),
    PAYMENT_REQUEST_MISMATCH(HttpStatus.CONFLICT, "이미 다른 결제 정보로 승인을 요청한 주문입니다."),
    PAYMENT_RESULT_UNKNOWN(HttpStatus.SERVICE_UNAVAILABLE, "결제 결과를 확인하지 못했습니다. 잠시 후 다시 확인해주세요.");

    private final HttpStatus status;
    private final String message;

    PaymentErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }
}
