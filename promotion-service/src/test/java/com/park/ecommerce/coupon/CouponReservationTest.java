package com.park.ecommerce.coupon;

import com.park.ecommerce.coupon.domain.Coupon;
import com.park.ecommerce.coupon.domain.CouponRepository;
import com.park.ecommerce.coupon.domain.CouponType;
import com.park.ecommerce.coupon.domain.MemberCoupon;
import com.park.ecommerce.coupon.domain.MemberCouponRepository;
import com.park.ecommerce.coupon.domain.MemberCouponStatus;
import com.park.ecommerce.coupon.dto.CouponReserveRequest;
import com.park.ecommerce.exception.coupon.CouponErrorCode;
import com.park.ecommerce.exception.coupon.CouponException;
import com.park.ecommerce.promotion.DiscountType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 선점·확정·해제는 조건부 UPDATE로 경쟁을 처리하므로 실제 MySQL에서 확인
@Testcontainers
@SpringBootTest
class CouponReservationTest {
    private static final int ORDER_AMOUNT = 10_000;

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.0");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.2")).withExposedPorts(6379);

    @Autowired
    private CouponService couponService;

    @Autowired
    private CouponReservationFacade couponReservationFacade;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private MemberCouponRepository memberCouponRepository;

    @Autowired
    private CouponReservationExpiryScheduler expiryScheduler;

    @Test
    @DisplayName("선점하면 주문에 묶이고 할인액을 응답한다")
    void reserve() {
        MemberCoupon memberCoupon = issue(saveCoupon(DiscountType.AMOUNT, 1_000, LocalDateTime.now().minusDays(1)), 1L);
        String orderNo = newOrderNo();

        int discountAmount = couponService.reserve(request(memberCoupon, 1L, orderNo)).discountAmount();

        MemberCoupon reserved = memberCouponRepository.findById(memberCoupon.getId()).orElseThrow();
        assertThat(discountAmount).isEqualTo(1_000);
        assertThat(reserved.getStatus()).isEqualTo(MemberCouponStatus.RESERVED);
        assertThat(reserved.getOrderNo()).isEqualTo(orderNo);
        assertThat(reserved.getReservedUntil()).isNotNull();
    }

    @Test
    @DisplayName("할인액이 주문 금액과 같아 0원 결제가 되는 쿠폰도 선점된다")
    void reserve_fullDiscount() {
        MemberCoupon memberCoupon = issue(saveCoupon(DiscountType.PERCENT, 100, LocalDateTime.now().minusDays(1)), 1L);

        int discountAmount = couponService.reserve(request(memberCoupon, 1L, newOrderNo())).discountAmount();

        assertThat(discountAmount).isEqualTo(ORDER_AMOUNT);
    }

    @Test
    @DisplayName("다른 회원의 쿠폰은 없는 쿠폰과 같은 404로 거절한다")
    void reserve_otherMember() {
        MemberCoupon memberCoupon = issue(saveCoupon(DiscountType.AMOUNT, 1_000, LocalDateTime.now().minusDays(1)), 1L);

        assertErrorCode(() -> couponService.reserve(request(memberCoupon, 2L, newOrderNo())), CouponErrorCode.MEMBER_COUPON_NOT_FOUND);
    }

    @Test
    @DisplayName("사용 기간 전인 쿠폰은 선점하지 않는다")
    void reserve_notInUsePeriod() {
        MemberCoupon memberCoupon = issue(saveCoupon(DiscountType.AMOUNT, 1_000, LocalDateTime.now().plusDays(1)), 1L);

        assertErrorCode(() -> couponService.reserve(request(memberCoupon, 1L, newOrderNo())), CouponErrorCode.NOT_IN_USE_PERIOD);
    }

    @Test
    @DisplayName("다른 주문에 묶인 쿠폰은 선점하지 않는다")
    void reserve_alreadyReserved() {
        MemberCoupon memberCoupon = issue(saveCoupon(DiscountType.AMOUNT, 1_000, LocalDateTime.now().minusDays(1)), 1L);
        couponService.reserve(request(memberCoupon, 1L, newOrderNo()));

        assertErrorCode(() -> couponService.reserve(request(memberCoupon, 1L, newOrderNo())), CouponErrorCode.COUPON_NOT_AVAILABLE);
    }

    @Test
    @DisplayName("같은 주문번호로 다시 선점하면 같은 할인액으로 성공한다 - 결과를 모르는 order-service의 재요청")
    void reserve_sameOrderNo() {
        MemberCoupon memberCoupon = issue(saveCoupon(DiscountType.AMOUNT, 1_000, LocalDateTime.now().minusDays(1)), 1L);
        String orderNo = newOrderNo();
        couponService.reserve(request(memberCoupon, 1L, orderNo));

        int discountAmount = couponService.reserve(request(memberCoupon, 1L, orderNo)).discountAmount();

        MemberCoupon reserved = memberCouponRepository.findById(memberCoupon.getId()).orElseThrow();
        assertThat(discountAmount).isEqualTo(1_000);
        assertThat(reserved.getStatus()).isEqualTo(MemberCouponStatus.RESERVED);
        assertThat(reserved.getOrderNo()).isEqualTo(orderNo);
    }

    @Test
    @DisplayName("같은 주문번호의 선점이 동시에 들어와도 모두 같은 할인액으로 성공한다 - 원래 요청과 재시도가 겹친 경우")
    void reserve_concurrentSameOrderNo() throws Exception {
        for (int i = 0; i < 20; i++) {
            MemberCoupon memberCoupon = issue(saveCoupon(DiscountType.AMOUNT, 1_000, LocalDateTime.now().minusDays(1)), 1L);
            String orderNo = newOrderNo();
            CountDownLatch start = new CountDownLatch(1);

            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                List<CompletableFuture<Integer>> futures = IntStream.range(0, 2)
                        .mapToObj(n -> CompletableFuture.supplyAsync(() -> {
                            try {
                                start.await();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(e);
                            }
                            return couponReservationFacade.reserve(request(memberCoupon, 1L, orderNo)).discountAmount();
                        }, executor))
                        .toList();
                start.countDown();

                assertThat(futures).allSatisfy(future -> assertThat(future.get()).isEqualTo(1_000));
            } finally {
                executor.shutdown();
            }
            assertThat(memberCouponRepository.findById(memberCoupon.getId()).orElseThrow().getOrderNo()).isEqualTo(orderNo);
        }
    }

    @Test
    @DisplayName("같은 쿠폰으로 동시에 주문해도 한 주문만 선점한다")
    void reserve_concurrent() throws Exception {
        int requestCount = 20;
        MemberCoupon memberCoupon = issue(saveCoupon(DiscountType.AMOUNT, 1_000, LocalDateTime.now().minusDays(1)), 1L);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        try {
            List<CompletableFuture<Void>> futures = IntStream.range(0, requestCount)
                    .mapToObj(i -> CompletableFuture.runAsync(() -> {
                        try {
                            start.await();
                            couponReservationFacade.reserve(request(memberCoupon, 1L, newOrderNo()));
                            succeeded.incrementAndGet();
                        } catch (CouponException e) {
                            if (e.getErrorCode() == CouponErrorCode.COUPON_NOT_AVAILABLE) rejected.incrementAndGet();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }, executor))
                    .toList();
            start.countDown();
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get();
        } finally {
            executor.shutdown();
        }

        assertThat(succeeded.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(requestCount - 1);
    }

    @Test
    @DisplayName("확정을 다시 요청해도 성공으로 응답한다")
    void confirm_twice() {
        MemberCoupon memberCoupon = issue(saveCoupon(DiscountType.AMOUNT, 1_000, LocalDateTime.now().minusDays(1)), 1L);
        String orderNo = newOrderNo();
        couponService.reserve(request(memberCoupon, 1L, orderNo));

        couponService.confirm(orderNo);
        assertThatCode(() -> couponService.confirm(orderNo)).doesNotThrowAnyException();

        MemberCoupon used = memberCouponRepository.findById(memberCoupon.getId()).orElseThrow();
        assertThat(used.getStatus()).isEqualTo(MemberCouponStatus.USED);
        assertThat(used.getUsedAt()).isNotNull();
    }

    // 스케줄러가 먼저 해제했든 아직 RESERVED로 남아 있든 같은 결과여야 함
    @Test
    @DisplayName("결제 기한이 지난 선점은 확정하지 않는다")
    void confirm_expired() {
        String orderNo = newOrderNo();
        saveReserved(1L, orderNo, MemberCouponStatus.RESERVED, LocalDateTime.now().minusSeconds(1));

        assertErrorCode(() -> couponService.confirm(orderNo), CouponErrorCode.COUPON_RESERVATION_EXPIRED);
    }

    @Test
    @DisplayName("확정 후 해제하면 다시 다른 주문에 쓸 수 있고, 해제를 반복해도 안전하다")
    void release_afterConfirm() {
        MemberCoupon memberCoupon = issue(saveCoupon(DiscountType.AMOUNT, 1_000, LocalDateTime.now().minusDays(1)), 1L);
        String orderNo = newOrderNo();
        couponService.reserve(request(memberCoupon, 1L, orderNo));
        couponService.confirm(orderNo);

        couponService.release(orderNo);
        assertThatCode(() -> couponService.release(orderNo)).doesNotThrowAnyException();

        MemberCoupon released = memberCouponRepository.findById(memberCoupon.getId()).orElseThrow();
        assertThat(released.getStatus()).isEqualTo(MemberCouponStatus.ISSUED);
        assertThat(released.getOrderNo()).isNull();
        assertThat(released.getUsedAt()).isNull();
        assertThatCode(() -> couponService.reserve(request(memberCoupon, 1L, newOrderNo()))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("기한이 지난 선점만 해제하고, 확정된 쿠폰은 기한이 지나도 그대로 둔다")
    void expireReservations() {
        MemberCoupon expired = saveReserved(1L, newOrderNo(), MemberCouponStatus.RESERVED, LocalDateTime.now().minusSeconds(1));
        MemberCoupon notYet = saveReserved(1L, newOrderNo(), MemberCouponStatus.RESERVED, LocalDateTime.now().plusMinutes(10));
        MemberCoupon confirmed = saveReserved(1L, newOrderNo(), MemberCouponStatus.USED, LocalDateTime.now().minusSeconds(1));

        expiryScheduler.releaseExpiredReservations();

        assertThat(memberCouponRepository.findById(expired.getId()).orElseThrow().getStatus()).isEqualTo(MemberCouponStatus.ISSUED);
        assertThat(memberCouponRepository.findById(notYet.getId()).orElseThrow().getStatus()).isEqualTo(MemberCouponStatus.RESERVED);
        assertThat(memberCouponRepository.findById(confirmed.getId()).orElseThrow().getStatus()).isEqualTo(MemberCouponStatus.USED);
    }

    private Coupon saveCoupon(DiscountType discountType, int discountValue, LocalDateTime useStartAt) {
        return couponRepository.save(Coupon.builder()
                .name("테스트 쿠폰")
                .discountType(discountType)
                .discountValue(discountValue)
                .type(CouponType.NORMAL)
                .issueStartAt(useStartAt.minusDays(1))
                .issueEndAt(useStartAt.plusDays(1))
                .useStartAt(useStartAt)
                .useEndAt(useStartAt.plusDays(2))
                .build());
    }

    private MemberCoupon issue(Coupon coupon, Long memberId) {
        return memberCouponRepository.save(MemberCoupon.builder().coupon(coupon).memberId(memberId).build());
    }

    // 지난 기한의 선점은 API로 만들 수 없어(@Future) 저장 전에 직접 지정
    private MemberCoupon saveReserved(Long memberId, String orderNo, MemberCouponStatus status, LocalDateTime reservedUntil) {
        MemberCoupon memberCoupon = MemberCoupon.builder()
                .coupon(saveCoupon(DiscountType.AMOUNT, 1_000, LocalDateTime.now().minusDays(1)))
                .memberId(memberId)
                .build();
        ReflectionTestUtils.setField(memberCoupon, "status", status);
        ReflectionTestUtils.setField(memberCoupon, "orderNo", orderNo);
        ReflectionTestUtils.setField(memberCoupon, "reservedUntil", reservedUntil);
        return memberCouponRepository.save(memberCoupon);
    }

    private static CouponReserveRequest request(MemberCoupon memberCoupon, Long memberId, String orderNo) {
        return new CouponReserveRequest(memberCoupon.getId(), memberId, orderNo, ORDER_AMOUNT, LocalDateTime.now().plusMinutes(10));
    }

    private static String newOrderNo() {
        return UUID.randomUUID().toString();
    }

    private static void assertErrorCode(Runnable action, CouponErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(CouponException.class, e -> assertThat(e.getErrorCode()).isEqualTo(errorCode));
    }
}
