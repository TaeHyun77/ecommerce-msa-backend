package com.park.ecommerce.toss;

import lombok.Getter;

// 토스페이먼츠 호출이 성공 응답을 받지 못한 경우 - 오류 응답, 타임아웃, 연결 실패
@Getter
public class TossPaymentsException extends RuntimeException {
    private final String code; // 토스 에러 코드 - 응답을 받지 못했거나 오류 응답에 본문이 없으면 null
    private final boolean noResponse; // 타임아웃, 연결 실패로 응답 자체를 받지 못한 경우

    public TossPaymentsException(String code, String message) {
        this(code, message, false);
    }

    private TossPaymentsException(String code, String message, boolean noResponse) {
        super(message);
        this.code = code;
        this.noResponse = noResponse;
    }

    public static TossPaymentsException noResponse(String message) {
        return new TossPaymentsException(null, message, true);
    }
}
