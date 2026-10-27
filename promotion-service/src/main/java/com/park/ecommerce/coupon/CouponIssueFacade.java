package com.park.ecommerce.coupon;

import com.park.ecommerce.coupon.CouponStockRepository.StockDecrease;
import com.park.ecommerce.coupon.domain.Coupon;
import com.park.ecommerce.coupon.domain.CouponRepository;
import com.park.ecommerce.coupon.domain.CouponType;
import com.park.ecommerce.exception.coupon.CouponErrorCode;
import com.park.ecommerce.exception.coupon.CouponException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Redis에서 남은 수량을 먼저 차감하여, 소진된 뒤의 요청은 DB 커넥션과 쿠폰 행 잠금까지 가지 않고 바로 실패시킴
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponIssueFacade {
    private static final Duration STOCK_TTL_MARGIN = Duration.ofDays(1); // 발급 종료 뒤에도 늦게 도착한 요청을 소진으로 응답하기 위한 여유

    private final CouponStockRepository couponStockRepository;
    private final CouponRepository couponRepository;
    private final CouponIssueService couponIssueService;

    private final Map<Long, Object> initLocks = new ConcurrentHashMap<>();

    public void issue(Long couponId, Long memberId) {
        boolean decreased = passStockGate(couponId); // Redis 선차감 결과
        try {
            couponIssueService.issue(couponId, memberId);
        } catch (CouponException e) {
            if (decreased) compensate(couponId, e.getErrorCode());
            throw e;
        } catch (RuntimeException e) {
            if (decreased) restore(couponId); // 트랜잭션이 롤백되어 DB 수량은 그대로
            throw e;
        }
    }

    // Redis에서 차감했으면 true, 입구를 거치지 않고 DB로 보낼 때 (무제한 쿠폰, 없는 쿠폰, Redis 장애)는 false
    private boolean passStockGate(Long couponId) {
        try {
            return switch (decreaseOrInitialize(couponId)) {
                case DECREASED -> true;
                case SOLD_OUT -> throw new CouponException(CouponErrorCode.COUPON_SOLD_OUT);
                case UNLIMITED, NOT_INITIALIZED -> false;
            };
        } catch (DataAccessException e) {
            log.warn("[쿠폰 발급] Redis 수량 확인 실패, DB로 처리 couponId={}", couponId, e);
            return false;
        }
    }

    private StockDecrease decreaseOrInitialize(Long couponId) {
        StockDecrease result = couponStockRepository.tryDecrease(couponId);
        if (result != StockDecrease.NOT_INITIALIZED) return result;

        // Redis에 수량 키가 없을 때, 락 안에서는 초기화만
        synchronized (initLocks.computeIfAbsent(couponId, id -> new Object())) {
            if (!couponStockRepository.exists(couponId) && !initializeStock(couponId)) {
                return StockDecrease.NOT_INITIALIZED; // DB에도 없는 쿠폰
            }
        }
        return couponStockRepository.tryDecrease(couponId);
    }

    // DB의 남은 수량으로 채움 - Redis가 재시작되거나 키가 만료돼도 DB 기준으로 다시 맞춰짐
    // 없는 쿠폰이면 false를 돌려 DB 처리 (COUPON_NOT_FOUND)에 맡김
    private boolean initializeStock(Long couponId) {
        return couponRepository.findById(couponId)
                .map(coupon -> {
                    Integer quantity = coupon.getType() == CouponType.FIRST_COME ? coupon.getQuantity() : null;
                    couponStockRepository.initializeIfAbsent(couponId, quantity, stockTtl(coupon));
                    return true;
                })
                .orElse(false);
    }

    private static Duration stockTtl(Coupon coupon) {
        Duration untilIssueEnd = Duration.between(LocalDateTime.now(), coupon.getIssueEndAt());
        return untilIssueEnd.isNegative() ? STOCK_TTL_MARGIN : untilIssueEnd.plus(STOCK_TTL_MARGIN);
    }

    // DB가 수량을 쓰지 않고 실패했으면 Redis에 돌려놓음 - 단, DB가 소진이라고 하면 Redis가 실제보다 많았던 것이므로 0으로 맞춤
    private void compensate(Long couponId, CouponErrorCode errorCode) {
        if (errorCode != CouponErrorCode.COUPON_SOLD_OUT) {
            restore(couponId);
            return;
        }
        try {
            couponStockRepository.markSoldOut(couponId);
        } catch (DataAccessException e) {
            log.warn("[쿠폰 발급] Redis 소진 표시 실패 couponId={}", couponId, e);
        }
    }

    private void restore(Long couponId) {
        try {
            couponStockRepository.increase(couponId);
        } catch (DataAccessException e) {
            log.warn("[쿠폰 발급] Redis 수량 복구 실패 - 발급되지 못한 수량이 남을 수 있음 couponId={}", couponId, e);
        }
    }
}
