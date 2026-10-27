package com.park.ecommerce.coupon;

import com.park.ecommerce.coupon.domain.Coupon;
import com.park.ecommerce.coupon.domain.CouponRepository;
import com.park.ecommerce.coupon.domain.CouponType;
import com.park.ecommerce.coupon.domain.MemberCouponRepository;
import com.park.ecommerce.exception.coupon.CouponErrorCode;
import com.park.ecommerce.exception.coupon.CouponException;
import com.park.ecommerce.promotion.DiscountType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// Redis 선차감과 DB 조건부 UPDATE가 함께 수량을 지키는지는 실제 Redis·MySQL에서만 의미 있게 검증되므로 컨테이너로 확인
@Testcontainers
@SpringBootTest
class CouponIssueFacadeTest {
    private static final int COUPON_QUANTITY = 500;

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.0");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.2")).withExposedPorts(6379);

    @Autowired
    private CouponIssueFacade couponIssueFacade;

    // Redis에서 떨어진 요청이 DB 처리까지 가지 않았는지 호출 횟수로 확인하기 위해 spy로 둠
    @MockitoSpyBean
    private CouponIssueService couponIssueService;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private MemberCouponRepository memberCouponRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    @DisplayName("수량보다 많은 회원이 동시에 발급하면 수량만큼만 DB까지 가서 발급되고, 나머지는 Redis에서 소진으로 실패한다")
    void rejectsSoldOutRequestsBeforeDatabase() throws InterruptedException {
        Long couponId = firstComeCoupon(COUPON_QUANTITY);
        AtomicInteger issued = new AtomicInteger();

        List<Throwable> errors = runConcurrently(IntStream.rangeClosed(1, 1000)
                .<Runnable>mapToObj(memberId -> () -> {
                    couponIssueFacade.issue(couponId, (long) memberId);
                    issued.incrementAndGet();
                })
                .toList());

        assertThat(issued.get()).isEqualTo(COUPON_QUANTITY);
        assertThat(errors).hasSize(1000 - COUPON_QUANTITY)
                .allSatisfy(error -> assertSoldOut(error));
        verify(couponIssueService, times(COUPON_QUANTITY)).issue(eq(couponId), anyLong());
        assertThat(dbRemainingOf(couponId)).isZero();
        assertThat(redisRemainingOf(couponId)).isEqualTo("0");
        assertThat(memberCouponRepository.countByCouponId(couponId)).isEqualTo(COUPON_QUANTITY);
    }

    @Test
    @DisplayName("같은 회원의 중복 요청은 DB에서 이미 발급으로 실패하고, Redis에서 차감한 수량은 복구된다")
    void restoresRedisStockWhenAlreadyIssued() throws InterruptedException {
        Long couponId = firstComeCoupon(COUPON_QUANTITY);
        Long memberId = 1L;

        List<Throwable> errors = runConcurrently(IntStream.range(0, 10)
                .<Runnable>mapToObj(i -> () -> couponIssueFacade.issue(couponId, memberId))
                .toList());

        assertThat(errors).hasSize(9)
                .allSatisfy(error -> assertThat(error)
                        .isInstanceOf(CouponException.class)
                        .extracting("errorCode")
                        .isEqualTo(CouponErrorCode.ALREADY_ISSUED));
        assertThat(dbRemainingOf(couponId)).isEqualTo(COUPON_QUANTITY - 1);
        assertThat(redisRemainingOf(couponId)).isEqualTo(String.valueOf(COUPON_QUANTITY - 1));
    }

    @Test
    @DisplayName("Redis에 수량이 없으면 DB의 남은 수량으로 채운 뒤 차감한다 - Redis 재시작이나 만료 뒤에도 DB 기준으로 맞춰짐")
    void initializesRedisStockFromDatabaseRemaining() {
        Long couponId = firstComeCoupon(COUPON_QUANTITY);
        couponIssueService.issue(couponId, 1L); // Redis를 거치지 않고 DB에서 먼저 1장 발급된 상태

        couponIssueFacade.issue(couponId, 2L);

        assertThat(dbRemainingOf(couponId)).isEqualTo(COUPON_QUANTITY - 2);
        assertThat(redisRemainingOf(couponId)).isEqualTo(String.valueOf(COUPON_QUANTITY - 2));
    }

    @Test
    @DisplayName("Redis 수량이 실제보다 많아도 DB 조건부 UPDATE가 초과 발급을 막고, Redis를 소진으로 맞춘다")
    void markSoldOutWhenRedisHasMoreThanDatabase() {
        Long couponId = firstComeCoupon(1);
        couponIssueService.issue(couponId, 1L); // DB 수량 0
        redisTemplate.opsForValue().set(stockKey(couponId), "5");

        assertThatThrownBy(() -> couponIssueFacade.issue(couponId, 2L))
                .satisfies(CouponIssueFacadeTest::assertSoldOut);
        assertThat(memberCouponRepository.countByCouponId(couponId)).isEqualTo(1);
        assertThat(redisRemainingOf(couponId)).isEqualTo("0");
    }

    private static void assertSoldOut(Throwable error) {
        assertThat(error)
                .isInstanceOf(CouponException.class)
                .extracting("errorCode")
                .isEqualTo(CouponErrorCode.COUPON_SOLD_OUT);
    }

    private Long firstComeCoupon(int quantity) {
        LocalDateTime now = LocalDateTime.now();
        return couponRepository.save(Coupon.builder()
                .name("선착순 테스트 쿠폰")
                .discountType(DiscountType.AMOUNT)
                .discountValue(1_000)
                .type(CouponType.FIRST_COME)
                .quantity(quantity)
                .issueStartAt(now.minusHours(1))
                .issueEndAt(now.plusHours(1))
                .useStartAt(now.minusHours(1))
                .useEndAt(now.plusDays(1))
                .build()).getId();
    }

    private int dbRemainingOf(Long couponId) {
        return couponRepository.findById(couponId)
                .map(Coupon::getQuantity)
                .orElseThrow();
    }

    private String redisRemainingOf(Long couponId) {
        return redisTemplate.opsForValue().get(stockKey(couponId));
    }

    private static String stockKey(Long couponId) {
        return "coupon:stock:" + couponId;
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
        assertThat(done.await(1, TimeUnit.MINUTES)).isTrue();
        executor.shutdown();
        return errors;
    }
}
