package com.park.ecommerce.order;

import com.park.ecommerce.exception.OrderErrorCode;
import com.park.ecommerce.exception.OrderException;
import com.park.ecommerce.order.domain.Order;
import com.park.ecommerce.order.domain.OrderRepository;
import com.park.ecommerce.order.dto.OrderCreateRequest;
import com.park.ecommerce.order.dto.OrderCreateResponse;
import com.park.ecommerce.client.product.ProductApiClient;
import com.park.ecommerce.client.product.dto.ProductSummaryResponse;
import com.park.ecommerce.client.product.dto.StockReservationRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

// 1. 주문 생성 → 재고 선점
@Slf4j
@Service
public class OrderPlacementService {
    private final ProductApiClient productApiClient;
    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final Duration paymentWaitingTtl;

    public OrderPlacementService(
            ProductApiClient productApiClient,
            OrderRepository orderRepository,
            OrderService orderService,
            @Value("${order.payment-waiting.ttl}") Duration paymentWaitingTtl
    ) {
        this.productApiClient = productApiClient;
        this.orderRepository = orderRepository;
        this.orderService = orderService;
        this.paymentWaitingTtl = paymentWaitingTtl;
    }

    // 주문 생성
    public OrderCreateResponse place(Long memberId, OrderCreateRequest request) {
        if (request.hasDuplicateProduct()) { // 중복 상품 주문 여부 검사
            throw new OrderException(OrderErrorCode.DUPLICATE_ORDER_PRODUCT);
        }

        Map<Long, ProductSummaryResponse> products = findOrderableProducts(request); // 상품 정보를 담은 Map

        // 결제 기한은 재고 선점 만료 시각으로도 그대로 넘겨 두 서비스가 같은 기한을 쓰도록 함
        LocalDateTime now = LocalDateTime.now();
        Order order = Order.builder()
                .memberId(memberId)
                .orderedAt(now)
                .expiresAt(now.plus(paymentWaitingTtl))
                .build();

        request.items().forEach(item -> {
            ProductSummaryResponse product = products.get(item.productId());
            order.addLine(product.productId(), product.name(), product.price(), item.quantity());
        });

        orderRepository.save(order); // 주문 생성, 주문 시점의 스냅샷
        reserveStock(order); // 재고 선점

        log.debug("[주문] 결제 대기 주문 생성 orderNo={}, 결제 기한={}", order.getOrderNo(), order.getExpiresAt());
        return OrderCreateResponse.from(order);
    }

    // 상품 정보를 일괄 조회하고 주문 가능 여부를 검증한 뒤, 상품 ID를 키로 하는 Map으로 반환
    // 등록되어 있는 상품인지, 판매 가능 상태인지,
    private Map<Long, ProductSummaryResponse> findOrderableProducts(OrderCreateRequest request) {
        Map<Long, ProductSummaryResponse> products = productApiClient.findProducts(request.productIds()).stream()
                .collect(Collectors.toMap(ProductSummaryResponse::productId, Function.identity()));

        for (OrderCreateRequest.Item item : request.items()) {
            ProductSummaryResponse product = products.get(item.productId());

            if (product == null) throw new OrderException(OrderErrorCode.PRODUCT_NOT_FOUND);
            if (!product.isOnSale()) throw new OrderException(OrderErrorCode.PRODUCT_NOT_ON_SALE);

            // 재고는 선점에서 다시 확인하지만, 명백히 부족한 주문은 실패 이력을 남기지 않도록 저장 전에 확인
            if (!product.hasEnoughStock(item.quantity())) throw new OrderException(OrderErrorCode.INSUFFICIENT_STOCK);
        }
        return products;
    }

    // 재고 선점
    private void reserveStock(Order order) {
        try {
            productApiClient.reserve(StockReservationRequest.from(order));
        } catch (RuntimeException e) {
            // 선점하지 못한 주문은 결제로 이어질 수 없으므로 바로 실패 처리
            // 타임아웃처럼 실제로는 선점됐을 수 있는 경우에도, 그 선점은 결제 기한에 product-service가 해제하여 재고가 복구됨
            orderService.fail(order.getOrderNo());
            log.debug("[주문] 재고 선점 실패로 주문 실패 처리 orderNo={}, 사유={}", order.getOrderNo(), e.getMessage());
            throw e;
        }
    }
}
