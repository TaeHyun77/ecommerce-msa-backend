package com.park.ecommerce.coupon;

import com.park.ecommerce.coupon.domain.MemberCouponRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class CouponReservationExpiryScheduler {
    private static final int BATCH_SIZE = 100;

    private final MemberCouponRepository memberCouponRepository;
    private final CouponService couponService;

    @Scheduled(fixedDelayString = "${coupon.reservation.expire-poll-delay}")
    public void releaseExpiredReservations() {
        List<Long> targetIds = memberCouponRepository.findExpiredIds(LocalDateTime.now(), PageRequest.of(0, BATCH_SIZE));

        for (Long memberCouponId : targetIds) {
            try {
                couponService.expire(memberCouponId);
            } catch (RuntimeException e) {
                // 한 건의 실패가 같은 회차의 나머지 건 처리를 막지 않도록 건 단위로 격리 - 실패한 건은 다음 회차에 다시 대상이 됨
                log.error("[쿠폰 선점] 만료 해제 중 오류 memberCouponId={}", memberCouponId, e);
            }
        }
    }
}
