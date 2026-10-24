package com.park.ecommerce.reservation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// order-service가 주문을 만든 직후 보내는 재고 선점 요청
// 만료 시각은 주문의 결제 가능 기한과 같아야 하므로 order-service가 정해서 보내도록 함
public record StockReservationRequest(
        @NotBlank(message = "주문번호는 필수입니다.")
        String orderNo,

        @NotNull(message = "선점 만료 시각은 필수입니다.")
        @Future(message = "선점 만료 시각은 현재 이후여야 합니다.")
        LocalDateTime expiresAt,

        @NotEmpty(message = "선점 품목은 1개 이상이어야 합니다.")
        List<@Valid Item> items
) {
    public record Item(
            @NotNull(message = "상품은 필수입니다.")
            Long productId,

            @NotNull(message = "수량은 필수입니다.")
            @Positive(message = "수량은 1개 이상이어야 합니다.")
            Integer quantity
    ) {
    }

    public Map<Long, Integer> quantities() {
        return items.stream().collect(Collectors.toMap(Item::productId, Item::quantity));
    }
}
