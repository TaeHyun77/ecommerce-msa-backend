package com.park.ecommerce.order.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {
    List<Order> findAllByMemberIdOrderByOrderedAtDesc(Long memberId);

    // 목록에서 품목까지 보여주므로 한 번에 함께 조회 - 주문마다 품목을 따로 조회하는 N+1을 막음
    @EntityGraph(attributePaths = "lines")
    List<Order> findAllWithLinesByMemberIdOrderByOrderedAtDesc(Long memberId);

    Optional<Order> findByOrderNo(String orderNo);

    Optional<Order> findByOrderNoAndMemberId(String orderNo, Long memberId);

    // 기한이 지난 모든 결제 대기 주문을 FAILED로
    // 재고·쿠폰 복구는 product-service·promotion-service가 선점 만료로 따로 처리하므로 건별 후처리 없이 한 번에 실패 처리
    @Modifying
    @Query("update Order o set o.status = FAILED where o.status = PAYMENT_WAITING and o.expiresAt <= :now")
    int failExpired(LocalDateTime now);

    // 결제 대기이고 결제 기한 전인 주문만 승인 중으로 바꿈
    // 같은 주문의 승인 요청이 동시에 오거나 만료 처리와 겹쳐도 한쪽만 반영되고, 결제 기한이 지난 주문은 만료 처리 전이어도 승인하지 않음
    @Modifying
    @Query("update Order o set o.status = APPROVING, o.paymentKey = :paymentKey, o.approvalRequestedAt = :now, o.nextRetryAt = :nextRetryAt where o.orderNo = :orderNo and o.status = PAYMENT_WAITING and o.expiresAt > :now")
    int startApproval(String orderNo, String paymentKey, LocalDateTime now, LocalDateTime nextRetryAt);

    @Modifying
    @Query("update Order o set o.status = PAID, o.nextRetryAt = null where o.orderNo = :orderNo and o.status = APPROVING")
    int markPaid(String orderNo);

    @Modifying
    @Query("update Order o set o.status = FAILED, o.nextRetryAt = null where o.orderNo = :orderNo and o.status = APPROVING")
    int failApproving(String orderNo);

    @Modifying
    @Query("update Order o set o.nextRetryAt = :now where o.orderNo = :orderNo and o.status = APPROVING")
    int markApprovalUnknown(String orderNo, LocalDateTime now);

    // 재시도 상한을 넘긴 주문만 다음 시도 시각을 비움 - 비운 주문은 복구 대상에서 빠지므로 건당 한 번만 반영됨
    @Modifying
    @Query("update Order o set o.nextRetryAt = null where o.orderNo = :orderNo and o.status = APPROVING and o.approvalRequestedAt <= :giveUpBefore")
    int giveUpApproval(String orderNo, LocalDateTime giveUpBefore);

    // 복구 대상 - 다음 시도 시각이 지난 승인 중 주문
    // 다시 처리해도 결과를 모르는 주문은 시각이 뒤로 가므로, 오래 기다린 주문부터 돌아가며 처리됨
    @Query("select o.orderNo from Order o where o.status = APPROVING and o.nextRetryAt <= :now order by o.nextRetryAt")
    List<String> findRecoverableApprovingOrderNos(LocalDateTime now, Pageable pageable);
}
