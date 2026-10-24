package com.park.ecommerce.order.dto;

import com.park.ecommerce.order.domain.Order;

import java.time.LocalDateTime;
import java.util.List;

// 주문 목록의 주문 1건 - 상세 조회로 이어지는 orderNo와 주문한 상품만 보여줌
public record OrderSummaryResponse(
        String orderNo,
        LocalDateTime orderedAt,
        List<OrderDetailResponse.Line> lines
) {
    public static OrderSummaryResponse from(Order order) {
        List<OrderDetailResponse.Line> lines = order.getLines().stream()
                .map(line -> new OrderDetailResponse.Line(line.getProductId(), line.getProductName(), line.getUnitPrice(), line.getQuantity()))
                .toList();
        return new OrderSummaryResponse(order.getOrderNo(), order.getOrderedAt(), lines);
    }
}
