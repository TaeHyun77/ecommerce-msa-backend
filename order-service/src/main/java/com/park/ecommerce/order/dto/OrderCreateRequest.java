package com.park.ecommerce.order.dto;

import com.park.ecommerce.cart.CartItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

// 장바구니 주문과 바로 구매를 같은 형식으로 받음
public record OrderCreateRequest(
        @NotEmpty(message = "주문 품목은 1개 이상이어야 합니다.")
        List<@Valid Item> items,

        Long memberCouponId // 적용할 쿠폰 - 쓰지 않으면 null
) {
    public record Item(
            @NotNull(message = "상품은 필수입니다.")
            Long productId,

            @NotNull(message = "수량은 필수입니다.")
            @Min(value = 1, message = "수량은 1개 이상이어야 합니다.")
            @Max(value = CartItem.MAX_QUANTITY, message = "수량은 99개 이하여야 합니다.") // 장바구니와 같은 1회 구매 제한
            Integer quantity
    ) {
    }

    // 같은 상품이 두 줄이면 재고가 두 번 선점되므로 요청 자체를 거절하기 위해 검사
    public boolean hasDuplicateProduct() {
        return items.stream().map(Item::productId).distinct().count() != items.size();
    }

    public List<Long> productIds() {
        return items.stream().map(Item::productId).toList();
    }
}
