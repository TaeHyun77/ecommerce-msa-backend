package com.park.ecommerce.member;

import com.park.ecommerce.auth.util.CookieUtils;
import com.park.ecommerce.member.dto.MemberResponse;
import com.park.ecommerce.client.order.dto.MemberOrderResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/members")
@RequiredArgsConstructor
public class MemberController {
    private final MemberService memberService;

    // 회원 정보 조회
    @GetMapping("/me")
    public MemberResponse getMyInfo(@AuthenticationPrincipal Long memberId) {
        return memberService.getMyInfo(memberId);
    }

    // 특정 회원의 주문 목록 조회
    @GetMapping("/me/orders")
    public List<MemberOrderResponse> getMyOrders(@AuthenticationPrincipal Long memberId) {
        return memberService.getMyOrders(memberId);
    }

    @DeleteMapping("/me")
    public ResponseEntity<Void> withdraw(@AuthenticationPrincipal Long memberId, HttpServletResponse response) {
        memberService.withdraw(memberId);
        CookieUtils.expireRefreshTokenCookie(response);
        return ResponseEntity.noContent().build();
    }
}
