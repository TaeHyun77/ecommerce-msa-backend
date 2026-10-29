package com.park.ecommerce.coupon;

import com.park.ecommerce.coupon.domain.*;
import com.park.ecommerce.exception.coupon.CouponErrorCode;
import com.park.ecommerce.exception.coupon.CouponException;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

// 쿠폰 다운로드
// 회원별 발급 내역을 저장, 선착순 수량은 Redis가 나눠 준 발급 번호를 유니크 제약으로 확인하므로 CouponIssueFacade를 거쳐 호출
@Service
@RequiredArgsConstructor
public class CouponIssueService {
    private final CouponRepository couponRepository;
    private final MemberCouponRepository memberCouponRepository;

    // 짧은 제한 시간 - 결과 불명으로 반납된 번호를 받은 요청이 아직 끝나지 않은 이전 트랜잭션의 잠금을 오래 기다리지 않도록
    @Transactional(timeout = 3)
    public void issue(Long couponId, Long memberId, Integer slotNo) {
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new CouponException(CouponErrorCode.COUPON_NOT_FOUND));

        if (!coupon.isInIssuePeriod(LocalDateTime.now())) {
            throw new CouponException(CouponErrorCode.NOT_IN_ISSUE_PERIOD);
        }
        // 회원 중복보다 먼저 확인 - 이미 받은 회원으로 처리되면 잘못된 번호가 Redis에 반납되어 다시 나감
        if (!coupon.isIssuableSlot(slotNo)) {
            throw new CouponException(CouponErrorCode.COUPON_ISSUE_UNAVAILABLE);
        }
        // 유니크 제약 위반은 InnoDB가 해당 행에 공유 잠금을 남겨 이웃 INSERT를 막을 수 있으므로, 흔한 재요청은 먼저 걸러냄
        // 동시 요청은 아래 유니크 제약이 막음
        if (memberCouponRepository.existsByCouponIdAndMemberId(couponId, memberId)) {
            throw new CouponException(CouponErrorCode.ALREADY_ISSUED);
        }

        // 제약 위반을 이 메서드 안에서 받기 위해 즉시 flush - 커밋 시점으로 미뤄지면 아래 catch를 벗어남
        try {
            memberCouponRepository.saveAndFlush(MemberCoupon.builder()
                    .coupon(coupon)
                    .memberId(memberId)
                    .slotNo(slotNo)
                    .build());
        } catch (DataIntegrityViolationException e) {
            throw toCouponException(e);
        }
    }

    // 유니크 제약 외의 위반(NOT NULL 등)까지 발급 실패로 숨기지 않도록 제약 이름으로 구분
    private static RuntimeException toCouponException(DataIntegrityViolationException e) {
        String constraintName = e.getCause() instanceof ConstraintViolationException violation ? violation.getConstraintName() : null;
        if (constraintName == null) return e;
        // 같은 회원의 동시 요청
        if (constraintName.contains(MemberCoupon.UK_COUPON_MEMBER)) return new CouponException(CouponErrorCode.ALREADY_ISSUED);
        // 이미 쓰인 번호 - Redis가 상태를 잃었거나 오래된 값으로 돌아온 경우
        if (constraintName.contains(MemberCoupon.UK_COUPON_SLOT)) return new CouponException(CouponErrorCode.COUPON_ISSUE_UNAVAILABLE);
        return e;
    }
}
