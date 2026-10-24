package com.park.ecommerce.order;

import com.park.ecommerce.exception.OrderErrorCode;
import com.park.ecommerce.exception.OrderException;
import com.park.ecommerce.order.domain.Order;
import com.park.ecommerce.order.domain.OrderRepository;
import com.park.ecommerce.order.dto.OrderDetailResponse;
import com.park.ecommerce.order.dto.OrderResponse;
import com.park.ecommerce.order.dto.OrderSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {
    private final OrderRepository orderRepository;

    // 특정 사용자의 주문 목록 조회
    public List<OrderResponse> getOrdersByMemberId(Long memberId) {
        return orderRepository.findAllByMemberIdOrderByOrderedAtDesc(memberId).stream()
                .map(OrderResponse::from)
                .toList();
    }

    // 내 주문 목록 조회 - 최근 주문부터
    public List<OrderSummaryResponse> getOrderSummaries(Long memberId) {
        return orderRepository.findAllWithLinesByMemberIdOrderByOrderedAtDesc(memberId).stream()
                .map(OrderSummaryResponse::from)
                .toList();
    }

    // 주문 조회
    // 다른 회원의 주문은 존재 여부도 드러내지 않도록 없는 주문과 같은 404로 응답
    public OrderDetailResponse getOrder(Long memberId, String orderNo) {
        Order order = orderRepository.findByOrderNoAndMemberId(orderNo, memberId)
                .orElseThrow(() -> new OrderException(OrderErrorCode.ORDER_NOT_FOUND));

        return OrderDetailResponse.from(order);
    }

    @Transactional
    public void fail(String orderNo) {
        orderRepository.failPaymentWaiting(orderNo);
    }

    // 승인 중으로 바꾼 뒤 바로 커밋하여, 이후 원격 호출 중에 서버가 멈춰도 복구 스케줄러가 이 주문을 찾을 수 있도록 함
    @Transactional
    public boolean startApproval(String orderNo, String paymentKey) {
        return orderRepository.startApproval(orderNo, paymentKey, LocalDateTime.now()) == 1;
    }

    // 승인 요청이 이미 끝난 뒤 호출하므로 진행 중인 요청과 겹치지 않아 바로 복구 대상으로 삼아도 됨
    @Transactional
    public void markApprovalUnknown(String orderNo) {
        orderRepository.markApprovalUnknown(orderNo, LocalDateTime.now());
    }

    @Transactional
    public void clearApprovalUnknown(String orderNo) {
        orderRepository.clearApprovalUnknown(orderNo);
    }

    @Transactional
    public void markPaid(String orderNo) {
        orderRepository.markPaid(orderNo);
    }

    @Transactional
    public void failApproving(String orderNo) {
        orderRepository.failApproving(orderNo);
    }

    @Transactional
    public int failExpiredOrders() {
        return orderRepository.failExpired(LocalDateTime.now());
    }
}
