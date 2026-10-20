package com.park.ecommerce.client.product.dto;

// product-service의 응답 중 장바구니·주문에 필요한 필드만 직접 정의한 DTO
// product-service 응답에 필드가 추가/변경되어도 여기서 쓰는 필드 이름만 유지되면 영향받지 않음
public record ProductSummaryResponse(
        Long productId,
        String name,
        Integer price,
        String thumbnailUrl,
        String status,
        Integer availableQuantity
) {
    private static final String ON_SALE = "ON_SALE";

    public boolean isOnSale() {
        return ON_SALE.equals(status);
    }

    public boolean isSoldOut() {
        return availableQuantity == 0;
    }

    public boolean canCover(int quantity) {
        return availableQuantity >= quantity;
    }
}
