package com.park.ecommerce.exception;

/**
 * payment-service에 결제 승인을 요청했지만 결과를 알 수 없는 경우 ( 결과 불명 응답, 5xx, 타임아웃, 호출 계약 오류 )
 * 주문을 승인 중(APPROVING)으로 남겨 복구 스케줄러가 같은 요청을 다시 보내도록 하는 신호로 쓴다
 */
public class PaymentResultUnknownException extends RuntimeException {
    public PaymentResultUnknownException(String message) {
        super(message);
    }
}
