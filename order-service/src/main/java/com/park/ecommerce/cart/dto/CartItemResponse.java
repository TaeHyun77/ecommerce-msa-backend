package com.park.ecommerce.cart.dto;

import com.park.ecommerce.cart.CartItem;
import com.park.ecommerce.client.product.dto.ProductSummaryResponse;

public record CartItemResponse(
        Long productId,
        String name,
        Integer price,
        Integer quantity,
        Integer lineAmount, // 판매가 × 수량 (원)
        String thumbnailUrl,
        boolean soldOut,
        boolean orderable // 판매중이고 담긴 수량만큼 재고가 남아 있는지
) {
    public static CartItemResponse of(CartItem cartItem, ProductSummaryResponse product) {
        int quantity = cartItem.getQuantity();
        return new CartItemResponse(
                product.productId(),
                product.name(),
                product.price(),
                quantity,
                product.price() * quantity,
                product.thumbnailUrl(),
                product.isSoldOut(),
                product.isOnSale() && product.hasEnoughStock(quantity)
        );
    }
}
