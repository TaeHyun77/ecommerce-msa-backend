package com.park.ecommerce.order;

import com.park.ecommerce.order.dto.OrderResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// 서비스 간 내부 통신 전용 API로, 게이트웨이에서 외부 접근 차단 필요
@RequiredArgsConstructor
@RequestMapping("/internal/orders")
@RestController
public class OrderInternalController {
    private final OrderService orderService;

    @GetMapping
    public List<OrderResponse> getOrders(@RequestParam Long memberId) {
        return orderService.getOrdersByMemberId(memberId);
    }
}