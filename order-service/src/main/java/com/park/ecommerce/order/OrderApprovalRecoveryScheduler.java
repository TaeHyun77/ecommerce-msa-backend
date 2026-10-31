package com.park.ecommerce.order;

import com.park.ecommerce.order.domain.OrderRepository;
import com.park.ecommerce.order.domain.OrderStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

// 결과를 모른 채 승인 중(APPROVING)으로 멈춘 주문을 주기적으로 다시 처리해 결제 완료 또는 실패로 확정함
@Slf4j
@Component
public class OrderApprovalRecoveryScheduler {
    private static final int BATCH_SIZE = 100;

    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final OrderPaymentService orderPaymentService;
    private final Duration staleAfter;
    private final Duration giveUpAfter;

    public OrderApprovalRecoveryScheduler(
            OrderRepository orderRepository,
            OrderService orderService,
            OrderPaymentService orderPaymentService,
            @Value("${order.approval-recovery.stale-after}") Duration staleAfter,
            @Value("${order.approval-recovery.give-up-after}") Duration giveUpAfter
    ) {
        this.orderRepository = orderRepository;
        this.orderService = orderService;
        this.orderPaymentService = orderPaymentService;
        this.staleAfter = staleAfter;
        this.giveUpAfter = giveUpAfter;
    }

    @Scheduled(fixedDelayString = "${order.approval-recovery.poll-delay}")
    public void recoverApprovals() {
        retryApprovals();
        reportGivenUpApprovals();
    }

    // 결과 불명이 확정된 주문은 바로, 표시가 없는 주문은 승인을 시작한 지 staleAfter가 지난 뒤에 다시 처리
    // 표시가 없는 주문은 아직 진행 중인 요청일 수 있고, 서버가 요청 중에 멈춰 표시를 못 남긴 주문일 수도 있어 진행 중인 요청과 같은 주문을 동시에 처리하지 않도록 기다림
    private void retryApprovals() {
        LocalDateTime now = LocalDateTime.now();
        List<String> orderNos = orderRepository.findRecoverableApprovingOrderNos(
                now.minus(staleAfter), now.minus(giveUpAfter), PageRequest.of(0, BATCH_SIZE)
        );

        for (String orderNo : orderNos) {
            try {
                OrderStatus status = orderPaymentService.recover(orderNo);
                log.info("[결제 복구] orderNo={}, 결과={}", orderNo, status);
            } catch (RuntimeException e) {
                // 한 건의 실패가 같은 회차의 나머지 건 처리를 막지 않도록 건 단위로 격리 - 실패한 건은 다음 회차에 다시 대상이 됨
                log.error("[결제 복구] 예상하지 못한 오류 orderNo={}", orderNo, e);
            }
        }
    }

    // 상한까지 확정되지 않은 주문은 이미 승인됐을 수 있어 자동으로 실패 처리하지 않고, 토스에서 결제 상태를 확인해 수동으로 처리하도록 알림
    // 선점이 확정된 채로 남아 재고와 쿠폰이 묶여 있으므로 수동 처리 때 재고 선점과 쿠폰 해제도 함께 해야 함
    private void reportGivenUpApprovals() {
        List<String> orderNos = orderRepository.findGivenUpApprovingOrderNos(
                LocalDateTime.now().minus(giveUpAfter), PageRequest.of(0, BATCH_SIZE)
        );

        for (String orderNo : orderNos) {
            log.error("[결제 복구] 재시도 상한 초과, 수동 확인 필요 orderNo={}", orderNo);
            orderService.clearApprovalUnknown(orderNo); // 같은 건을 매 주기마다 알리지 않도록 표시 해제
        }
    }
}
