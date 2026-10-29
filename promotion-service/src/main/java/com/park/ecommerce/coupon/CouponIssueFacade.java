package com.park.ecommerce.coupon;

import com.park.ecommerce.coupon.CouponStockRepository.SlotAcquisition;
import com.park.ecommerce.coupon.domain.Coupon;
import com.park.ecommerce.coupon.domain.CouponRepository;
import com.park.ecommerce.coupon.domain.CouponType;
import com.park.ecommerce.coupon.domain.MemberCouponRepository;
import com.park.ecommerce.exception.coupon.CouponErrorCode;
import com.park.ecommerce.exception.coupon.CouponException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

/**
 * 선착순 쿠폰은 Redis가 발급 번호 (1~수량)를 나눠 주고, DB는 번호와 함께 발급 기록을 저장
 * 같은 번호는 DB 유니크 제약이 한 번만 받아들이므로, Redis가 상태를 잃거나 오래된 값으로 돌아와도 수량을 넘겨 발급되지 않음
 * 실패한 요청의 번호는 커밋 여부가 불확실해도 반납하고, Redis 키는 언제 지워도 DB 기준으로 다시 채워짐
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponIssueFacade {
    private static final Duration SLOTS_TTL_MARGIN = Duration.ofDays(1); // 발급 종료 뒤에도 늦게 도착한 요청을 소진으로 응답하기 위한 여유

    private final CouponStockRepository couponStockRepository;
    private final CouponRepository couponRepository;
    private final MemberCouponRepository memberCouponRepository;
    private final CouponIssueService couponIssueService;

    private final Map<Long, Object> initLocks = new ConcurrentHashMap<>();
    // 발급 기간, 유형 확인용 - 오픈 전후 몰리는 요청마다 DB를 조회하지 않도록 메모리에 둠
    private final Map<Long, Coupon> coupons = new ConcurrentHashMap<>();

    public void issue(Long couponId, Long memberId) {
        Coupon coupon = findCoupon(couponId);
        validateIssuePeriod(coupon);

        // 기본 쿠폰은 수량 제한이 없어 Redis를 거치지 않음 - 1인 1장은 DB 유니크 제약이 지킴
        Integer slotNo = coupon.getType() == CouponType.FIRST_COME ? acquireSlot(coupon, memberId) : null;
        try {
            couponIssueService.issue(couponId, memberId, slotNo);
        } catch (CouponException e) {
            if (slotNo != null) compensate(couponId, memberId, slotNo, e.getErrorCode());
            throw e;
        } catch (RuntimeException e) {
            // 커밋됐는지 알 수 없는 실패도 반납 - 실제로 커밋됐다면 이 번호를 다시 받은 요청은 유니크 제약에 막힘
            if (slotNo != null) {
                log.warn("[쿠폰 발급] DB 처리 실패, 번호 반납 couponId={}, memberId={}, slotNo={}", couponId, memberId, slotNo, e);
                restore(couponId, () -> couponStockRepository.cancel(couponId, slotNo, memberId));
            }
            throw e;
        }
    }

    private Coupon findCoupon(Long couponId) {
        return coupons.computeIfAbsent(couponId, id -> couponRepository.findById(id)
                .orElseThrow(() -> new CouponException(CouponErrorCode.COUPON_NOT_FOUND)));
    }

    // Redis에서 번호를 받기 전에 확인 - 기간 밖 요청이 번호를 붙잡은 채 DB를 기다리면, 오픈 직후 정상 요청이 소진으로 실패할 수 있음
    private static void validateIssuePeriod(Coupon coupon) {
        if (!coupon.isInIssuePeriod(LocalDateTime.now())) {
            throw new CouponException(CouponErrorCode.NOT_IN_ISSUE_PERIOD);
        }
    }

    private int acquireSlot(Coupon coupon, Long memberId) {
        SlotAcquisition result;
        try {
            result = acquireUnlessIssued(coupon, memberId);
        } catch (DataAccessException e) {
            // 번호 없이 DB로 넘겨도 서비스가 거부하므로 바로 실패시킴
            log.warn("[쿠폰 발급] 번호 확인 실패, 발급 거부 couponId={}", coupon.getId(), e);
            throw new CouponException(CouponErrorCode.COUPON_ISSUE_UNAVAILABLE);
        }
        return switch (result.status()) {
            case ACQUIRED -> result.slotNo();
            case SOLD_OUT -> throw new CouponException(CouponErrorCode.COUPON_SOLD_OUT);
            case ALREADY_ISSUED -> throw new CouponException(CouponErrorCode.ALREADY_ISSUED);
            // 채운 직후 키가 사라짐 (Redis 재시작 등)
            case NOT_INITIALIZED -> throw new CouponException(CouponErrorCode.COUPON_ISSUE_UNAVAILABLE);
        };
    }

    // Redis 회원 기록은 응답 유실, 보상 실패로 실제 발급 없이 남을 수 있어, 이미 받았다고 할 때만 DB로 확인
    // DB에 없으면 기록을 지우고 한 번만 다시 받음 - 첫 요청이 커밋 전이라 번호를 하나 더 받아도 회원 유니크 제약이 막고 번호는 반납됨
    private SlotAcquisition acquireUnlessIssued(Coupon coupon, Long memberId) {
        SlotAcquisition result = acquireOrInitialize(coupon, memberId);
        if (result.status() != SlotAcquisition.Status.ALREADY_ISSUED
                || memberCouponRepository.existsByCouponIdAndMemberId(coupon.getId(), memberId)) {
            return result;
        }
        couponStockRepository.forgetMember(coupon.getId(), memberId);
        return acquireOrInitialize(coupon, memberId);
    }

    private SlotAcquisition acquireOrInitialize(Coupon coupon, Long memberId) {
        SlotAcquisition result = couponStockRepository.tryAcquire(coupon.getId(), memberId);
        if (result.status() != SlotAcquisition.Status.NOT_INITIALIZED) return result;

        // Redis에 번호가 없을 때, 락 안에서는 채우기만
        synchronized (initLocks.computeIfAbsent(coupon.getId(), id -> new Object())) {
            if (!couponStockRepository.isInitialized(coupon.getId())) {
                initializeSlots(coupon);
            }
        }
        return couponStockRepository.tryAcquire(coupon.getId(), memberId);
    }

    // 쓰이지 않은 번호 중 (총 수량 - 발급 수)개만 채움 - 번호 없이 저장된 이전 발급 기록이 있어도 덜 발급되는 쪽으로 맞춤
    // 번호를 받고 아직 커밋 전인 요청의 번호가 다시 들어갈 수 있지만, 그 번호는 유니크 제약이 한 번만 받아들임
    private void initializeSlots(Coupon coupon) {
        long remaining = coupon.getQuantity() - memberCouponRepository.countByCouponId(coupon.getId());
        Set<Integer> used = new HashSet<>(memberCouponRepository.findSlotNosByCouponId(coupon.getId()));
        List<Integer> slots = IntStream.rangeClosed(1, coupon.getQuantity())
                .filter(slot -> !used.contains(slot))
                .limit(Math.max(0, remaining))
                .boxed()
                .toList();
        couponStockRepository.initializeIfAbsent(coupon.getId(), slots, slotsTtl(coupon));
    }

    private static Duration slotsTtl(Coupon coupon) {
        Duration untilIssueEnd = Duration.between(LocalDateTime.now(), coupon.getIssueEndAt());
        return untilIssueEnd.isNegative() ? SLOTS_TTL_MARGIN : untilIssueEnd.plus(SLOTS_TTL_MARGIN);
    }

    private void compensate(Long couponId, Long memberId, int slotNo, CouponErrorCode errorCode) {
        switch (errorCode) {
            // Redis 회원 기록이 빠져 있던 것이므로 기록은 남기고 번호만 반납
            case ALREADY_ISSUED -> restore(couponId, () -> couponStockRepository.release(couponId, slotNo));
            // 이미 쓰였거나 범위 밖 번호 - 반납하면 같은 실패가 반복되므로 버리고, 회원은 다시 신청할 수 있게 함
            case COUPON_ISSUE_UNAVAILABLE -> restore(couponId, () -> couponStockRepository.forgetMember(couponId, memberId));
            default -> restore(couponId, () -> couponStockRepository.cancel(couponId, slotNo, memberId));
        }
    }

    // 복구 실패는 덜 발급으로 이어질 뿐이라, 요청의 원래 실패 원인을 덮지 않도록 로그만 남김
    private static void restore(Long couponId, Runnable action) {
        try {
            action.run();
        } catch (DataAccessException e) {
            log.warn("[쿠폰 발급] Redis 번호 복구 실패 - 발급되지 못한 수량이 남을 수 있음 couponId={}", couponId, e);
        }
    }
}
