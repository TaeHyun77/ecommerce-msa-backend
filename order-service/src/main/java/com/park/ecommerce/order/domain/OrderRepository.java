package com.park.ecommerce.order.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {
    List<Order> findAllByMemberIdOrderByOrderedAtDesc(Long memberId);

    Optional<Order> findByOrderNo(String orderNo);

    Optional<Order> findByOrderNoAndMemberId(String orderNo, Long memberId);

    // 해당 주문을 FAILED 상태로 변경
    // 상태는 조건부 UPDATE로만 바꿈 - 결제 대기가 아닌 주문(결제 중/완료)을 늦게 도착한 실패 처리가 덮어쓰지 않도록
    @Modifying
    @Query("update Order o set o.status = FAILED where o.orderNo = :orderNo and o.status = PAYMENT_WAITING")
    int failPaymentWaiting(String orderNo);

    // 기한이 지난 모든 결제 대기 주문을 FAILED로
    // 재고 복구는 product-service가 선점 만료로 따로 처리하므로 건별 후처리 없이 한 번에 실패 처리
    @Modifying
    @Query("update Order o set o.status = FAILED where o.status = PAYMENT_WAITING and o.expiresAt <= :now")
    int failExpired(LocalDateTime now);

    // 결제 대기이고 결제 기한 전인 주문만 승인 중으로 바꿈
    // 같은 주문의 승인 요청이 동시에 오거나 만료 처리와 겹쳐도 한쪽만 반영되고, 결제 기한이 지난 주문은 만료 처리 전이어도 승인하지 않음
    @Modifying
    @Query("update Order o set o.status = APPROVING, o.paymentKey = :paymentKey, o.approvalRequestedAt = :now where o.orderNo = :orderNo and o.status = PAYMENT_WAITING and o.expiresAt > :now")
    int startApproval(String orderNo, String paymentKey, LocalDateTime now);

    @Modifying
    @Query("update Order o set o.status = PAID where o.orderNo = :orderNo and o.status = APPROVING")
    int markPaid(String orderNo);

    @Modifying
    @Query("update Order o set o.status = FAILED where o.orderNo = :orderNo and o.status = APPROVING")
    int failApproving(String orderNo);

    @Modifying
    @Query("update Order o set o.approvalUnknownAt = :now where o.orderNo = :orderNo and o.status = APPROVING")
    int markApprovalUnknown(String orderNo, LocalDateTime now);

    @Modifying
    @Query("update Order o set o.approvalUnknownAt = null where o.orderNo = :orderNo and o.status = APPROVING")
    int clearApprovalUnknown(String orderNo);

    // 복구 대상 - 상한 안의 승인 중 주문 중 결과 불명이 확정된 건, 또는 승인 시작 후 staleBefore를 넘겨 서버가 멈췄을 수 있는 건
    @Query("""
            select o.orderNo from Order o
            where o.status = APPROVING and o.approvalRequestedAt > :giveUpBefore
              and (o.approvalUnknownAt is not null or o.approvalRequestedAt <= :staleBefore)
            order by o.id
            """)
    List<String> findRecoverableApprovingOrderNos(LocalDateTime staleBefore, LocalDateTime giveUpBefore, Pageable pageable);

    // 재시도 상한을 넘긴 건 - 표시가 남아 있는 건만 골라 알린 뒤 표시를 해제하므로 건당 한 번만 조회됨
    @Query("select o.orderNo from Order o where o.status = APPROVING and o.approvalUnknownAt is not null and o.approvalRequestedAt <= :giveUpBefore order by o.id")
    List<String> findGivenUpApprovingOrderNos(LocalDateTime giveUpBefore, Pageable pageable);
}
