package com.park.ecommerce.order.dto;

import com.park.ecommerce.order.domain.OrderStatus;

// status: CANCELLED(취소 완료) 또는 CANCELLING(처리 중 - 주문 조회로 최종 결과를 확인)
public record OrderCancelResponse(String orderNo, OrderStatus status) {
}
