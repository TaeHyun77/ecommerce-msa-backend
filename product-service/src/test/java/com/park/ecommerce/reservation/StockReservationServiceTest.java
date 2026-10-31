package com.park.ecommerce.reservation;

import com.park.ecommerce.category.Category;
import com.park.ecommerce.category.CategoryRepository;
import com.park.ecommerce.exception.product.ProductErrorCode;
import com.park.ecommerce.exception.product.ProductException;
import com.park.ecommerce.exception.reservation.ReservationErrorCode;
import com.park.ecommerce.exception.reservation.ReservationException;
import com.park.ecommerce.product.Product;
import com.park.ecommerce.product.ProductRepository;
import com.park.ecommerce.product.ProductService;
import com.park.ecommerce.product.dto.ProductCreateRequest;
import com.park.ecommerce.product.status.StorageType;
import com.park.ecommerce.reservation.domain.StockReservation;
import com.park.ecommerce.reservation.domain.StockReservationRepository;
import com.park.ecommerce.reservation.domain.StockReservationStatus;
import com.park.ecommerce.reservation.dto.StockReservationRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 선점·확정·해제는 조건부 UPDATE의 조건이 핵심이라 실제 MySQL에서 검증
@Testcontainers
@SpringBootTest(properties = { // 테스트 중 스케줄러 개입 방지 - 만료 스케줄러는 테스트에서 직접 호출
        "stock.reservation.expire-poll-delay=1h",
        "outbox.relay.poll-delay=1h"
})
class StockReservationServiceTest {
    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.0");

    @Autowired
    private StockReservationService stockReservationService;

    @Autowired
    private StockReservationRepository stockReservationRepository;

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private StockReservationExpiryScheduler stockReservationExpiryScheduler;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("선점하면 상품마다 주문 수량만큼 재고가 줄어든다")
    void decreasesStockOnReserve() {
        Long first = productWithStock(10);
        Long second = productWithStock(5);
        String orderNo = newOrderNo();

        stockReservationService.reserve(request(orderNo, inTenMinutes(), item(first, 3), item(second, 5)));

        assertThat(stockOf(first)).isEqualTo(7);
        assertThat(stockOf(second)).isZero();
        assertThat(statusOf(orderNo)).isEqualTo(StockReservationStatus.RESERVED);
    }

    @Test
    @DisplayName("한 상품이라도 재고가 부족하면 예외가 발생하고 다른 상품의 차감도 취소된다")
    void rollsBackAllWhenAnyStockIsInsufficient() {
        Long enough = productWithStock(10);
        Long lacking = productWithStock(1);
        String orderNo = newOrderNo();

        assertThatThrownBy(() -> stockReservationService.reserve(request(orderNo, inTenMinutes(), item(enough, 3), item(lacking, 2))))
                .isInstanceOf(ProductException.class)
                .extracting("errorCode")
                .isEqualTo(ProductErrorCode.INSUFFICIENT_STOCK);

        assertThat(stockOf(enough)).isEqualTo(10);
        assertThat(stockOf(lacking)).isEqualTo(1);
        assertThat(stockReservationRepository.findByOrderNo(orderNo)).isEmpty();
    }

    @Test
    @DisplayName("같은 주문번호로 다시 선점하면 성공으로 끝나고 재고는 한 번만 줄어든다 - 결과를 모르는 order-service의 재요청")
    void reservesOnceForSameOrderNo() {
        Long productId = productWithStock(10);
        String orderNo = newOrderNo();
        stockReservationService.reserve(request(orderNo, inTenMinutes(), item(productId, 3)));

        stockReservationService.reserve(request(orderNo, inTenMinutes(), item(productId, 3)));

        assertThat(stockOf(productId)).isEqualTo(7);
        assertThat(statusOf(orderNo)).isEqualTo(StockReservationStatus.RESERVED);
    }

