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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;

// 1인 1장과 발급 번호 중복은 DB 유니크 제약이 최종으로 지키므로 실제 DB에서 확인 - Redis 번호 분배는 CouponIssueFacadeTest에서 확인
@Testcontainers
@SpringBootTest
class CouponIssueConcurrencyTest {
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

    @Test
    @DisplayName("같은 회원이 동시에 여러 번 발급해도 1장만 발급된다")
    void issuesOncePerMemberConcurrently() throws InterruptedException {
        Long couponId = firstComeCoupon();
        Long memberId = 1L;

        List<Throwable> errors = runConcurrently(IntStream.range(0, 10)
                .<Runnable>mapToObj(i -> () -> couponIssueService.issue(couponId, memberId, i + 1))
                .toList());

        assertThat(errors).hasSize(9)
                .allSatisfy(error -> assertThat(error)
                        .isInstanceOf(CouponException.class)
                        .extracting("errorCode")
                        .isEqualTo(CouponErrorCode.ALREADY_ISSUED));
        assertThat(memberCouponRepository.countByCouponId(couponId)).isEqualTo(1);
    }

    // 위 동시 요청 테스트는 타이밍에 따라 사전 확인에서만 걸러져도 통과하므로, 유니크 제약 경로는 따로 고정해서 확인
    @Test
    @DisplayName("사전 확인을 함께 통과한 중복 요청은 유니크 제약으로 이미 발급 처리된다")
    void rejectsDuplicateByUniqueConstraint() {
        Long couponId = firstComeCoupon();
        Long memberId = 1L;
        couponIssueService.issue(couponId, memberId, 1);
        doReturn(false).when(memberCouponRepository).existsByCouponIdAndMemberId(couponId, memberId);

        assertThatThrownBy(() -> couponIssueService.issue(couponId, memberId, 2))
                .isInstanceOf(CouponException.class)
                .extracting("errorCode")
                .isEqualTo(CouponErrorCode.ALREADY_ISSUED);
        assertThat(memberCouponRepository.countByCouponId(couponId)).isEqualTo(1);
    }

    // Redis가 같은 번호를 여러 번 내줘도 (스냅샷 복구, 재초기화 경합) 수량을 넘겨 발급되지 않는 근거
    @Test
    @DisplayName("여러 회원이 같은 번호로 동시에 발급해도 1장만 발급되고, 나머지는 일시적 발급 불가로 실패한다")
    void issuesOncePerSlotConcurrently() throws InterruptedException {
        Long couponId = firstComeCoupon();

        List<Throwable> errors = runConcurrently(IntStream.rangeClosed(1, 10)
                .<Runnable>mapToObj(memberId -> () -> couponIssueService.issue(couponId, (long) memberId, 1))
                .toList());

        assertThat(errors).hasSize(9)
                .allSatisfy(error -> assertThat(error)
                        .isInstanceOf(CouponException.class)
                        .extracting("errorCode")
                        .isEqualTo(CouponErrorCode.COUPON_ISSUE_UNAVAILABLE));
        assertThat(memberCouponRepository.countByCouponId(couponId)).isEqualTo(1);
    }

    // 유니크 제약은 같은 번호의 중복만 막으므로, 번호 없음·범위 밖 번호는 서비스가 막아야 함
    @Test
    @DisplayName("선착순 쿠폰에 번호가 없거나 수량 범위 밖이면 발급하지 않는다")
    void rejectsMissingOrOutOfRangeSlot() {
        Long couponId = firstComeCoupon();

        for (Integer slotNo : new Integer[]{null, 0, 501}) {
            assertThatThrownBy(() -> couponIssueService.issue(couponId, 1L, slotNo))
                    .isInstanceOf(CouponException.class)
                    .extracting("errorCode")
                    .isEqualTo(CouponErrorCode.COUPON_ISSUE_UNAVAILABLE);
        }
        assertThat(memberCouponRepository.countByCouponId(couponId)).isZero();
    }

    private Long firstComeCoupon() {
        LocalDateTime now = LocalDateTime.now();
        return couponRepository.save(Coupon.builder()
                .name("선착순 테스트 쿠폰")
                .discountType(DiscountType.AMOUNT)
                .discountValue(1_000)
                .type(CouponType.FIRST_COME)
                .quantity(500)
                .issueStartAt(now.minusHours(1))
                .issueEndAt(now.plusHours(1))
                .useStartAt(now.minusHours(1))
                .useEndAt(now.plusDays(1))
                .build()).getId();
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
