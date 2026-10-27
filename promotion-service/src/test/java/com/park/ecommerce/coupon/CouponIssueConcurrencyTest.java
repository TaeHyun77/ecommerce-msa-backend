package com.park.ecommerce.coupon;

import com.park.ecommerce.coupon.domain.Coupon;
import com.park.ecommerce.coupon.domain.CouponRepository;
import com.park.ecommerce.coupon.domain.CouponType;
import com.park.ecommerce.coupon.domain.MemberCouponRepository;
import com.park.ecommerce.exception.coupon.CouponErrorCode;
import com.park.ecommerce.exception.coupon.CouponException;
import com.park.ecommerce.promotion.DiscountType;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.IMetricsTracker;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;

// 조건부 UPDATE가 동시 발급에서도 수량을 지키는지는 실제 DB에서만 의미 있게 검증되므로 MySQL 컨테이너로 확인
// 지연 시간은 HTTP·Tomcat 스레드풀이 빠진 서비스 호출 기준이라 규모별 상대 비교용으로만 기록
@Slf4j
@Testcontainers
@SpringBootTest
class CouponIssueConcurrencyTest {
    private static final int COUPON_QUANTITY = 500;

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.0");

    @Autowired
    private CouponIssueService couponIssueService;

    @Autowired
    private CouponRepository couponRepository;

    // 사전 중복 확인을 우회시켜 유니크 제약 경로를 결정적으로 검증하기 위해 spy로 둠 - 스텁하지 않으면 실제 동작 그대로
    @MockitoSpyBean
    private MemberCouponRepository memberCouponRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    private static final ConnectionPoolRecorder POOL_RECORDER = new ConnectionPoolRecorder();

    // 컨텍스트가 테스트 간에 재사용되므로 처음 한 번만 등록 - Hikari는 트래커를 한 번만 설정할 수 있음
    @BeforeEach
    void registerPoolRecorder() throws SQLException {
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        if (hikari.getMetricsTrackerFactory() == null) {
            hikari.setMetricsTrackerFactory((poolName, poolStats) -> POOL_RECORDER);
        }
    }

    // 요청마다 스레드를 하나씩 띄우므로 macOS 프로세스당 스레드 한도(4,096개) 안에서만 실행 가능 - 그 이상은 HTTP 부하 테스트로 측정
    @ParameterizedTest(name = "{0}명 동시 발급")
    @ValueSource(ints = {1000, 3000})
    @DisplayName("수량보다 많은 회원이 동시에 발급해도 정확히 수량만큼만 발급되고, 나머지는 모두 소진으로 실패한다")
    void issuesExactlyQuantityConcurrently(int memberCount) throws InterruptedException, SQLException {
        Long couponId = firstComeCoupon(COUPON_QUANTITY);
        Map<String, Long> lockBefore = rowLockStatus();
        POOL_RECORDER.reset();

        RunResult result = runConcurrently(IntStream.rangeClosed(1, memberCount)
                .<Runnable>mapToObj(memberId -> () -> couponIssueService.issue(couponId, (long) memberId))
                .toList());

        // 이후 조회 쿼리도 커넥션을 빌리므로 실행 직후의 기록만 떼어 둔다
        PoolRecord poolRecord = POOL_RECORDER.snapshot();
        logResult(memberCount, result, lockBefore, rowLockStatus());
        logPool(poolRecord, dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize());

        // 소진 외의 실패(커넥션 타임아웃, 데드락 등)가 섞이면 수량이 맞아도 정상이 아니므로 실패 원인까지 확인
        assertThat(result.completed()).isTrue();
        assertThat(result.successLatencies()).hasSize(COUPON_QUANTITY);
        assertThat(result.errors()).hasSize(memberCount - COUPON_QUANTITY)
                .allSatisfy(error -> assertThat(error)
                        .isInstanceOf(CouponException.class)
                        .extracting("errorCode")
                        .isEqualTo(CouponErrorCode.COUPON_SOLD_OUT));
        assertThat(remainingQuantityOf(couponId)).isZero();
        assertThat(memberCouponRepository.countByCouponId(couponId)).isEqualTo(COUPON_QUANTITY);
    }

