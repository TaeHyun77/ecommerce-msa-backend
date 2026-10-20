package com.park.ecommerce.client.order.dto;

import java.time.LocalDateTime;

// order-service의 OrderResponse를 그대로 참조하지 않고, member-service가 실제로 필요한 필드만 직접 정의한 DTO
// order-service 응답에 필드가 추가/변경되어도 여기서 쓰는 필드 이름만 유지되면 영향받지 않음
public record MemberOrderResponse(
        Long orderId,
        String status,
        Integer totalAmount,
        LocalDateTime orderedAt
) {
}
