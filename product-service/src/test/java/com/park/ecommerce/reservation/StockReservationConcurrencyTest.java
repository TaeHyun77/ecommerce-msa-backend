package com.park.ecommerce.reservation;

import com.park.ecommerce.category.Category;
import com.park.ecommerce.category.CategoryRepository;
import com.park.ecommerce.exception.product.ProductErrorCode;
import com.park.ecommerce.exception.product.ProductException;
import com.park.ecommerce.product.Product;
import com.park.ecommerce.product.ProductRepository;
import com.park.ecommerce.product.ProductService;
import com.park.ecommerce.product.dto.ProductCreateRequest;
import com.park.ecommerce.product.status.StorageType;
import com.park.ecommerce.reservation.domain.StockReservationRepository;
import com.park.ecommerce.reservation.domain.StockReservationStatus;
import com.park.ecommerce.reservation.dto.StockReservationRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

// 조건부 UPDATE가 동시 요청에서도 재고를 지키는지는 실제 DB에서만 의미 있게 검증되므로 MySQL 컨테이너로 확인
@Testcontainers
@SpringBootTest(properties = { // 테스트 중 스케줄러 개입 방지
        "stock.reservation.expire-poll-delay=1h",
        "outbox.relay.poll-delay=1h"
})
class StockReservationConcurrencyTest {
    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.0");

    @Autowired
    private StockReservationService stockReservationService;

    @Autowired
    private StockReservationFacade stockReservationFacade;

    @Autowired
    private StockReservationRepository stockReservationRepository;

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("재고보다 많은 선점이 동시에 들어와도 재고만큼만 성공하고 재고는 음수가 되지 않는다")
    void reservesOnlyAvailableStockConcurrently() throws InterruptedException {
        Long productId = productWithStock(5);

        List<Throwable> errors = runConcurrently(IntStream.range(0, 10)
                .<Runnable>mapToObj(i -> () -> stockReservationService.reserve(request(UUID.randomUUID().toString(), productId)))
                .toList());

        assertThat(errors).hasSize(5)
                .allSatisfy(error -> assertThat(error)
                        .isInstanceOf(ProductException.class)
                        .extracting("errorCode")
                        .isEqualTo(ProductErrorCode.INSUFFICIENT_STOCK));
        assertThat(stockOf(productId)).isZero();
    }

    @Test
    @DisplayName("같은 주문번호의 선점이 동시에 들어와도 모두 성공하고 재고는 한 번만 줄어든다 - 원래 요청과 재시도가 겹친 경우")
    void reservesOnceForSameOrderNoConcurrently() throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            // 재고를 주문 수량과 같게 두어, 늦은 요청이 재고 부족으로 판정되면 실패하도록 함
            Long productId = productWithStock(1);
            String orderNo = UUID.randomUUID().toString();

            List<Throwable> errors = runConcurrently(List.of(
                    () -> stockReservationFacade.reserve(request(orderNo, productId)),
                    () -> stockReservationFacade.reserve(request(orderNo, productId))
            ));

            assertThat(errors).isEmpty();
            assertThat(stockOf(productId)).isZero();
            assertThat(statusOf(orderNo)).isEqualTo(StockReservationStatus.RESERVED);
        }
    }

    @Test
    @DisplayName("만료 해제와 명시적 해제가 동시에 실행돼도 재고는 한 번만 복구된다")
    void restoresOnceWhenExpireAndReleaseRace() throws InterruptedException {
        Long productId = productWithStock(10);
        String orderNo = UUID.randomUUID().toString();
        stockReservationService.reserve(new StockReservationRequest(
                orderNo, LocalDateTime.now().minusSeconds(1), List.of(new StockReservationRequest.Item(productId, 3))
        ));
        Long reservationId = stockReservationRepository.findByOrderNo(orderNo).orElseThrow().getId();

        List<Throwable> errors = runConcurrently(List.of(
                () -> stockReservationService.expire(reservationId),
                () -> stockReservationService.release(orderNo)
        ));

        assertThat(errors).isEmpty();
        assertThat(stockOf(productId)).isEqualTo(10);
    }

    @Test
    @DisplayName("확정과 만료 해제가 같은 선점에 동시에 실행되면 한쪽만 반영된다 - 만료 시각 경계에서 결제 승인과 만료 스케줄러가 겹친 경우")
    void appliesOnlyOneWhenConfirmAndExpireRace() throws InterruptedException {
        Long productId = productWithStock(100);

        for (int i = 0; i < 50; i++) {
            String orderNo = UUID.randomUUID().toString();
            LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(10);
            stockReservationService.reserve(new StockReservationRequest(
                    orderNo, expiresAt, List.of(new StockReservationRequest.Item(productId, 1))
            ));
            Long reservationId = stockReservationRepository.findByOrderNo(orderNo).orElseThrow().getId();
            AtomicInteger confirmed = new AtomicInteger();
            AtomicInteger expired = new AtomicInteger();

            // 두 쪽에 서로 다른 시각을 넘겨 시간 조건은 모두 참으로 만든다 - 행 잠금과 상태 조건만으로 승패가 갈리는지 확인하기 위해
            List<Throwable> errors = runConcurrently(List.of(
                    () -> confirmed.set(transactionTemplate.execute(status ->
                            stockReservationRepository.confirm(orderNo, expiresAt.minusSeconds(1)))),
                    () -> expired.set(transactionTemplate.execute(status ->
                            stockReservationRepository.expire(reservationId, expiresAt.plusSeconds(1))))
            ));

            assertThat(errors).isEmpty();
            assertThat(confirmed.get() + expired.get()).isEqualTo(1);
            assertThat(statusOf(orderNo)).isEqualTo(confirmed.get() == 1
                    ? StockReservationStatus.CONFIRMED
                    : StockReservationStatus.RELEASED);
        }
    }

    private Long productWithStock(int stock) {
        Long productId = productService.register(new ProductCreateRequest(
                "SKU-" + UUID.randomUUID(), "동시성 테스트 상품", null, null, StorageType.ROOM_TEMPERATURE, 1_000, null, subCategoryId()
        )).productId();
        productService.increaseStock(productId, stock);
        return productId;
    }

    private StockReservationStatus statusOf(String orderNo) {
        return stockReservationRepository.findByOrderNo(orderNo).orElseThrow().getStatus();
    }

    private int stockOf(Long productId) {
        return productRepository.findById(productId)
                .map(Product::getStockQuantity)
                .orElseThrow();
    }

    private static StockReservationRequest request(String orderNo, Long productId) {
        return new StockReservationRequest(
                orderNo, LocalDateTime.now().plusMinutes(10), List.of(new StockReservationRequest.Item(productId, 1))
        );
    }

    // 작업들을 최대한 같은 순간에 시작시키고, 발생한 예외를 모아 돌려준다
    private static List<Throwable> runConcurrently(List<Runnable> tasks) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(tasks.size());
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (Runnable task : tasks) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    task.run();
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        return errors;
    }

    // 상품은 하위 카테고리에만 등록되므로 상위와 하위 카테고리를 함께 만든다
    private Long subCategoryId() {
        Category parent = categoryRepository.save(Category.builder().name("테스트 상위 카테고리").build());
        return categoryRepository.save(Category.builder().name("테스트 하위 카테고리").parentId(parent.getId()).build()).getId();
    }
}