    @Test
    @DisplayName("같은 회원이 동시에 여러 번 발급해도 1장만 발급되고, 실패한 요청의 차감은 롤백된다")
    void issuesOncePerMemberConcurrently() throws InterruptedException {
        Long couponId = firstComeCoupon(COUPON_QUANTITY);
        Long memberId = 1L;

        RunResult result = runConcurrently(IntStream.range(0, 10)
                .<Runnable>mapToObj(i -> () -> couponIssueService.issue(couponId, memberId))
                .toList());

        assertThat(result.completed()).isTrue();
        assertThat(result.successLatencies()).hasSize(1);
        assertThat(result.errors()).hasSize(9)
                .allSatisfy(error -> assertThat(error)
                        .isInstanceOf(CouponException.class)
                        .extracting("errorCode")
                        .isEqualTo(CouponErrorCode.ALREADY_ISSUED));
        assertThat(remainingQuantityOf(couponId)).isEqualTo(COUPON_QUANTITY - 1);
        assertThat(memberCouponRepository.countByCouponId(couponId)).isEqualTo(1);
    }

    // 위 동시 요청 테스트는 타이밍에 따라 사전 확인에서만 걸러져도 통과하므로, 유니크 제약 경로는 따로 고정해서 확인
    @Test
    @DisplayName("사전 확인을 함께 통과한 중복 요청은 유니크 제약으로 이미 발급 처리되고, 차감한 수량은 롤백된다")
    void rollsBackDecreaseWhenUniqueConstraintViolated() {
        Long couponId = firstComeCoupon(COUPON_QUANTITY);
        Long memberId = 1L;
        couponIssueService.issue(couponId, memberId);
        doReturn(false).when(memberCouponRepository).existsByCouponIdAndMemberId(couponId, memberId);

        assertThatThrownBy(() -> couponIssueService.issue(couponId, memberId))
                .isInstanceOf(CouponException.class)
                .extracting("errorCode")
                .isEqualTo(CouponErrorCode.ALREADY_ISSUED);
        assertThat(remainingQuantityOf(couponId)).isEqualTo(COUPON_QUANTITY - 1);
        assertThat(memberCouponRepository.countByCouponId(couponId)).isEqualTo(1);
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

    private int remainingQuantityOf(Long couponId) {
        return couponRepository.findById(couponId)
                .map(Coupon::getQuantity)
                .orElseThrow();
    }

    // 누적값이라 실행 전후 차이로 이번 실행의 행 잠금 대기를 구함
    private Map<String, Long> rowLockStatus() {
        return jdbcTemplate.query("SHOW GLOBAL STATUS LIKE 'Innodb_row_lock%'",
                        (rs, rowNum) -> Map.entry(rs.getString(1), rs.getLong(2)))
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static void logResult(int memberCount, RunResult result, Map<String, Long> lockBefore, Map<String, Long> lockAfter) {
        Map<String, Long> errorCounts = result.errors().stream()
                .collect(Collectors.groupingBy(CouponIssueConcurrencyTest::errorName, Collectors.counting()));
        List<Long> allLatencies = new ArrayList<>(result.successLatencies());
        allLatencies.addAll(result.failureLatencies());

        log.info("[선착순 발급] 동시 요청 {}명, 전체 소요 {}ms, 처리량 {}건/s, 실패 원인 {}",
                memberCount, result.elapsedMillis(), memberCount * 1000L / Math.max(result.elapsedMillis(), 1), errorCounts);
        log.info("[선착순 발급] 전체 지연 {}", latencySummary(allLatencies));
        log.info("[선착순 발급] 성공 지연 {}", latencySummary(result.successLatencies()));
        log.info("[선착순 발급] 실패 지연 {}", latencySummary(result.failureLatencies()));
        // Innodb_row_lock_time은 ms 단위 누적, Innodb_row_lock_time_max는 서버 시작 이후 최댓값이라 차이를 내지 않음
        log.info("[선착순 발급] 행 잠금 대기 횟수 {}, 대기 시간 합계 {}ms, 최대 대기 {}ms",
                lockAfter.get("Innodb_row_lock_waits") - lockBefore.get("Innodb_row_lock_waits"),
                lockAfter.get("Innodb_row_lock_time") - lockBefore.get("Innodb_row_lock_time"),
                lockAfter.get("Innodb_row_lock_time_max"));
    }

    private static void logPool(PoolRecord poolRecord, int maximumPoolSize) {
        log.info("[선착순 발급] 커넥션 풀 크기 {}, 획득 타임아웃 {}건", maximumPoolSize, poolRecord.timeouts());
        log.info("[선착순 발급] 커넥션 대기 {}", latencySummary(poolRecord.acquireNanos()));
        // 커넥션을 빌린 뒤 반납까지 - 트랜잭션 실행 시간이며 쿠폰 행 잠금 대기도 여기에 포함됨
        log.info("[선착순 발급] 커넥션 점유 {}", latencySummary(poolRecord.usageNanos()));
    }

    private static String errorName(Throwable error) {
        return error instanceof CouponException couponException
                ? couponException.getErrorCode().name()
                : error.getClass().getSimpleName();
    }

    private static String latencySummary(List<Long> latencyNanos) {
        if (latencyNanos.isEmpty()) return "없음";
        List<Long> sorted = latencyNanos.stream().sorted().toList();
        Function<Double, Long> percentileMillis = p ->
                TimeUnit.NANOSECONDS.toMillis(sorted.get((int) Math.ceil(p / 100 * sorted.size()) - 1));
        return String.format("p50=%dms, p95=%dms, p99=%dms, max=%dms (%d건)",
                percentileMillis.apply(50.0), percentileMillis.apply(95.0), percentileMillis.apply(99.0),
                TimeUnit.NANOSECONDS.toMillis(sorted.get(sorted.size() - 1)), sorted.size());
    }

    // 작업들을 최대한 같은 순간에 시작시키고, 작업별 지연 시간(커넥션 대기 포함)과 예외를 모아 돌려준다
    private static RunResult runConcurrently(List<Runnable> tasks) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(tasks.size());
        List<Long> successLatencies = Collections.synchronizedList(new ArrayList<>());
        List<Long> failureLatencies = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (Runnable task : tasks) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    long startedAt = System.nanoTime();
                    try {
                        task.run();
                        successLatencies.add(System.nanoTime() - startedAt);
                    } catch (Throwable e) {
                        failureLatencies.add(System.nanoTime() - startedAt);
                        errors.add(e);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        long startedAt = System.nanoTime();
        start.countDown();
        // 시간 안에 끝나지 않은 작업이 있으면 결과 집계가 불완전하므로 완료 여부를 함께 돌려준다
        boolean completed = done.await(2, TimeUnit.MINUTES);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        executor.shutdownNow();
        return new RunResult(completed, elapsedMillis, List.copyOf(successLatencies), List.copyOf(failureLatencies), List.copyOf(errors));
    }

    private record RunResult(
            boolean completed, long elapsedMillis,
            List<Long> successLatencies, List<Long> failureLatencies, List<Throwable> errors
    ) {
    }

    // Hikari가 커넥션을 빌려줄 때마다 알려 주는 대기·점유 시간을 모은다 - 서비스 코드를 건드리지 않고 커넥션 대기를 분리해 보기 위함
    private static class ConnectionPoolRecorder implements IMetricsTracker {
        private final List<Long> acquireNanos = Collections.synchronizedList(new ArrayList<>());
        private final List<Long> usageNanos = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger timeouts = new AtomicInteger();

        @Override
        public void recordConnectionAcquiredNanos(long elapsedAcquiredNanos) {
            acquireNanos.add(elapsedAcquiredNanos);
        }

        @Override
        public void recordConnectionUsageMillis(long elapsedBorrowedMillis) {
            usageNanos.add(TimeUnit.MILLISECONDS.toNanos(elapsedBorrowedMillis)); // Hikari가 ms 단위로만 알려 줌
        }

        @Override
        public void recordConnectionTimeout() {
            timeouts.incrementAndGet();
        }

        void reset() {
            acquireNanos.clear();
            usageNanos.clear();
            timeouts.set(0);
        }

        PoolRecord snapshot() {
            synchronized (acquireNanos) {
                synchronized (usageNanos) {
                    return new PoolRecord(List.copyOf(acquireNanos), List.copyOf(usageNanos), timeouts.get());
                }
            }
        }
    }

    private record PoolRecord(List<Long> acquireNanos, List<Long> usageNanos, int timeouts) {
    }
}
