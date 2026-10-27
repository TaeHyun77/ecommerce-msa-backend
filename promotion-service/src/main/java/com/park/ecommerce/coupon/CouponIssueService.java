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

// 쿠폰 다운로드 - 선착순 쿠폰은 남은 수량을 차감하고, 회원별 발급 내역을 저장
@Service
@RequiredArgsConstructor
public class CouponIssueService {
    private final CouponRepository couponRepository;
    private final MemberCouponRepository memberCouponRepository;

    @Transactional
    public void issue(Long couponId, Long memberId) {
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new CouponException(CouponErrorCode.COUPON_NOT_FOUND));

        if (!coupon.isInIssuePeriod(LocalDateTime.now())) {
            throw new CouponException(CouponErrorCode.NOT_IN_ISSUE_PERIOD);
        }
        // 이미 받은 회원이 다시 누를 때 수량 차감(쿠폰 행 잠금)까지 가지 않도록 먼저 확인 - 동시 요청은 아래 유니크 제약이 막음
        if (memberCouponRepository.existsByCouponIdAndMemberId(couponId, memberId)) {
            throw new CouponException(CouponErrorCode.ALREADY_ISSUED);
        }

        // 차감(UPDATE)을 저장(INSERT)보다 먼저 - INSERT가 FK로 쿠폰 행에 공유 잠금을 먼저 잡으면
        // 동시 요청끼리 배타 잠금으로 올리려다 서로를 기다려 데드락이 날 수 있음
        if (coupon.getType() == CouponType.FIRST_COME && couponRepository.decreaseRemainingQuantity(couponId) == 0) {
            throw new CouponException(CouponErrorCode.COUPON_SOLD_OUT);
        }

        // 제약 위반을 이 메서드 안에서 받기 위해 즉시 flush - 커밋 시점으로 미뤄지면 아래 catch를 벗어남
        try {
            memberCouponRepository.saveAndFlush(MemberCoupon.builder()
                    .coupon(coupon)
                    .memberId(memberId)
                    .build());
        } catch (DataIntegrityViolationException e) {
            // 1인 1장 유니크 제약 외의 위반(NOT NULL 등)까지 "이미 발급"으로 숨기지 않도록 제약 이름으로 구분
            if (!isDuplicateIssue(e)) throw e;
            // 같은 회원의 동시 요청 - 예외로 트랜잭션이 롤백되어 위에서 차감한 수량도 함께 복구됨
            throw new CouponException(CouponErrorCode.ALREADY_ISSUED);
        }
    }

    private static boolean isDuplicateIssue(DataIntegrityViolationException e) {
        return e.getCause() instanceof ConstraintViolationException violation
                && violation.getConstraintName() != null
                && violation.getConstraintName().contains(MemberCoupon.UK_COUPON_MEMBER);
    }
}
