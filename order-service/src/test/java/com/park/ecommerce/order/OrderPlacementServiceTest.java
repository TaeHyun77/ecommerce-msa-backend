package com.park.ecommerce.order;

import com.park.ecommerce.exception.OrderErrorCode;
import com.park.ecommerce.exception.OrderException;
import com.park.ecommerce.exception.ProductServiceUnavailableException;
import com.park.ecommerce.order.domain.Order;
import com.park.ecommerce.order.domain.OrderRepository;
import com.park.ecommerce.order.domain.OrderStatus;
import com.park.ecommerce.order.dto.OrderCreateRequest;
import com.park.ecommerce.order.dto.OrderCreateResponse;
import com.park.ecommerce.client.product.ProductApiClient;
import com.park.ecommerce.client.product.dto.ProductSummaryResponse;
import com.park.ecommerce.client.product.dto.StockReservationRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// 주문 저장과 실패 처리가 원격 호출과 분리된 트랜잭션으로 반영되는지는 실제 DB로 확인하고, 외부 서비스인 product-service만 대체한다
@Testcontainers
@SpringBootTest(properties = {
        "order.payment-waiting.ttl=10m",
        "order.payment-waiting.expire-poll-delay=1h" // 테스트 중 스케줄러 개입 방지
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
    @DisplayName("주문을 저장한 뒤 같은 주문번호·결제 기한·품목으로 재고 선점을 요청한다")
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
    @DisplayName("재고 선점에 실패하면 주문을 실패 처리하고 예외를 그대로 던진다 - 결과를 모르는 장애도 실패 처리")
    void failsOrderWhenReservationFails() {
        Long memberId = newMemberId();
        givenProducts(onSale(1L, "유기농 우유 900ml", 3_900, 10));
        willThrow(new ProductServiceUnavailableException()).given(productApiClient).reserve(any());

        assertThatThrownBy(() -> orderPlacementService.place(memberId, request(item(1L, 2))))
                .isInstanceOf(ProductServiceUnavailableException.class);

        assertThat(orderRepository.findAllByMemberIdOrderByOrderedAtDesc(memberId))
                .singleElement()
                .extracting(Order::getStatus)
                .isEqualTo(OrderStatus.FAILED);
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
        return new OrderCreateRequest(List.of(items));
    }

    private static OrderCreateRequest.Item item(Long productId, int quantity) {
        return new OrderCreateRequest.Item(productId, quantity);
    }
}
