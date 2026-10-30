package com.park.ecommerce.coupon;

import com.park.ecommerce.coupon.CouponStockRepository.SlotAcquisition;
import com.park.ecommerce.coupon.domain.Coupon;
import com.park.ecommerce.coupon.domain.CouponRepository;
import com.park.ecommerce.coupon.domain.CouponType;
import com.park.ecommerce.coupon.domain.MemberCoupon;
import com.park.ecommerce.coupon.domain.MemberCouponRepository;
import com.park.ecommerce.exception.coupon.CouponErrorCode;
import com.park.ecommerce.exception.coupon.CouponException;
import com.park.ecommerce.promotion.DiscountType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// 발급 번호는 Redis가 나눠 주고 수량 한도는 DB 유니크 제약이 지키므로, 실제 Redis·MySQL에서 Redis 상태가 틀려도 초과 발급되지 않는지 컨테이너로 확인
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

    // Redis에서 떨어진 요청이 DB 처리까지 가지 않았는지 호출 횟수로 확인하고, DB 실패를 만들기 위해 spy로 둠
    @MockitoSpyBean
    private CouponService couponService;

    // Redis 장애와 키 유실을 만들기 위해 spy로 둠 - 스텁하지 않으면 실제 동작 그대로
    @MockitoSpyBean
    private CouponStockRepository couponStockRepository;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private MemberCouponRepository memberCouponRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

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
                .allSatisfy(error -> assertErrorCode(error, CouponErrorCode.COUPON_SOLD_OUT));
        verify(couponService, times(COUPON_QUANTITY)).issue(eq(couponId), anyLong(), any());
        assertThat(memberCouponRepository.countByCouponId(couponId)).isEqualTo(COUPON_QUANTITY);
        assertThat(remainingSlotsOf(couponId)).isZero();
    }

    @Test
    @DisplayName("이미 발급받은 회원의 중복 요청은 번호를 꺼내지 않고 이미 발급으로 실패하며, 발급 처리까지 가지 않는다")
    void rejectsIssuedMemberWithoutTakingSlot() throws InterruptedException {
        Long couponId = firstComeCoupon(COUPON_QUANTITY);
        Long memberId = 1L;
        couponIssueFacade.issue(couponId, memberId);

        List<Throwable> errors = runConcurrently(IntStream.range(0, 10)
                .<Runnable>mapToObj(i -> () -> couponIssueFacade.issue(couponId, memberId))
                .toList());

        assertThat(errors).hasSize(10)
                .allSatisfy(error -> assertErrorCode(error, CouponErrorCode.ALREADY_ISSUED));
        verify(couponService, times(1)).issue(eq(couponId), eq(memberId), any());
        assertThat(remainingSlotsOf(couponId)).isEqualTo(COUPON_QUANTITY - 1);
    }

    // 첫 요청이 커밋하기 전의 중복 요청은 Redis 기록이 틀린 것과 구분할 수 없어 번호를 하나 더 받을 수 있음 - 회원 유니크 제약이 막고 번호는 반납됨
    @Test
    @DisplayName("같은 회원이 동시에 여러 번 요청해도 1장만 발급되고, 잠시 더 받은 번호는 모두 반납된다")
    void issuesOnceForConcurrentDuplicates() throws InterruptedException {
        Long couponId = firstComeCoupon(COUPON_QUANTITY);
        Long memberId = 1L;

        List<Throwable> errors = runConcurrently(IntStream.range(0, 10)
                .<Runnable>mapToObj(i -> () -> couponIssueFacade.issue(couponId, memberId))
                .toList());

        assertThat(errors).hasSize(9)
                .allSatisfy(error -> assertErrorCode(error, CouponErrorCode.ALREADY_ISSUED));
        assertThat(memberCouponRepository.countByCouponId(couponId)).isEqualTo(1);
        assertThat(remainingSlotsOf(couponId)).isEqualTo(COUPON_QUANTITY - 1);
        assertThat(isRedisMember(couponId, memberId)).isTrue();
    }

    // Redis는 번호를 꺼내고 회원을 기록했는데 응답만 늦어 앱이 실패로 처리한 상황 - 앱은 번호를 몰라 되돌릴 수 없음
    @Test
    @DisplayName("Redis 응답을 받지 못해 회원 기록만 남아도, DB에 발급 기록이 없으면 재시도에서 발급된다")
    void issuesOnRetryWhenAcquireResponseLost() {
        Long couponId = firstComeCoupon(COUPON_QUANTITY);
        couponIssueFacade.issue(couponId, 2L); // 번호를 채워 둠
        Long memberId = 1L;
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new QueryTimeoutException("Redis 응답 시간 초과");
        }).doCallRealMethod().when(couponStockRepository).tryAcquire(couponId, memberId);

        assertThatThrownBy(() -> couponIssueFacade.issue(couponId, memberId))
                .satisfies(error -> assertErrorCode(error, CouponErrorCode.COUPON_ISSUE_UNAVAILABLE));
        assertThat(isRedisMember(couponId, memberId)).isTrue();

        couponIssueFacade.issue(couponId, memberId);

        assertThat(memberCouponRepository.existsByCouponIdAndMemberId(couponId, memberId)).isTrue();
        assertThat(memberCouponRepository.countByCouponId(couponId)).isEqualTo(2);
    }

    @Test
    @DisplayName("Redis에 번호가 없으면 DB에서 쓰이지 않은 번호로 채운 뒤 나눠 준다")
    void initializesSlotsFromUnusedNumbers() {
        Long couponId = firstComeCoupon(COUPON_QUANTITY);
        couponService.issue(couponId, 1L, 1); // Redis를 거치지 않고 DB에서 먼저 1번이 발급된 상태

        couponIssueFacade.issue(couponId, 2L);

        assertThat(memberCouponRepository.findSlotNosByCouponId(couponId)).containsExactlyInAnyOrder(1, 2);
        assertThat(remainingSlotsOf(couponId)).isEqualTo(COUPON_QUANTITY - 2);
    }

    // 슬롯 도입 전 발급 기록은 번호가 없어 유니크 제약에 걸리지 않으므로, 남은 번호 개수를 발급 수로 줄여야 함
    @Test
    @DisplayName("번호 없이 저장된 발급 기록도 발급 수에 포함해 남은 번호를 채운다")
    void countsIssuedWithoutSlotWhenInitializing() {
        Long couponId = firstComeCoupon(1);
        memberCouponRepository.save(MemberCoupon.builder()
                .coupon(couponRepository.findById(couponId).orElseThrow())
                .memberId(1L)
                .build());

        assertThatThrownBy(() -> couponIssueFacade.issue(couponId, 2L))
                .satisfies(error -> assertErrorCode(error, CouponErrorCode.COUPON_SOLD_OUT));
        assertThat(memberCouponRepository.countByCouponId(couponId)).isEqualTo(1);
    }

    // Facade의 기간 확인을 통과한 뒤 트랜잭션 안에서 발급 기간이 끝나는 경계 상황
    @Test
    @DisplayName("DB가 발급을 거절하면 번호를 반납하고 회원 기록을 지워 다시 신청할 수 있다")
    void cancelsSlotWhenDatabaseRejects() {
        Long couponId = firstComeCoupon(COUPON_QUANTITY);
        Long memberId = 1L;
        doThrow(new CouponException(CouponErrorCode.NOT_IN_ISSUE_PERIOD)).when(couponService).issue(eq(couponId), eq(memberId), any());

        assertThatThrownBy(() -> couponIssueFacade.issue(couponId, memberId))
                .satisfies(error -> assertErrorCode(error, CouponErrorCode.NOT_IN_ISSUE_PERIOD));
        assertThat(remainingSlotsOf(couponId)).isEqualTo(COUPON_QUANTITY);
        assertThat(isRedisMember(couponId, memberId)).isFalse();
    }

    @Test
    @DisplayName("커넥션을 얻지 못해 트랜잭션이 시작되지 않았으면 번호를 반납하고 회원 기록을 지운다")
    void cancelsSlotWhenTransactionNotStarted() {
        Long couponId = firstComeCoupon(COUPON_QUANTITY);
        Long memberId = 1L;
        doThrow(new CannotCreateTransactionException("커넥션 획득 실패")).when(couponService).issue(eq(couponId), eq(memberId), any());

        assertThatThrownBy(() -> couponIssueFacade.issue(couponId, memberId))
                .isInstanceOf(CannotCreateTransactionException.class);
        assertThat(remainingSlotsOf(couponId)).isEqualTo(COUPON_QUANTITY);
        assertThat(isRedisMember(couponId, memberId)).isFalse();
    }

    // 결과 불명이어도 번호를 반납 - 실제로 커밋됐다면 그 번호를 다시 받은 요청은 유니크 제약에 막혀 초과 발급되지 않음
    @Test
    @DisplayName("커밋됐는지 알 수 없는 실패 뒤 번호를 반납해도, 실제로 커밋됐다면 같은 번호의 다음 요청은 거부되고 1장만 발급된다")
    void returnsSlotWhenOutcomeUnknownWithoutOverIssue() {
        Long couponId = firstComeCoupon(1);
        // spy는 트랜잭션 프록시 안쪽에 있어, 커밋된 상태는 별도 트랜잭션으로 만들어야 아래 예외로 롤백되지 않음
        TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        doAnswer(invocation -> {
            requiresNew.executeWithoutResult(status -> memberCouponRepository.save(MemberCoupon.builder()
                    .coupon(couponRepository.findById(couponId).orElseThrow())
                    .memberId(1L)
                    .slotNo(invocation.getArgument(2))
                    .build()));
            throw new TransactionSystemException("커밋 응답 없음");
        }).when(couponService).issue(eq(couponId), eq(1L), any());

        assertThatThrownBy(() -> couponIssueFacade.issue(couponId, 1L))
                .isInstanceOf(TransactionSystemException.class);
        assertThat(isRedisMember(couponId, 1L)).isFalse();
        assertThatThrownBy(() -> couponIssueFacade.issue(couponId, 2L))
                .satisfies(error -> assertErrorCode(error, CouponErrorCode.COUPON_ISSUE_UNAVAILABLE));

        assertThat(memberCouponRepository.countByCouponId(couponId)).isEqualTo(1);
        assertThat(isRedisMember(couponId, 2L)).isFalse(); // 번호 탓에 실패했으므로 다시 신청할 수 있어야 함
        assertThat(remainingSlotsOf(couponId)).isZero(); // 이미 쓰인 번호는 반납하지 않음
    }

    // 키가 사라진 순간 번호를 받고 아직 커밋 전인 요청이 있으면, DB 기준 재초기화가 같은 번호를 다시 내줌
    @Test
    @DisplayName("번호를 받은 요청이 커밋하기 전에 Redis 키가 사라져 같은 번호가 다시 나가도 1장만 발급된다")
    void rejectsSameSlotReissuedAfterKeysLost() {
        Long couponId = firstComeCoupon(1);
        doAnswer(invocation -> {
            deleteSlotKeys(couponId);
            // 재초기화로 같은 번호를 받아 먼저 커밋 - 같은 스레드면 이 요청의 트랜잭션에 합류하므로 다른 스레드에서 실행
            CompletableFuture.runAsync(() -> couponIssueFacade.issue(couponId, 2L)).join();
            return invocation.callRealMethod();
        }).when(couponService).issue(eq(couponId), eq(1L), any());

        assertThatThrownBy(() -> couponIssueFacade.issue(couponId, 1L))
                .satisfies(error -> assertErrorCode(error, CouponErrorCode.COUPON_ISSUE_UNAVAILABLE));
        assertThat(memberCouponRepository.countByCouponId(couponId)).isEqualTo(1);
    }

    @Test
    @DisplayName("Redis가 이미 쓰인 번호가 담긴 오래된 상태로 돌아와도 수량을 넘겨 발급하지 않는다")
    void rejectsUsedSlotsFromStaleSnapshot() {
        int quantity = 3;
        Long couponId = firstComeCoupon(quantity);
        IntStream.rangeClosed(1, quantity).forEach(memberId -> couponIssueFacade.issue(couponId, (long) memberId));
        // 발급 전 스냅샷으로 복구된 상태 - 번호는 처음 그대로, 받은 회원은 없음
        redisTemplate.opsForList().rightPushAll(slotsKey(couponId), "1", "2", "3");
        redisTemplate.delete("coupon:members:" + couponId);

        IntStream.rangeClosed(1, quantity).forEach(i -> assertThatThrownBy(() -> couponIssueFacade.issue(couponId, 100L + i))
                .satisfies(error -> assertErrorCode(error, CouponErrorCode.COUPON_ISSUE_UNAVAILABLE)));

        assertThat(memberCouponRepository.countByCouponId(couponId)).isEqualTo(quantity);
        assertThat(remainingSlotsOf(couponId)).isZero();
    }

    // 회원 기록까지 사라지면 이미 받은 회원이 번호를 잠시 붙잡아 다른 요청이 소진으로 실패할 수 있음 - 덜 발급은 허용, 초과와 번호 유실은 불가
    @Test
    @DisplayName("발급 도중 Redis 키와 회원 기록이 사라져도 DB 기준으로 다시 채워, 초과 발급 없이 번호를 잃지 않는다")
    void neverOverIssuesWhenKeysLostDuringTraffic() throws InterruptedException {
        Long couponId = firstComeCoupon(COUPON_QUANTITY);
        IntStream.rangeClosed(1, 100).forEach(memberId -> couponIssueFacade.issue(couponId, (long) memberId));
        deleteSlotKeys(couponId);
        redisTemplate.delete("coupon:members:" + couponId);

        List<Throwable> errors = runConcurrently(IntStream.rangeClosed(1, 1000)
                .<Runnable>mapToObj(memberId -> () -> couponIssueFacade.issue(couponId, (long) memberId))
                .toList());

        long issued = memberCouponRepository.countByCouponId(couponId);
        assertThat(errors).allSatisfy(error -> assertThat(error)
                .isInstanceOf(CouponException.class)
                .extracting("errorCode")
                .isIn(CouponErrorCode.COUPON_SOLD_OUT, CouponErrorCode.ALREADY_ISSUED));
        assertThat(issued).isLessThanOrEqualTo(COUPON_QUANTITY);
        assertThat(memberCouponRepository.findSlotNosByCouponId(couponId)).doesNotHaveDuplicates();
        assertThat(issued + remainingSlotsOf(couponId)).isEqualTo(COUPON_QUANTITY);
    }

    @Test
    @DisplayName("Redis 장애 시 선착순 쿠폰은 발급을 거부하고, 기본 쿠폰은 Redis를 거치지 않고 발급한다")
    void rejectsOnlyFirstComeWhenRedisFails() {
        Long firstComeId = firstComeCoupon(COUPON_QUANTITY);
        LocalDateTime now = LocalDateTime.now();
        Long normalId = couponRepository.save(coupon(CouponType.NORMAL, null, now.minusHours(1), now.plusHours(1))).getId();
        doThrow(new RedisConnectionFailureException("Redis 연결 실패")).when(couponStockRepository).tryAcquire(anyLong(), anyLong());

        assertThatThrownBy(() -> couponIssueFacade.issue(firstComeId, 1L))
                .satisfies(error -> assertErrorCode(error, CouponErrorCode.COUPON_ISSUE_UNAVAILABLE));
        couponIssueFacade.issue(normalId, 1L);

        assertThat(memberCouponRepository.countByCouponId(firstComeId)).isZero();
        assertThat(memberCouponRepository.countByCouponId(normalId)).isEqualTo(1);
    }

    @Test
    @DisplayName("번호를 채운 직후에도 Redis에 키가 없으면 DB로 넘기지 않고 발급을 거부한다")
    void rejectsWhenSlotsMissingAfterInitialize() {
        Long couponId = firstComeCoupon(COUPON_QUANTITY);
        doReturn(SlotAcquisition.NOT_INITIALIZED).when(couponStockRepository).tryAcquire(anyLong(), anyLong());

        assertThatThrownBy(() -> couponIssueFacade.issue(couponId, 1L))
                .satisfies(error -> assertErrorCode(error, CouponErrorCode.COUPON_ISSUE_UNAVAILABLE));
        verify(couponService, never()).issue(anyLong(), anyLong(), any());
    }

    // 오픈 전 요청이 번호를 붙잡은 채 DB를 기다리면, 오픈 직후 정상 요청이 소진으로 실패할 수 있음
    @Test
    @DisplayName("발급 기간이 아닌 요청은 Redis에서 번호를 받지 않고 DB까지 가지 않는다")
    void rejectsOutOfPeriodBeforeStockGate() {
        LocalDateTime now = LocalDateTime.now();
        Long couponId = couponRepository.save(coupon(CouponType.FIRST_COME, COUPON_QUANTITY, now.plusHours(1), now.plusHours(2))).getId();

        assertThatThrownBy(() -> couponIssueFacade.issue(couponId, 1L))
                .satisfies(error -> assertErrorCode(error, CouponErrorCode.NOT_IN_ISSUE_PERIOD));
        verify(couponStockRepository, never()).tryAcquire(anyLong(), anyLong());
        verify(couponService, never()).issue(anyLong(), anyLong(), any());
    }

    private static void assertErrorCode(Throwable error, CouponErrorCode errorCode) {
        assertThat(error)
                .isInstanceOf(CouponException.class)
                .extracting("errorCode")
                .isEqualTo(errorCode);
    }

    private Long firstComeCoupon(int quantity) {
        LocalDateTime now = LocalDateTime.now();
        return couponRepository.save(coupon(CouponType.FIRST_COME, quantity, now.minusHours(1), now.plusHours(1))).getId();
    }

    private static Coupon coupon(CouponType type, Integer quantity, LocalDateTime issueStartAt, LocalDateTime issueEndAt) {
        return Coupon.builder()
                .name("테스트 쿠폰")
                .discountType(DiscountType.AMOUNT)
                .discountValue(1_000)
                .type(type)
                .quantity(quantity)
                .issueStartAt(issueStartAt)
                .issueEndAt(issueEndAt)
                .useStartAt(issueStartAt)
                .useEndAt(issueEndAt.plusDays(1))
                .build();
    }

    private static String slotsKey(Long couponId) {
        return "coupon:slots:" + couponId;
    }

    private long remainingSlotsOf(Long couponId) {
        Long size = redisTemplate.opsForList().size(slotsKey(couponId));
        return size == null ? 0 : size;
    }

    // Redis 재시작이나 키 유실 - 받은 회원 기록은 남아 있을 수 있어 그대로 둠
    private void deleteSlotKeys(Long couponId) {
        redisTemplate.delete(List.of("coupon:init:" + couponId, slotsKey(couponId)));
    }

    private boolean isRedisMember(Long couponId, Long memberId) {
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember("coupon:members:" + couponId, String.valueOf(memberId)));
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
