package com.park.ecommerce.order;

import com.park.ecommerce.order.domain.OrderRepository;
import com.park.ecommerce.order.domain.OrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

// 결과를 모른 채 승인 중(APPROVING)으로 멈춘 주문을 주기적으로 다시 처리하여 결제 완료 또는 실패로 확정함
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderApprovalRecoveryScheduler {
    private static final int BATCH_SIZE = 100;

    private final OrderRepository orderRepository;
    private final OrderPaymentService orderPaymentService;

    // 다음 시도 시각이 지난 승인 중 주문을 다시 처리 - 언제 다시 처리할지와 재시도 상한은 OrderService가 그 시각을 적거나 비워서 정함
    @Scheduled(fixedDelayString = "${order.approval-recovery.poll-delay}")
    public void recoverApprovals() {
        List<String> orderNos = orderRepository.findRecoverableApprovingOrderNos(LocalDateTime.now(), PageRequest.of(0, BATCH_SIZE));

        for (String orderNo : orderNos) {
            try {
                OrderStatus status = orderPaymentService.recoverApproval(orderNo);
                log.info("[결제 복구] orderNo={}, 결과={}", orderNo, status);
            } catch (RuntimeException e) {
                // 한 건의 실패가 같은 회차의 나머지 건 처리를 막지 않도록 건 단위로 격리 - 실패한 건은 다음 회차에 다시 대상이 됨
                log.error("[결제 복구] 예상하지 못한 오류 orderNo={}", orderNo, e);
            }
        }
    }
}
