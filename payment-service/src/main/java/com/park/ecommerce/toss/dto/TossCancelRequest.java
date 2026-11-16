package com.park.ecommerce.toss.dto;

// 토스페이먼츠 결제 취소 API 요청 - 취소 금액을 보내지 않으면 전액 취소
public record TossCancelRequest(String cancelReason) {
}
