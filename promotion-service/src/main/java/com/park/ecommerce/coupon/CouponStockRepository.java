package com.park.ecommerce.coupon;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 선착순 쿠폰의 남은 발급 번호와 받은 회원을 Redis에 두고 번호를 나눠 줌 - 확인과 꺼내기를 Lua로 묶어 동시 요청에도 원자적으로 처리
 * 수량 한도의 최종 판단은 DB 유니크 제약(쿠폰 + 번호)이 하므로, 여기 값이 틀려도 결과는 덜 발급이나 일시적 실패에 그침
 */
@Repository
@RequiredArgsConstructor
public class CouponStockRepository {
    private static final String INIT_KEY = "coupon:init:%d"; // 쿠폰 id, 번호를 채웠는지 표시 - 번호 목록은 비면 Redis가 지워 소진과 구분할 수 없음
    private static final String SLOTS_KEY = "coupon:slots:%d"; // 쿠폰 id, 남은 발급 번호 목록
    private static final String MEMBERS_KEY = "coupon:members:%d"; // 쿠폰 id, 번호를 받은 회원 id Set

    // 반환값: 1 이상 발급 번호, 0 소진, -2 채우지 않음, -3 이미 받은 회원
    // 이미 받은 회원은 꺼내기 전에 걸러야 번호를 꺼냈다가 반납하는 일이 생기지 않음
    private static final RedisScript<Long> ACQUIRE = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then return -2 end
            if redis.call('SISMEMBER', KEYS[3], ARGV[1]) == 1 then return -3 end
            local slot = redis.call('LPOP', KEYS[2])
            if not slot then return 0 end
            redis.call('SADD', KEYS[3], ARGV[1])
            local ttl = redis.call('PTTL', KEYS[1])
            if ttl > 0 then redis.call('PEXPIRE', KEYS[3], ttl) end
            return tonumber(slot)
            """, Long.class);

    // 표시 키가 있을 때만 반납 - 없으면 다음 요청이 DB 기준으로 다시 채우고, 목록이 비어 지워진 뒤 반납하면 만료 없는 목록이 새로 생기므로 만료도 맞춤
    // ARGV[2]가 있으면 회원 기록도 지워 같은 회원이 다시 신청할 수 있게 함
    private static final RedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            if ARGV[2] then redis.call('SREM', KEYS[3], ARGV[2]) end
            local ttl = redis.call('PTTL', KEYS[1])
            if ttl == -2 then return 0 end
            redis.call('RPUSH', KEYS[2], ARGV[1])
            if ttl > 0 then redis.call('PEXPIRE', KEYS[2], ttl) end
            return 1
            """, Long.class);

    // ARGV[1] 만료(ms), ARGV[2..] 남은 번호 - 이미 채워졌으면 덮어쓰지 않음(진행 중인 값을 지키기 위해)
    // unpack은 인자 수 제한(약 8,000개)이 있어 나눠서 넣고, 표시 키는 번호를 모두 넣은 뒤에 설정
    private static final RedisScript<Long> INITIALIZE = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
            redis.call('DEL', KEYS[2])
            for i = 2, #ARGV, 1000 do
                redis.call('RPUSH', KEYS[2], unpack(ARGV, i, math.min(i + 999, #ARGV)))
            end
            redis.call('PEXPIRE', KEYS[2], ARGV[1])
            redis.call('SET', KEYS[1], '1', 'PX', ARGV[1])
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public SlotAcquisition tryAcquire(Long couponId, Long memberId) {
        long result = redisTemplate.execute(ACQUIRE, keys(couponId), String.valueOf(memberId));
        if (result > 0) return SlotAcquisition.acquired((int) result);
        if (result == 0) return SlotAcquisition.SOLD_OUT;
        if (result == -3) return SlotAcquisition.ALREADY_ISSUED;
        return SlotAcquisition.NOT_INITIALIZED;
    }

    public boolean isInitialized(Long couponId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(INIT_KEY.formatted(couponId)));
    }

    public void initializeIfAbsent(Long couponId, List<Integer> slots, Duration ttl) {
        List<String> args = new ArrayList<>(slots.size() + 1);
        args.add(String.valueOf(ttl.toMillis()));
        slots.forEach(slot -> args.add(String.valueOf(slot)));
        redisTemplate.execute(INITIALIZE, keys(couponId), args.toArray());
    }

    // 회원 기록은 남기고 번호만 반납 - 실제로 이미 받은 회원일 때
    public void release(Long couponId, int slotNo) {
        redisTemplate.execute(RELEASE, keys(couponId), String.valueOf(slotNo));
    }

    public void cancel(Long couponId, int slotNo, Long memberId) {
        redisTemplate.execute(RELEASE, keys(couponId), String.valueOf(slotNo), String.valueOf(memberId));
    }

    // 번호는 버리고 회원 기록만 지움 - 이미 쓰였거나 범위 밖 번호라 반납하면 같은 실패가 반복됨
    public void forgetMember(Long couponId, Long memberId) {
        redisTemplate.opsForSet().remove(MEMBERS_KEY.formatted(couponId), String.valueOf(memberId));
    }

    private static List<String> keys(Long couponId) {
        return List.of(INIT_KEY.formatted(couponId), SLOTS_KEY.formatted(couponId), MEMBERS_KEY.formatted(couponId));
    }

    public record SlotAcquisition(Status status, Integer slotNo) {
        public static final SlotAcquisition SOLD_OUT = new SlotAcquisition(Status.SOLD_OUT, null);
        public static final SlotAcquisition ALREADY_ISSUED = new SlotAcquisition(Status.ALREADY_ISSUED, null);
        public static final SlotAcquisition NOT_INITIALIZED = new SlotAcquisition(Status.NOT_INITIALIZED, null);

        static SlotAcquisition acquired(int slotNo) {
            return new SlotAcquisition(Status.ACQUIRED, slotNo);
        }

        public enum Status {
            ACQUIRED,
            SOLD_OUT,
            ALREADY_ISSUED,
            NOT_INITIALIZED
        }
    }
}
