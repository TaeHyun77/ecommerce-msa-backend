package com.park.ecommerce.coupon;

import com.park.ecommerce.coupon.domain.Coupon;
import com.park.ecommerce.coupon.domain.CouponRepository;
import com.park.ecommerce.coupon.domain.CouponType;
import com.park.ecommerce.coupon.domain.MemberCoupon;
import com.park.ecommerce.coupon.domain.MemberCouponRepository;
import com.park.ecommerce.coupon.domain.MemberCouponStatus;
import com.park.ecommerce.coupon.dto.MemberCouponResponse;
import com.park.ecommerce.coupon.dto.MemberCouponResponse.Status;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

// 회원별 조회와 발급 내역마다 쿠폰을 함께 읽는 쿼리를 실제 MySQL에서 확인
@Testcontainers
@SpringBootTest
class CouponServiceTest {
    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.0");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.2")).withExposedPorts(6379);

    @Autowired
    private CouponService couponService;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private MemberCouponRepository memberCouponRepository;

    @Test
    @DisplayName("내 쿠폰만 최근 발급순으로 조회한다")
    void getMyCoupons_onlyMine_latestFirst() {
        LocalDateTime now = LocalDateTime.now();
        Coupon first = saveCoupon("첫 쿠폰", now.minusDays(1), now.plusDays(1));
        Coupon second = saveCoupon("둘째 쿠폰", now.minusDays(1), now.plusDays(1));
        saveMemberCoupon(first, 1L, MemberCouponStatus.ISSUED);
        saveMemberCoupon(first, 2L, MemberCouponStatus.ISSUED);
        saveMemberCoupon(second, 1L, MemberCouponStatus.ISSUED);

        List<MemberCouponResponse> coupons = couponService.getMyCoupons(1L);

        assertThat(coupons).extracting(MemberCouponResponse::name).containsExactly("둘째 쿠폰", "첫 쿠폰");
        assertThat(coupons.get(0).discountType()).isEqualTo(DiscountType.AMOUNT);
        assertThat(coupons.get(0).issuedAt()).isNotNull();
    }

    @Test
    @DisplayName("쓰지 않은 쿠폰은 사용 기간으로, 예약·사용된 쿠폰은 저장된 상태로 표시한다")
    void getMyCoupons_status() {
        Long memberId = 10L;
        LocalDateTime now = LocalDateTime.now();
        saveMemberCoupon(saveCoupon("사용 가능", now.minusDays(1), now.plusDays(1)), memberId, MemberCouponStatus.ISSUED);
        saveMemberCoupon(saveCoupon("시작 전", now.plusDays(1), now.plusDays(2)), memberId, MemberCouponStatus.ISSUED);
        saveMemberCoupon(saveCoupon("만료", now.minusDays(2), now.minusDays(1)), memberId, MemberCouponStatus.ISSUED);
        // 기간이 지나도 예약·사용 상태가 만료로 덮이지 않는지 확인하기 위해 만료된 기간으로 둠
        saveMemberCoupon(saveCoupon("예약", now.minusDays(2), now.minusDays(1)), memberId, MemberCouponStatus.RESERVED);
        saveMemberCoupon(saveCoupon("사용 완료", now.minusDays(2), now.minusDays(1)), memberId, MemberCouponStatus.USED);

        List<MemberCouponResponse> coupons = couponService.getMyCoupons(memberId);

        assertThat(coupons).extracting(MemberCouponResponse::name, MemberCouponResponse::status).containsExactly(
                tuple("사용 완료", Status.USED),
                tuple("예약", Status.RESERVED),
                tuple("만료", Status.EXPIRED),
                tuple("시작 전", Status.NOT_STARTED),
                tuple("사용 가능", Status.AVAILABLE)
        );
    }

    @Test
    @DisplayName("받은 쿠폰이 없으면 빈 목록을 반환한다")
    void getMyCoupons_empty() {
        assertThat(couponService.getMyCoupons(999L)).isEmpty();
    }

    // 발급 기간은 사용 기간 안쪽으로 둬 생성자 검증을 통과시킴 - 조회 테스트라 발급 기간은 의미 없음
    private Coupon saveCoupon(String name, LocalDateTime useStartAt, LocalDateTime useEndAt) {
        return couponRepository.save(Coupon.builder()
                .name(name)
                .discountType(DiscountType.AMOUNT)
                .discountValue(1_000)
                .type(CouponType.NORMAL)
                .issueStartAt(useStartAt.minusDays(1))
                .issueEndAt(useEndAt)
                .useStartAt(useStartAt)
                .useEndAt(useEndAt)
                .build());
    }

    // 예약·사용으로 바꾸는 비즈니스 메서드가 아직 없어 저장 전에 상태를 직접 지정
    private void saveMemberCoupon(Coupon coupon, Long memberId, MemberCouponStatus status) {
        MemberCoupon memberCoupon = MemberCoupon.builder().coupon(coupon).memberId(memberId).build();
        ReflectionTestUtils.setField(memberCoupon, "status", status);
        memberCouponRepository.save(memberCoupon);
    }
}
