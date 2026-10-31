package com.park.ecommerce.order;

import com.park.ecommerce.exception.OrderErrorCode;
import com.park.ecommerce.exception.OrderException;
import com.park.ecommerce.exception.ProductServiceUnavailableException;
import com.park.ecommerce.exception.PromotionServiceUnavailableException;
import com.park.ecommerce.order.domain.Order;
import com.park.ecommerce.order.domain.OrderRepository;
import com.park.ecommerce.order.domain.OrderStatus;
import com.park.ecommerce.order.dto.OrderCreateRequest;
import com.park.ecommerce.order.dto.OrderCreateResponse;
import com.park.ecommerce.client.product.ProductApiClient;
import com.park.ecommerce.client.product.dto.ProductSummaryResponse;
import com.park.ecommerce.client.product.dto.StockReservationRequest;
import com.park.ecommerce.client.payment.PaymentApiClient;
import com.park.ecommerce.client.promotion.PromotionApiClient;
import com.park.ecommerce.client.promotion.dto.CouponReservationRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

// 선점 결과에 따라 주문이 저장되는지는 실제 DB로 확인하고, 외부 서비스(product·promotion·payment)만 대체한다
@Testcontainers
@SpringBootTest(properties = {
        "order.payment-waiting.ttl=10m",
        // 테스트 중 스케줄러 개입 방지 - 0원 주문이 승인 중으로 남는 경우를 복구 스케줄러가 건드리지 않도록
        "order.payment-waiting.expire-poll-delay=1h",
        "order.approval-recovery.poll-delay=1h"
})
class OrderPlacementServiceTest {
    private static final AtomicLong MEMBER_SEQUENCE = new AtomicLong();

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.0");

    @Autowired
    private OrderPlacementService orderPlacementService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private ProductApiClient productApiClient;

    @MockitoBean
    private PromotionApiClient promotionApiClient;

    @MockitoBean
    private PaymentApiClient paymentApiClient;

