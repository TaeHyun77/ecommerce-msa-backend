package com.park.ecommerce.coupon;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;

// 선착순 쿠폰의 남은 수량을 Redis에 두고 DB보다 먼저 차감 - 확인과 차감을 Lua로 묶어 동시 요청에도 원자적으로 처리
@Repository
@RequiredArgsConstructor
public class CouponStockRepository {
    private static final String STOCK_KEY = "coupon:stock:%d"; // 쿠폰 id
    private static final String UNLIMITED = "-1"; // 기본 쿠폰 표시 - 수량 제한이 없어 차감하지 않음

    // 반환값: 1 차감, 0 소진, -1 무제한, -2 키 없음
    private static final RedisScript<Long> DECREASE = new DefaultRedisScript<>("""
            local stock = redis.call('GET', KEYS[1])
            if not stock then return -2 end
            stock = tonumber(stock)
            if stock == -1 then return -1 end
            if stock > 0 then
                redis.call('DECR', KEYS[1])
                return 1
            end
            return 0
            """, Long.class);

    // 키가 만료된 뒤 INCR하면 1짜리 키가 새로 생기므로, 남아 있는 수량 키일 때만 복구
    private static final RedisScript<Long> INCREASE = new DefaultRedisScript<>("""
            local stock = redis.call('GET', KEYS[1])
            if stock and tonumber(stock) >= 0 then return redis.call('INCR', KEYS[1]) end
            return -1
            """, Long.class);

    // 초기화 때 정한 만료 시간을 유지한 채 0으로 맞춤
    private static final RedisScript<Long> SOLD_OUT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1 then
                redis.call('SET', KEYS[1], '0', 'KEEPTTL')
                return 1
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public StockDecrease tryDecrease(Long couponId) {
        Long result = redisTemplate.execute(DECREASE, List.of(key(couponId)));
        return switch (result.intValue()) {
            case 1 -> StockDecrease.DECREASED;
            case 0 -> StockDecrease.SOLD_OUT;
            case -1 -> StockDecrease.UNLIMITED;
            default -> StockDecrease.NOT_INITIALIZED;
        };
    }

    public boolean exists(Long couponId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key(couponId)));
    }

    // quantity가 null이면 무제한 쿠폰 - 이미 키가 있으면 덮어쓰지 않음(진행 중인 차감 값을 지키기 위해)
    public void initializeIfAbsent(Long couponId, Integer quantity, Duration ttl) {
        String value = quantity == null ? UNLIMITED : String.valueOf(quantity);
        redisTemplate.opsForValue().setIfAbsent(key(couponId), value, ttl);
    }

    public void increase(Long couponId) {
        redisTemplate.execute(INCREASE, List.of(key(couponId)));
    }

    public void markSoldOut(Long couponId) {
        redisTemplate.execute(SOLD_OUT, List.of(key(couponId)));
    }

    private static String key(Long couponId) {
        return STOCK_KEY.formatted(couponId);
    }

    public enum StockDecrease {
        DECREASED,
        SOLD_OUT,
        UNLIMITED,
        NOT_INITIALIZED
    }
}
