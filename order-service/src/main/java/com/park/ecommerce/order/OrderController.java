package com.park.ecommerce.order;

import com.park.ecommerce.order.dto.OrderCreateRequest;
import com.park.ecommerce.order.dto.OrderCreateResponse;
import com.park.ecommerce.order.domain.OrderStatus;
import com.park.ecommerce.order.dto.OrderDetailResponse;
import com.park.ecommerce.order.dto.OrderPaymentRequest;
import com.park.ecommerce.order.dto.OrderPaymentResponse;
import com.park.ecommerce.order.dto.OrderSummaryResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// 회원은 게이트웨이가 JWT를 검증한 뒤 심어준 헤더로 식별 - 요청 파라미터로 받으면 남의 주문에 접근할 수 있음
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {
    private static final String MEMBER_ID_HEADER = "X-Member-Id";

    private final OrderPlacementService orderPlacementService;
    private final OrderService orderService;
    private final OrderPaymentService orderPaymentService;

    // 주문 목록 조회 - 요약 정보
    @GetMapping
    public List<OrderSummaryResponse> getOrders(@RequestHeader(MEMBER_ID_HEADER) Long memberId) {
        return orderService.getOrderSummaries(memberId);
    }

    // 주문 생성 - 재고를 선점하고 결제창을 띄우는 데 필요한 값을 응답
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderCreateResponse createOrder(
            @RequestHeader(MEMBER_ID_HEADER) Long memberId,
            @Valid @RequestBody OrderCreateRequest request
    ) {
        return orderPlacementService.place(memberId, request);
    }

    // 결제 승인 - 토스페이먼츠 결제창 인증 후 successUrl에서 호출
    // 결제가 끝나면 200, 결과를 아직 모르면 202 - 202를 받으면 주문 조회로 최종 결과를 확인한다
    @PostMapping("/{orderNo}/payment")
    public ResponseEntity<OrderPaymentResponse> approvePayment(
            @RequestHeader(MEMBER_ID_HEADER) Long memberId,
            @PathVariable String orderNo,
            @Valid @RequestBody OrderPaymentRequest request
    ) {
        OrderPaymentResponse response = orderPaymentService.approve(memberId, orderNo, request);
        HttpStatus status = response.status() == OrderStatus.PAID ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(response);
    }

    // 특정 주문 조회
    @GetMapping("/{orderNo}")
    public OrderDetailResponse getOrder(
            @RequestHeader(MEMBER_ID_HEADER) Long memberId,
            @PathVariable String orderNo
    ) {
        return orderPaymentService.getOrder(memberId, orderNo);
    }
}
