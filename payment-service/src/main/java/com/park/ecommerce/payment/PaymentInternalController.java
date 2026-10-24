package com.park.ecommerce.payment;

import com.park.ecommerce.payment.dto.PaymentApproveRequest;
import com.park.ecommerce.payment.dto.PaymentApproveResponse;
import com.park.ecommerce.payment.dto.PaymentResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 게이트웨이가 외부로 노출하지 않는 서비스 간 내부 통신 전용 API - order-service가 결제 인증을 마친 주문의 승인을 요청
// 같은 주문의 재요청에는 확정된 결과를 그대로 응답하므로, 결과를 모르는 order-service가 안전하게 다시 요청할 수 있음
@RestController
@RequestMapping("/internal/payments")
@RequiredArgsConstructor
public class PaymentInternalController {
    private final PaymentService paymentService;

    // order-service가 보낸 결제 승인 요청 ( 주문 생성 -> 결제 승인 요청 )
    @PostMapping("/approve")
    public PaymentApproveResponse approve(@Valid @RequestBody PaymentApproveRequest request) {
        return paymentService.approve(request);
    }

    // order-service가 주문 상세에 보여줄 결제 정보 조회 - 승인을 요청하기 전의 주문은 결제 기록이 없어 404
    @GetMapping("/{orderNo}")
    public PaymentResponse getPayment(@PathVariable String orderNo) {
        return paymentService.getPayment(orderNo);
    }
}