    @Test
    @DisplayName("존재하지 않는 상품이 있으면 재고 부족이 아닌 상품 없음으로 거절한다")
    void rejectsUnknownProduct() {
        assertThatThrownBy(() -> stockReservationService.reserve(request(newOrderNo(), inTenMinutes(), item(Long.MAX_VALUE, 1))))
                .isInstanceOf(ProductException.class)
                .extracting("errorCode")
                .isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND);
    }

    @Test
    @DisplayName("만료 전 선점을 확정하면 확정 상태가 된다")
    void confirmsReservation() {
        String orderNo = reserve(productWithStock(10), inTenMinutes());

        stockReservationService.confirm(orderNo);

        assertThat(statusOf(orderNo)).isEqualTo(StockReservationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("이미 확정된 선점을 다시 확정해도 예외 없이 확정 상태를 유지한다")
    void confirmsAgainIdempotently() {
        String orderNo = reserve(productWithStock(10), inTenMinutes());
        stockReservationService.confirm(orderNo);

        stockReservationService.confirm(orderNo);

        assertThat(statusOf(orderNo)).isEqualTo(StockReservationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("만료 시각이 지난 선점은 아직 해제되지 않았어도 확정할 수 없다")
    void rejectsConfirmAfterExpiresAt() {
        String orderNo = reserve(productWithStock(10), LocalDateTime.now().minusSeconds(1));

        assertThatThrownBy(() -> stockReservationService.confirm(orderNo))
                .isInstanceOf(ReservationException.class)
                .extracting("errorCode")
                .isEqualTo(ReservationErrorCode.RESERVATION_EXPIRED);

        assertThat(statusOf(orderNo)).isEqualTo(StockReservationStatus.RESERVED);
    }

    @Test
    @DisplayName("선점이 없는 주문번호를 확정하면 선점 없음으로 거절한다")
    void rejectsConfirmOfUnknownOrder() {
        assertThatThrownBy(() -> stockReservationService.confirm(newOrderNo()))
                .isInstanceOf(ReservationException.class)
                .extracting("errorCode")
                .isEqualTo(ReservationErrorCode.RESERVATION_NOT_FOUND);
    }

    @Test
    @DisplayName("선점을 해제하면 해제 상태가 되고 재고가 복구된다")
    void releasesReservation() {
        Long productId = productWithStock(10);
        String orderNo = reserve(productId, inTenMinutes());

        stockReservationService.release(orderNo);

        assertThat(statusOf(orderNo)).isEqualTo(StockReservationStatus.RELEASED);
        assertThat(stockOf(productId)).isEqualTo(10);
    }

    @Test
    @DisplayName("확정된 선점도 해제하면 재고가 복구된다 - PG 승인이 거절된 경우")
    void releasesConfirmedReservation() {
        Long productId = productWithStock(10);
        String orderNo = reserve(productId, inTenMinutes());
        stockReservationService.confirm(orderNo);

        stockReservationService.release(orderNo);

        assertThat(statusOf(orderNo)).isEqualTo(StockReservationStatus.RELEASED);
        assertThat(stockOf(productId)).isEqualTo(10);
    }

    @Test
    @DisplayName("이미 해제된 선점을 다시 해제해도 재고는 한 번만 복구된다")
    void restoresStockOnlyOnce() {
        Long productId = productWithStock(10);
        String orderNo = reserve(productId, inTenMinutes());
        stockReservationService.release(orderNo);

        stockReservationService.release(orderNo);

        assertThat(stockOf(productId)).isEqualTo(10);
    }

    @Test
    @DisplayName("선점이 없는 주문번호를 해제해도 예외 없이 끝난다")
    void ignoresReleaseOfUnknownOrder() {
        stockReservationService.release(newOrderNo());
    }

    @Test
    @DisplayName("해제된 선점은 만료 시각 전이어도 확정할 수 없다")
    void rejectsConfirmAfterRelease() {
        String orderNo = reserve(productWithStock(10), inTenMinutes());
        stockReservationService.release(orderNo);

        assertThatThrownBy(() -> stockReservationService.confirm(orderNo))
                .isInstanceOf(ReservationException.class)
                .extracting("errorCode")
                .isEqualTo(ReservationErrorCode.RESERVATION_EXPIRED);
    }

    @Test
    @DisplayName("만료 시각이 지난 선점은 스케줄러가 해제하고 재고를 복구한다")
    void expiresReservationPastExpiresAt() {
        Long productId = productWithStock(10);
        String orderNo = reserve(productId, LocalDateTime.now().minusSeconds(1));

        stockReservationExpiryScheduler.releaseExpiredReservations();

        assertThat(statusOf(orderNo)).isEqualTo(StockReservationStatus.RELEASED);
        assertThat(stockOf(productId)).isEqualTo(10);
    }

    @Test
    @DisplayName("만료 시각 전 선점은 스케줄러가 해제하지 않는다")
    void keepsReservationBeforeExpiresAt() {
        Long productId = productWithStock(10);
        String orderNo = reserve(productId, inTenMinutes());

        stockReservationExpiryScheduler.releaseExpiredReservations();

        assertThat(statusOf(orderNo)).isEqualTo(StockReservationStatus.RESERVED);
        assertThat(stockOf(productId)).isEqualTo(9);
    }

    @Test
    @DisplayName("확정된 선점은 만료 시각이 지나도 스케줄러가 해제하지 않는다 - PG 승인 중인 재고를 지키기 위해")
    void keepsConfirmedReservationPastExpiresAt() {
        Long productId = productWithStock(10);
        String orderNo = reserve(productId, inTenMinutes());
        stockReservationService.confirm(orderNo);
        // 확정은 만료 시각 전에만 가능하므로, 확정 후 만료 시각이 지난 상황을 DB에서 직접 만든다
        jdbcTemplate.update("update stock_reservation set expires_at = ? where order_no = ?", LocalDateTime.now().minusSeconds(1), orderNo);

        stockReservationExpiryScheduler.releaseExpiredReservations();

        assertThat(statusOf(orderNo)).isEqualTo(StockReservationStatus.CONFIRMED);
        assertThat(stockOf(productId)).isEqualTo(9);
    }

    private Long productWithStock(int stock) {
        Long productId = productService.register(new ProductCreateRequest(
                "SKU-" + UUID.randomUUID(), "선점 테스트 상품", null, null, StorageType.ROOM_TEMPERATURE, 1_000, null, subCategoryId()
        )).productId();
        productService.increaseStock(productId, stock);
        return productId;
    }

    // 상품 하나를 1개 선점하고 주문번호를 돌려준다 - 서비스를 직접 호출하므로 지난 만료 시각도 넣을 수 있다
    private String reserve(Long productId, LocalDateTime expiresAt) {
        String orderNo = newOrderNo();
        stockReservationService.reserve(request(orderNo, expiresAt, item(productId, 1)));
        return orderNo;
    }

    private int stockOf(Long productId) {
        return productRepository.findById(productId)
                .map(Product::getStockQuantity)
                .orElseThrow();
    }

    private StockReservationStatus statusOf(String orderNo) {
        return stockReservationRepository.findByOrderNo(orderNo)
                .map(StockReservation::getStatus)
                .orElseThrow();
    }

    private static String newOrderNo() {
        return UUID.randomUUID().toString();
    }

    private static LocalDateTime inTenMinutes() {
        return LocalDateTime.now().plusMinutes(10);
    }

    private static StockReservationRequest request(String orderNo, LocalDateTime expiresAt, StockReservationRequest.Item... items) {
        return new StockReservationRequest(orderNo, expiresAt, List.of(items));
    }

    private static StockReservationRequest.Item item(Long productId, int quantity) {
        return new StockReservationRequest.Item(productId, quantity);
    }

    // 상품은 하위 카테고리에만 등록되므로 상위와 하위 카테고리를 함께 만든다
    private Long subCategoryId() {
        Category parent = categoryRepository.save(Category.builder().name("테스트 상위 카테고리").build());
        return categoryRepository.save(Category.builder().name("테스트 하위 카테고리").parentId(parent.getId()).build()).getId();
    }
}
