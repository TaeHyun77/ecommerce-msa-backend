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
import com.park.ecommerce.client.promotion.PromotionApiClient;
import com.park.ecommerce.client.promotion.dto.CouponReservationRequest;
import com.park.ecommerce.order.dto.OrderPaymentResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

// 1. 쿠폰 선점 → 재고 선점 → 주문 생성
// 선점을 모두 마친 뒤 저장 - 선점에 실패한 주문은 결제로 이어질 수 없으므로 실패 주문을 남기지 않고 거절
@Slf4j
@Service
public class OrderPlacementService {
    private final ProductApiClient productApiClient;
    private final PromotionApiClient promotionApiClient;
    private final OrderRepository orderRepository;
    private final OrderPaymentService orderPaymentService;
    private final Duration paymentWaitingTtl;

    public OrderPlacementService(
            ProductApiClient productApiClient,
            PromotionApiClient promotionApiClient,
            OrderRepository orderRepository,
            OrderPaymentService orderPaymentService,
            @Value("${order.payment-waiting.ttl}") Duration paymentWaitingTtl
    ) {
        this.productApiClient = productApiClient;
        this.promotionApiClient = promotionApiClient;
        this.orderRepository = orderRepository;
        this.orderPaymentService = orderPaymentService;
        this.paymentWaitingTtl = paymentWaitingTtl;
    }

    // 주문 생성
    // 과정 : 재고 선점 -> 쿠폰 선점 -> 주문 생성
    public OrderCreateResponse place(Long memberId, OrderCreateRequest request) {
        if (request.hasDuplicateProduct()) { // 중복 상품 주문 여부 검사
            throw new OrderException(OrderErrorCode.DUPLICATE_ORDER_PRODUCT);
        }

        Map<Long, ProductSummaryResponse> products = findOrderableProducts(request); // 상품 정보를 담은 Map

        LocalDateTime now = LocalDateTime.now(); // 재고, 쿠폰 선점 만료 시각을 결제 기한으로 공유
        Order order = Order.builder()
                .memberId(memberId)
                .orderedAt(now)
                .expiresAt(now.plus(paymentWaitingTtl))
                .build();

        request.items().forEach(item -> {
            ProductSummaryResponse product = products.get(item.productId());
            order.addLine(product.productId(), product.name(), product.price(), item.quantity());
        });

        if (request.memberCouponId() != null) { // 주문에 쿠폰을 사용할 때, 쿠폰 선점
            reserveCoupon(order, request.memberCouponId());
        }
        reserveStock(order); // 재고 선점
        saveOrder(order); // 주문 생성 ( 주문 시점의 상품 정보를 스냅샷으로 저장하여 이후 변경과 무관하게 유지 )

        log.debug("[주문] 결제 대기 주문 생성 orderNo={}, 결제 기한={}", order.getOrderNo(), order.getExpiresAt());

        // 결제 금액이 0원이면 결제창을 띄울 수 없으므로 바로 확정 - 토스 정책
        if (order.getPaymentAmount() == 0) {
            OrderPaymentResponse payment = orderPaymentService.approveWithoutPayment(order);
            return OrderCreateResponse.of(order, payment.status());
        }
        return OrderCreateResponse.from(order);
    }

    // 주문하는 상품들을 product-service에 일괄 조회하고 검증 후 Map<상품 ID, 상품 정보>  반환
    private Map<Long, ProductSummaryResponse> findOrderableProducts(OrderCreateRequest request) {
        Map<Long, ProductSummaryResponse> products = productApiClient.findProducts(request.productIds()).stream()
                .collect(Collectors.toMap(ProductSummaryResponse::productId, Function.identity()));

        for (OrderCreateRequest.Item item : request.items()) {
            ProductSummaryResponse product = products.get(item.productId());

            if (product == null) throw new OrderException(OrderErrorCode.PRODUCT_NOT_FOUND);
            if (!product.isOnSale()) throw new OrderException(OrderErrorCode.PRODUCT_NOT_ON_SALE);

            // 재고는 선점에서 다시 확인하지만, 명백히 부족한 주문은 선점 요청 없이 바로 거절
            if (!product.hasEnoughStock(item.quantity())) throw new OrderException(OrderErrorCode.INSUFFICIENT_STOCK);
        }
        return products;
    }

    // 쿠폰 선점
    // 쿠폰은 회원별 자원이므로 먼저 선점하고, 공용 자원인 재고는 마지막에 짧게 선점하여 실패 시 다른 고객에게 미치는 영향을 최소화
    private void reserveCoupon(Order order, Long memberCouponId) {
        int discountAmount = promotionApiClient.reserveCoupon(CouponReservationRequest.of(order, memberCouponId));
        order.applyCoupon(memberCouponId, discountAmount); // 주문에 할인액을 저장
    }

    // 재고 선점
    private void reserveStock(Order order) {
        try {
            productApiClient.reserve(StockReservationRequest.from(order));
        } catch (RuntimeException e) {
            // 타임아웃처럼 실제로는 선점됐을 수 있는 경우에도, 그 선점은 결제 기한에 product-service가 해제하여 재고가 복구됨
            releaseCoupon(order);
            log.debug("[주문] 재고 선점 실패로 주문 거절 orderNo={}, 사유={}", order.getOrderNo(), e.getMessage());
            throw e;
        }
    }

    // 주문 생성
    // 선점을 마친 뒤 저장이 실패하면 바로 선점 해제 - 해제에 실패해도 결제 기한 만료 후 각 서비스의 스케줄러가 해제
    private void saveOrder(Order order) {
        try {
            orderRepository.save(order);
        } catch (RuntimeException e) {
            releaseStock(order);
            releaseCoupon(order);
            log.warn("[주문] 주문 저장 실패로 선점 해제 orderNo={}, 사유={}", order.getOrderNo(), e.getMessage());
            throw e;
        }
    }

    // 재고 선점 해제
    private void releaseStock(Order order) {
        try {
            productApiClient.releaseReservation(order.getOrderNo());
        } catch (RuntimeException e) {
            log.warn("[주문] 재고 선점 해제 실패 - 결제 기한에 만료로 해제됨 orderNo={}, 사유={}", order.getOrderNo(), e.getMessage());
        }
    }

    // 쿠폰 선점 해제
    private void releaseCoupon(Order order) {
        if (!order.hasCoupon()) return;

        try {
            promotionApiClient.releaseCoupon(order.getOrderNo());
        } catch (RuntimeException e) {
            log.warn("[주문] 쿠폰 해제 실패 - 결제 기한에 만료로 해제됨 orderNo={}, 사유={}", order.getOrderNo(), e.getMessage());
        }
    }
}
