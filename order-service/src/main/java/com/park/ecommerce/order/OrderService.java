package com.park.ecommerce.order;

import com.park.ecommerce.exception.OrderErrorCode;
import com.park.ecommerce.exception.OrderException;
import com.park.ecommerce.order.domain.Order;
import com.park.ecommerce.order.domain.OrderRepository;
import com.park.ecommerce.order.dto.OrderDetailResponse;
import com.park.ecommerce.order.dto.OrderResponse;
import com.park.ecommerce.order.dto.OrderSummaryResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@Transactional(readOnly = true)
public class OrderService {
    private final OrderRepository orderRepository;
    private final Duration staleAfter;
    private final Duration giveUpAfter;

    public OrderService(
            OrderRepository orderRepository,
            @Value("${order.approval-recovery.stale-after}") Duration staleAfter,
            @Value("${order.approval-recovery.give-up-after}") Duration giveUpAfter
    ) {
        this.orderRepository = orderRepository;
        this.staleAfter = staleAfter;
        this.giveUpAfter = giveUpAfter;
    }

    // 주문 목록 조회
    public List<OrderResponse> getOrdersByMemberId(Long memberId) {
        return orderRepository.findAllByMemberIdOrderByOrderedAtDesc(memberId).stream()
                .map(OrderResponse::from)
                .toList();
    }

    // 주문 목록 조회
    public List<OrderSummaryResponse> getOrderSummaries(Long memberId) {
        return orderRepository.findAllWithLinesByMemberIdOrderByOrderedAtDesc(memberId).stream()
                .map(OrderSummaryResponse::from)
                .toList();
    }

    // 단건 상세 주문 조회
    public OrderDetailResponse getOrder(Long memberId, String orderNo) {
        Order order = orderRepository.findByOrderNoAndMemberId(orderNo, memberId)
                .orElseThrow(() -> new OrderException(OrderErrorCode.ORDER_NOT_FOUND));

        return OrderDetailResponse.from(order);
    }

    // 승인 중으로 바꾼 뒤 바로 커밋하여, 이후 원격 호출 중에 서버가 멈춰도 복구 스케줄러가 이 주문을 찾을 수 있도록 함
    // 다음 시도 시각을 staleAfter 뒤로 적어 두므로, 서버가 멈춰 결과 불명을 기록하지 못한 주문도 그때 다시 처리됨
    @Transactional
    public boolean startApproval(String orderNo, String paymentKey) {
        LocalDateTime now = LocalDateTime.now();
        return orderRepository.startApproval(orderNo, paymentKey, now, now.plus(staleAfter)) == 1;
    }

    // 승인 처리가 결과를 모른 채 끝났을 때 호출 - 다음 시도 시각을 지금으로 당겨 다음 주기에 바로 다시 처리되도록 함
    // 재시도 상한을 넘긴 주문은 이미 승인됐을 수 있어 자동으로 실패 처리하지 않고, 재시도만 멈춘 뒤 토스에서 결제 상태를 확인해 수동으로 처리하도록 알림
    // 상한을 시도 뒤에 판단하므로, 서버가 오래 멈췄다 켜져 상한을 넘긴 주문도 한 번은 다시 처리됨
    @Transactional
    public void markApprovalUnknown(String orderNo) {
        LocalDateTime now = LocalDateTime.now();
        if (orderRepository.giveUpApproval(orderNo, now.minus(giveUpAfter)) == 1) {
            // 선점이 확정된 채로 남아 재고와 쿠폰이 묶여 있으므로 수동 처리 때 재고 선점과 쿠폰 해제도 함께 해야 함
            log.error("[결제 복구] 재시도 상한 초과, 수동 확인 필요 orderNo={}", orderNo);
            return;
        }
        orderRepository.markApprovalUnknown(orderNo, now);
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
