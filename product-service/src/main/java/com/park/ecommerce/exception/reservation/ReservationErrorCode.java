package com.park.ecommerce.exception.reservation;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ReservationErrorCode {
    DUPLICATE_RESERVATION_PRODUCT(HttpStatus.BAD_REQUEST, "같은 상품이 여러 품목에 중복되어 있습니다."),
    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "재고 선점 정보를 찾을 수 없습니다."),
    RESERVATION_EXPIRED(HttpStatus.CONFLICT, "재고 선점이 만료되었거나 해제되었습니다.");

    private final HttpStatus status;
    private final String message;

    ReservationErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }
}