    @Test
    @DisplayName("주문하면 상품명·단가를 스냅샷으로 담아 결제 대기 주문을 저장한다")
    void savesPaymentWaitingOrderWithSnapshot() {
        givenProducts(onSale(1L, "유기농 우유 900ml", 3_900, 10), onSale(2L, "국산콩 두부 300g", 2_500, 10));

        OrderCreateResponse response = orderPlacementService.place(newMemberId(), request(item(1L, 2), item(2L, 1)));

        Order order = orderRepository.findByOrderNo(response.orderNo()).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_WAITING);
        assertThat(order.getTotalAmount()).isEqualTo(10_300);
        assertThat(Duration.between(order.getOrderedAt(), order.getExpiresAt())).isEqualTo(Duration.ofMinutes(10));
        assertThat(linesOf(response.orderNo())).containsExactly(
                Map.of("product_id", 1L, "product_name", "유기농 우유 900ml", "unit_price", 3_900, "quantity", 2),
                Map.of("product_id", 2L, "product_name", "국산콩 두부 300g", "unit_price", 2_500, "quantity", 1)
        );
        assertThat(response.orderName()).isEqualTo("유기농 우유 900ml 외 1건");
        assertThat(response.amount()).isEqualTo(10_300);
    }

    @Test
    @DisplayName("저장할 주문과 같은 주문번호·결제 기한·품목으로 재고 선점을 요청한다")
    void reservesStockWithSameOrderNoAndExpiresAt() {
        givenProducts(onSale(1L, "유기농 우유 900ml", 3_900, 10), onSale(2L, "국산콩 두부 300g", 2_500, 10));

        OrderCreateResponse response = orderPlacementService.place(newMemberId(), request(item(1L, 2), item(2L, 1)));

        ArgumentCaptor<StockReservationRequest> captor = ArgumentCaptor.forClass(StockReservationRequest.class);
        verify(productApiClient).reserve(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new StockReservationRequest(
                response.orderNo(),
                response.expiresAt(),
                List.of(new StockReservationRequest.Item(1L, 2), new StockReservationRequest.Item(2L, 1))
        ));
    }

    @Test
    @DisplayName("재고 선점에 실패하면 주문을 저장하지 않고 예외를 그대로 던진다 - 결과를 모르는 장애도 같음")
    void rejectsOrderWhenReservationFails() {
        Long memberId = newMemberId();
        givenProducts(onSale(1L, "유기농 우유 900ml", 3_900, 10));
        willThrow(new ProductServiceUnavailableException()).given(productApiClient).reserve(any());

        assertThatThrownBy(() -> orderPlacementService.place(memberId, request(item(1L, 2))))
                .isInstanceOf(ProductServiceUnavailableException.class);

        assertThat(orderRepository.findAllByMemberIdOrderByOrderedAtDesc(memberId)).isEmpty();
    }

    @Test
    @DisplayName("선점 후 주문 저장이 실패하면 재고·쿠폰 선점을 바로 해제하고 저장 오류를 그대로 던진다")
    void releasesReservationsWhenSaveFails() {
        Long memberId = newMemberId();
        // 저장 실패를 실제 DB 오류로 재현 - 상품명이 컬럼 길이(255자)를 넘음
        givenProducts(onSale(1L, "가".repeat(256), 3_900, 10));
        given(promotionApiClient.reserveCoupon(any())).willReturn(2_000);

        assertThatThrownBy(() -> orderPlacementService.place(memberId, couponRequest(10L, item(1L, 2))))
                .isInstanceOf(DataIntegrityViolationException.class);

        String orderNo = reservedOrderNo();
        assertThat(orderRepository.findAllByMemberIdOrderByOrderedAtDesc(memberId)).isEmpty();
        verify(productApiClient).releaseReservation(orderNo);
        verify(promotionApiClient).releaseCoupon(orderNo);
    }

    @Test
    @DisplayName("쿠폰을 쓰지 않으면 promotion-service를 호출하지 않는다")
    void skipsPromotionWithoutCoupon() {
        givenProducts(onSale(1L, "유기농 우유 900ml", 3_900, 10));

        OrderCreateResponse response = orderPlacementService.place(newMemberId(), request(item(1L, 2)));

        assertThat(response.status()).isEqualTo(OrderStatus.PAYMENT_WAITING);
        verifyNoInteractions(promotionApiClient);
    }

    @Test
    @DisplayName("쿠폰을 쓰면 상품 합계로 쿠폰을 선점하고, 할인액을 반영해 저장한 뒤 결제 금액을 응답한다")
    void savesOrderWithCouponDiscount() {
        Long memberId = newMemberId();
        givenProducts(onSale(1L, "유기농 우유 900ml", 3_900, 10), onSale(2L, "국산콩 두부 300g", 2_500, 10));
        given(promotionApiClient.reserveCoupon(any())).willReturn(2_000);

        OrderCreateResponse response = orderPlacementService.place(memberId, couponRequest(10L, item(1L, 2), item(2L, 1)));

        ArgumentCaptor<CouponReservationRequest> captor = ArgumentCaptor.forClass(CouponReservationRequest.class);
        verify(promotionApiClient).reserveCoupon(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new CouponReservationRequest(10L, memberId, response.orderNo(), 10_300, response.expiresAt()));

        Order order = orderRepository.findByOrderNo(response.orderNo()).orElseThrow();
        assertThat(order.getMemberCouponId()).isEqualTo(10L);
        assertThat(order.getTotalAmount()).isEqualTo(10_300);
        assertThat(order.getDiscountAmount()).isEqualTo(2_000);
        assertThat(response.amount()).isEqualTo(8_300);
        assertThat(response.status()).isEqualTo(OrderStatus.PAYMENT_WAITING);
    }

    @Test
    @DisplayName("쓸 수 없는 쿠폰이면 주문을 저장하지 않고 재고 선점도 요청하지 않는다")
    void rejectsUnavailableCoupon() {
        Long memberId = newMemberId();
        givenProducts(onSale(1L, "유기농 우유 900ml", 3_900, 10));
        given(promotionApiClient.reserveCoupon(any())).willThrow(new OrderException(OrderErrorCode.COUPON_NOT_AVAILABLE));

        assertRejected(memberId, couponRequest(10L, item(1L, 1)), OrderErrorCode.COUPON_NOT_AVAILABLE);
    }

    @Test
    @DisplayName("쿠폰 선점 중 promotion-service 장애가 나면 주문을 저장하지 않고 재고 선점도 요청하지 않는다")
    void rejectsWhenPromotionUnavailable() {
        Long memberId = newMemberId();
        givenProducts(onSale(1L, "유기농 우유 900ml", 3_900, 10));
        given(promotionApiClient.reserveCoupon(any())).willThrow(new PromotionServiceUnavailableException());

        assertRejected(memberId, couponRequest(10L, item(1L, 1)), OrderErrorCode.PROMOTION_SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("쿠폰을 쓴 주문의 재고 선점이 실패하면 주문을 저장하지 않고 쿠폰을 바로 해제한다")
    void releasesCouponWhenStockReservationFails() {
        Long memberId = newMemberId();
        givenProducts(onSale(1L, "유기농 우유 900ml", 3_900, 10));
        given(promotionApiClient.reserveCoupon(any())).willReturn(2_000);
        willThrow(new OrderException(OrderErrorCode.INSUFFICIENT_STOCK)).given(productApiClient).reserve(any());

        assertThatThrownBy(() -> orderPlacementService.place(memberId, couponRequest(10L, item(1L, 2))))
                .isInstanceOf(OrderException.class)
                .extracting("errorCode")
                .isEqualTo(OrderErrorCode.INSUFFICIENT_STOCK);

        String orderNo = reservedOrderNo();
        assertThat(orderRepository.findAllByMemberIdOrderByOrderedAtDesc(memberId)).isEmpty();
        verify(promotionApiClient).releaseCoupon(orderNo);
        verify(productApiClient, never()).releaseReservation(anyString()); // 선점하지 못한 재고는 해제 대상이 아님
    }

    @Test
    @DisplayName("재고 선점 실패 뒤 쿠폰 해제까지 실패해도 원래 실패(재고 선점)를 그대로 던진다 - 쿠폰은 결제 기한에 만료로 해제")
    void throwsOriginalFailureWhenCouponReleaseFails() {
        Long memberId = newMemberId();
        givenProducts(onSale(1L, "유기농 우유 900ml", 3_900, 10));
        given(promotionApiClient.reserveCoupon(any())).willReturn(2_000);
        willThrow(new OrderException(OrderErrorCode.INSUFFICIENT_STOCK)).given(productApiClient).reserve(any());
        willThrow(new PromotionServiceUnavailableException()).given(promotionApiClient).releaseCoupon(anyString());

        assertThatThrownBy(() -> orderPlacementService.place(memberId, couponRequest(10L, item(1L, 2))))
                .isInstanceOf(OrderException.class)
                .extracting("errorCode")
                .isEqualTo(OrderErrorCode.INSUFFICIENT_STOCK);

        assertThat(orderRepository.findAllByMemberIdOrderByOrderedAtDesc(memberId)).isEmpty();
    }

    @Test
    @DisplayName("전액 할인으로 결제 금액이 0원이면 PG 승인 없이 재고·쿠폰을 확정하고 결제 완료로 응답한다")
    void completesZeroAmountOrderWithoutPayment() {
        givenProducts(onSale(1L, "유기농 우유 900ml", 3_900, 10));
        given(promotionApiClient.reserveCoupon(any())).willReturn(7_800);
        given(productApiClient.confirmReservation(anyString())).willReturn(true);
        given(promotionApiClient.confirmCoupon(anyString())).willReturn(true);

        OrderCreateResponse response = orderPlacementService.place(newMemberId(), couponRequest(10L, item(1L, 2)));

        assertThat(response.amount()).isZero();
        assertThat(response.status()).isEqualTo(OrderStatus.PAID);
        assertThat(orderRepository.findByOrderNo(response.orderNo()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
        InOrder inOrder = inOrder(productApiClient, promotionApiClient);
        inOrder.verify(productApiClient).reserve(any());
        inOrder.verify(productApiClient).confirmReservation(response.orderNo());
        inOrder.verify(promotionApiClient).confirmCoupon(response.orderNo());
        verifyNoInteractions(paymentApiClient);
    }

    @Test
    @DisplayName("0원 주문의 확정 결과를 모르면 승인 중으로 응답한다 - 복구 스케줄러가 이어서 처리")
    void staysApprovingWhenZeroAmountConfirmUnknown() {
        givenProducts(onSale(1L, "유기농 우유 900ml", 3_900, 10));
        given(promotionApiClient.reserveCoupon(any())).willReturn(7_800);
        given(productApiClient.confirmReservation(anyString())).willReturn(true);
        given(promotionApiClient.confirmCoupon(anyString())).willThrow(new PromotionServiceUnavailableException());

        OrderCreateResponse response = orderPlacementService.place(newMemberId(), couponRequest(10L, item(1L, 2)));

        assertThat(response.status()).isEqualTo(OrderStatus.APPROVING);
        assertThat(orderRepository.findByOrderNo(response.orderNo()).orElseThrow().getStatus()).isEqualTo(OrderStatus.APPROVING);
    }

    @Test
    @DisplayName("등록되지 않은 상품이 있으면 주문을 저장하지 않고 선점도 요청하지 않는다")
    void rejectsUnknownProduct() {
        Long memberId = newMemberId();
        givenProducts(onSale(1L, "유기농 우유 900ml", 3_900, 10));

        assertRejected(memberId, request(item(1L, 1), item(99L, 1)), OrderErrorCode.PRODUCT_NOT_FOUND);
    }

    @Test
    @DisplayName("판매중이 아닌 상품이 있으면 주문을 저장하지 않고 선점도 요청하지 않는다")
    void rejectsSuspendedProduct() {
        Long memberId = newMemberId();
        givenProducts(new ProductSummaryResponse(1L, "유기농 우유 900ml", 3_900, null, "SUSPENDED", 10));

        assertRejected(memberId, request(item(1L, 1)), OrderErrorCode.PRODUCT_NOT_ON_SALE);
    }

    @Test
    @DisplayName("재고가 명백히 부족하면 실패 주문을 남기지 않도록 주문을 저장하지 않는다")
    void rejectsObviouslyInsufficientStock() {
        Long memberId = newMemberId();
        givenProducts(onSale(1L, "유기농 우유 900ml", 3_900, 1));

        assertRejected(memberId, request(item(1L, 2)), OrderErrorCode.INSUFFICIENT_STOCK);
    }

    @Test
    @DisplayName("같은 상품이 여러 품목에 있으면 주문을 저장하지 않는다")
    void rejectsDuplicateProduct() {
        Long memberId = newMemberId();
        givenProducts(onSale(1L, "유기농 우유 900ml", 3_900, 10));

        assertRejected(memberId, request(item(1L, 1), item(1L, 2)), OrderErrorCode.DUPLICATE_ORDER_PRODUCT);
    }

    private void assertRejected(Long memberId, OrderCreateRequest request, OrderErrorCode errorCode) {
        assertThatThrownBy(() -> orderPlacementService.place(memberId, request))
                .isInstanceOf(OrderException.class)
                .extracting("errorCode")
                .isEqualTo(errorCode);

        assertThat(orderRepository.findAllByMemberIdOrderByOrderedAtDesc(memberId)).isEmpty();
        verify(productApiClient, never()).reserve(any());
    }

    // 주문을 저장하지 않은 경우의 주문번호 - 선점 요청에 실린 값으로 확인
    private String reservedOrderNo() {
        ArgumentCaptor<StockReservationRequest> captor = ArgumentCaptor.forClass(StockReservationRequest.class);
        verify(productApiClient).reserve(captor.capture());
        return captor.getValue().orderNo();
    }

    private void givenProducts(ProductSummaryResponse... products) {
        given(productApiClient.findProducts(anyCollection())).willReturn(List.of(products));
    }

    private List<Map<String, Object>> linesOf(String orderNo) {
        return jdbcTemplate.queryForList("""
                select l.product_id, l.product_name, l.unit_price, l.quantity
                from order_line l join orders o on l.order_id = o.id
                where o.order_no = ?
                order by l.product_id
                """, orderNo);
    }

    // 테스트끼리 같은 DB를 쓰므로 회원을 나눠 서로의 주문이 섞이지 않도록 한다
    private static Long newMemberId() {
        return MEMBER_SEQUENCE.incrementAndGet();
    }

    private static ProductSummaryResponse onSale(Long productId, String name, int price, int availableQuantity) {
        return new ProductSummaryResponse(productId, name, price, null, "ON_SALE", availableQuantity);
    }

    private static OrderCreateRequest request(OrderCreateRequest.Item... items) {
        return new OrderCreateRequest(List.of(items), null);
    }

    private static OrderCreateRequest couponRequest(Long memberCouponId, OrderCreateRequest.Item... items) {
        return new OrderCreateRequest(List.of(items), memberCouponId);
    }

    private static OrderCreateRequest.Item item(Long productId, int quantity) {
        return new OrderCreateRequest.Item(productId, quantity);
    }
}
