package com.park.ecommerce.coupon;

import com.park.ecommerce.coupon.domain.*;
import com.park.ecommerce.coupon.dto.CouponReserveRequest;
import com.park.ecommerce.coupon.dto.CouponReserveResponse;
import com.park.ecommerce.coupon.dto.MemberCouponResponse;
import com.park.ecommerce.exception.coupon.CouponErrorCode;
import com.park.ecommerce.exception.coupon.CouponException;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

// 회원 쿠폰의 발급, 조회, 주문 사용 (선점 → 확정 → 해제)
// 발급은 선착순 수량을 Redis가 나눠 준 발급 번호를 유니크 제약으로 확인하므로 반드시 CouponIssueFacade를 거쳐 호출 - 직접 부르면 번호 분배와 반납을 건너뜀
@Service
@RequiredArgsConstructor
public class CouponService {
    private final CouponRepository couponRepository;
    private final MemberCouponRepository memberCouponRepository;

    // 내 쿠폰 목록 조회 - 최근 발급순
    @Transactional(readOnly = true)
    public List<MemberCouponResponse> getMyCoupons(Long memberId) {
        LocalDateTime now = LocalDateTime.now();
        return memberCouponRepository.findAllWithCouponByMemberIdOrderByIdDesc(memberId).stream()
                .map(memberCoupon -> MemberCouponResponse.from(memberCoupon, now))
                .toList();
    }

    // 쿠폰 발급
    @Transactional
    public void issue(Long couponId, Long memberId, Integer slotNo) {
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new CouponException(CouponErrorCode.COUPON_NOT_FOUND));

        if (!coupon.isInIssuePeriod(LocalDateTime.now())) { // 발급 유효성 확인
            throw new CouponException(CouponErrorCode.NOT_IN_ISSUE_PERIOD);
        }
        if (!coupon.isIssuableSlot(slotNo)) { // 쿠폰 번호 유효성 확인
            throw new CouponException(CouponErrorCode.COUPON_ISSUE_UNAVAILABLE);
        }

        if (memberCouponRepository.existsByCouponIdAndMemberId(couponId, memberId)) { // 이미 발급받은 쿠폰인지 확인
            throw new CouponException(CouponErrorCode.ALREADY_ISSUED);
        }

        try {
            memberCouponRepository.saveAndFlush(MemberCoupon.builder()
                    .coupon(coupon)
                    .memberId(memberId)
                    .slotNo(slotNo)
                    .build()
            );
        } catch (DataIntegrityViolationException e) {
            throw toCouponException(e);
        }
    }

    // 주문 생성 시 쿠폰을 주문에 선점
    // 사용 기간은 선점 시점에만 확인 - 이후로는 선점 만료(결제 기한)가 지켜, 결제 중 사용 기간이 끝나도 이 주문에는 쓸 수 있음
    // 같은 주문번호의 동시 요청에 진 쪽은 COUPON_NOT_AVAILABLE로 끝나고, CouponReservationFacade가 새 트랜잭션으로 다시 확인함
    @Transactional
    public CouponReserveResponse reserve(CouponReserveRequest request) {
        MemberCoupon memberCoupon = memberCouponRepository.findWithCouponByIdAndMemberId(request.memberCouponId(), request.memberId())
                .orElseThrow(() -> new CouponException(CouponErrorCode.MEMBER_COUPON_NOT_FOUND));

        Coupon coupon = memberCoupon.getCoupon();

        // 이미 선점한 주문의 재요청은 사용 기간과 관계없이 기존 할인액으로 응답
        if (request.orderNo().equals(memberCoupon.getOrderNo())) {
            return new CouponReserveResponse(coupon.getDiscount().calculateAmount(request.orderAmount()));
        }
        if (!coupon.isInUsePeriod(LocalDateTime.now())) { // 사용 기간 유효성 확인
            throw new CouponException(CouponErrorCode.NOT_IN_USE_PERIOD);
        }
        if (memberCouponRepository.reserve(memberCoupon.getId(), request.orderNo(), request.expiresAt()) == 0) { // 선점 요청 (조건부 update)
            throw new CouponException(CouponErrorCode.COUPON_NOT_AVAILABLE);
        }

        // 할인액이 주문 금액과 같아 0원 결제가 되는 경우도 허용 - PG 승인 생략은 order-service가 처리
        return new CouponReserveResponse(coupon.getDiscount().calculateAmount(request.orderAmount()));
    }

    // 쿠폰 사용 확정
    // 만료 여부를 먼저 확인한 뒤 주문번호 조회와 PK 기반 UPDATE로 확정 처리하여, 스케줄러 실행 순서와 동시 요청에 관계없이 만료된 쿠폰의 확정 및 중복 확정을 방지합니다.
    @Transactional
    public void confirm(String orderNo) {
        // 선점이 없으면 만료나 해제로 주문번호가 지워진 것
        MemberCoupon memberCoupon = memberCouponRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new CouponException(CouponErrorCode.COUPON_RESERVATION_EXPIRED));

        // 승인 결과를 몰라 order-service가 다시 확정을 요청한 경우 - 이미 확정됐으면 성공으로 응답
        if (memberCoupon.getStatus() == MemberCouponStatus.USED) {
            return;
        }
        if (memberCouponRepository.confirm(memberCoupon.getId(), orderNo, LocalDateTime.now()) == 0) {
            throw new CouponException(CouponErrorCode.COUPON_RESERVATION_EXPIRED);
        }
    }

    // 쿠폰 선점 해제
    @Transactional
    public void release(String orderNo) {
        memberCouponRepository.findByOrderNo(orderNo)
                .ifPresent(memberCoupon -> memberCouponRepository.release(memberCoupon.getId(), orderNo));
    }

    // 쿠폰 만료처리
    @Transactional
    public void expire(Long memberCouponId) {
        memberCouponRepository.expire(memberCouponId, LocalDateTime.now());
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
