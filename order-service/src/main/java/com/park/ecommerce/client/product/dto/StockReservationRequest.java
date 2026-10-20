package com.park.ecommerce.client.product.dto;

import com.park.ecommerce.order.domain.Order;

import java.time.LocalDateTime;
import java.util.List;

// product-service 재고 선점 API의 요청 형식을 그대로 옮긴 DTO - 필드 이름이 곧 서비스 간 계약
public record StockReservationRequest(
        String orderNo,
        LocalDateTime expiresAt,
        List<Item> items
) {
    public record Item(Long productId, Integer quantity) {
    }

    public static StockReservationRequest from(Order order) {
        List<Item> items = order.getLines().stream()
                .map(line -> new Item(line.getProductId(), line.getQuantity()))
                .toList();
        return new StockReservationRequest(order.getOrderNo(), order.getExpiresAt(), items);
    }
}
