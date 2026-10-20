package com.park.ecommerce.member;

import com.park.ecommerce.auth.RefreshTokenRepository;
import com.park.ecommerce.exception.AuthErrorCode;
import com.park.ecommerce.exception.AuthException;
import com.park.ecommerce.member.domain.Member;
import com.park.ecommerce.member.dto.MemberResponse;
import com.park.ecommerce.member.repository.MemberRepository;
import com.park.ecommerce.client.order.OrderApiClient;
import com.park.ecommerce.client.order.dto.MemberOrderResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MemberService {
    private final MemberRepository memberRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final OrderApiClient orderApiClient;

    // 회원 정보 조회
    @Transactional(readOnly = true)
    public MemberResponse getMyInfo(Long memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new AuthException(AuthErrorCode.MEMBER_NOT_FOUND));

        return MemberResponse.from(member);
    }

    @Transactional
    public void withdraw(Long memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new AuthException(AuthErrorCode.MEMBER_NOT_FOUND));

        if (member.isWithdrawn()) {
            throw new AuthException(AuthErrorCode.ALREADY_WITHDRAWN_MEMBER);
        }

        member.withdraw();
        refreshTokenRepository.deleteByMemberId(memberId);
    }


    // 특정 회원의 주문 목록 조회
    public List<MemberOrderResponse> getMyOrders(Long memberId) {
        return orderApiClient.findOrdersByMemberId(memberId);
    }
}
