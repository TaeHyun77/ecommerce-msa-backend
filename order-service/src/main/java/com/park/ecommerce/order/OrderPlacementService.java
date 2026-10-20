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

// 상품 조회 → 검증 → 저장 → 선점 → 실패하면 취소로 이어지는 서비스 간 흐름
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
        // 같은 상품이 여러 개 주문 들어왔는지 검사 - 앞단에서 검사하긴 하지만 혹시나
        if (request.hasDuplicateProduct()) throw new OrderException(OrderErrorCode.DUPLICATE_ORDER_PRODUCT);

        Map<Long, ProductSummaryResponse> products = findOrderableProducts(request);

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

        orderRepository.save(order); // 주문 생성
        reserveStock(order); // 재고 선점

        log.info("[주문] 결제 대기 주문 생성 orderNo={}, 결제 기한={}", order.getOrderNo(), order.getExpiresAt());
        return OrderCreateResponse.from(order);
    }

    // 주문에 들어온 상품들을 product-service에서 한 번에 조회하고, 모두 주문 가능한지 검증한 뒤, 상품 id → ProductSummaryResponse Map으로 반환
    private Map<Long, ProductSummaryResponse> findOrderableProducts(OrderCreateRequest request) {
        Map<Long, ProductSummaryResponse> products = productApiClient.findProducts(request.productIds()).stream()
                .collect(Collectors.toMap(ProductSummaryResponse::productId, Function.identity()));

        for (OrderCreateRequest.Item item : request.items()) {
            // 등록되지 않은 상품 식별자는 응답에서 빠진다
            ProductSummaryResponse product = products.get(item.productId());

            if (product == null) throw new OrderException(OrderErrorCode.PRODUCT_NOT_FOUND);
            if (!product.isOnSale()) throw new OrderException(OrderErrorCode.PRODUCT_NOT_ON_SALE);

            // 재고는 선점에서 다시 확인하지만, 명백히 부족한 주문은 취소 이력을 남기지 않도록 저장 전에 확인
            if (!product.canCover(item.quantity())) throw new OrderException(OrderErrorCode.INSUFFICIENT_STOCK);
        }
        return products;
    }

    // 재고 선점 api
    private void reserveStock(Order order) {
        try {
            productApiClient.reserve(StockReservationRequest.from(order));
        } catch (RuntimeException e) {
            // 선점하지 못한 주문은 결제로 이어질 수 없으므로 바로 취소
            // 타임아웃처럼 실제로는 선점됐을 수 있는 경우에도, 그 선점은 결제 기한에 product-service가 해제해 재고가 복구됨
            orderService.cancel(order.getOrderNo());
            log.warn("[주문] 재고 선점 실패로 주문 취소 orderNo={}, 사유={}", order.getOrderNo(), e.getMessage());
            throw e;
        }
    }
}
