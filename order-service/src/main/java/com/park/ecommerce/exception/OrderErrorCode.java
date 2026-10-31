package com.park.ecommerce.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum OrderErrorCode {
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),
    MAX_QUANTITY_EXCEEDED(HttpStatus.BAD_REQUEST, "한 상품은 99개까지 담을 수 있습니다."),
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 상품입니다."),
    PRODUCT_NOT_ON_SALE(HttpStatus.BAD_REQUEST, "판매 중이 아닌 상품입니다."),
    PRODUCT_SOLD_OUT(HttpStatus.CONFLICT, "품절된 상품입니다."),
    INSUFFICIENT_STOCK(HttpStatus.CONFLICT, "재고가 부족합니다."),
    CART_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "장바구니에 담겨 있지 않은 상품입니다."),
    DUPLICATE_ORDER_PRODUCT(HttpStatus.BAD_REQUEST, "같은 상품이 여러 품목에 중복되어 있습니다."),
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다."),
    ORDER_NOT_PAYABLE(HttpStatus.CONFLICT, "결제할 수 없는 주문입니다."),
    ORDER_EXPIRED(HttpStatus.CONFLICT, "결제 가능 시간이 지났습니다. 다시 주문해주세요."),
    PAYMENT_AMOUNT_MISMATCH(HttpStatus.BAD_REQUEST, "결제 금액이 주문 금액과 다릅니다."),
    PAYMENT_IN_PROGRESS(HttpStatus.CONFLICT, "결제를 처리하고 있습니다. 잠시 후 주문 상태를 확인해주세요."),
    PAYMENT_FAILED(HttpStatus.CONFLICT, "결제가 승인되지 않았습니다. 다시 주문해주세요."),
    COUPON_NOT_FOUND(HttpStatus.NOT_FOUND, "보유한 쿠폰을 찾을 수 없습니다."),
    COUPON_NOT_IN_USE_PERIOD(HttpStatus.BAD_REQUEST, "쿠폰 사용 기간이 아닙니다."),
    COUPON_NOT_AVAILABLE(HttpStatus.CONFLICT, "다른 주문에 사용 중이거나 이미 사용한 쿠폰입니다."),
    PRODUCT_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "상품 정보를 가져오지 못했습니다. 잠시 후 다시 시도해주세요."),
    PROMOTION_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "쿠폰 정보를 가져오지 못했습니다. 잠시 후 다시 시도해주세요.");

    private final HttpStatus status;
    private final String message;

    OrderErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }
}
