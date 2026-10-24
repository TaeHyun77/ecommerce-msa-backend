package com.park.ecommerce.order.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// 회원의 주문 1건 - 주문 시점의 상품명/단가를 품목에 스냅샷으로 보관
@Entity
@Table(name = "orders") // order는 MySQL 예약어라 테이블명으로 사용할 수 없음
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 외부와 다른 서비스에 노출하는 주문 식별자 - 토스페이먼츠 orderId 규격(영문/숫자·-·_ 6~64자)을 만족하는 UUID
    // 순번 id를 노출하지 않아 다른 주문을 추측할 수 없도록 함
    @Column(nullable = false, unique = true, length = 36)
    private String orderNo;

    // member-service의 Member를 직접 참조하지 않고 식별자만 보관
    @Column(nullable = false)
    private Long memberId;

    @Column(nullable = false)
    private Integer totalAmount; // 주문 총액 (원)

    // 엔티티 UPDATE에서 제외 - 상태는 OrderRepository의 조건부 UPDATE로만 변경
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private OrderStatus status;

    @Column(nullable = false)
    private LocalDateTime orderedAt;

    @Column(nullable = false)
    private LocalDateTime expiresAt; // 결제 기한 - 재고 선점 만료 시각과 같은 값

    @Column(length = 200) // 토스페이먼츠 paymentKey 최대 길이
    private String paymentKey; // 결제 승인을 시작할 때 기록 - 복구 스케줄러가 같은 결제로 다시 승인을 요청하는 데 사용

    private LocalDateTime approvalRequestedAt; // 결제 승인을 시작한 시각 - 복구 대상을 고르는 기준이자 재시도 상한의 기준

    // 승인 요청이 결과를 모른 채 끝난 시각 - 있으면 승인 시작 후 대기 없이 바로 복구 대상
    // approvalRequestedAt을 덮어쓰지 않고 따로 두어 재시도 상한 계산이 흐트러지지 않도록 함
    private LocalDateTime approvalUnknownAt;

    // 품목은 주문과 생명주기가 같아 함께 저장
    @OneToMany(mappedBy = "order", cascade = CascadeType.PERSIST)
    private List<OrderLine> lines = new ArrayList<>();

    @Builder
    private Order(Long memberId, LocalDateTime orderedAt, LocalDateTime expiresAt) {
        if (memberId == null) {
            throw new IllegalArgumentException("회원은 필수입니다.");
        }

        this.orderNo = UUID.randomUUID().toString();
        this.memberId = memberId;
        this.totalAmount = 0;
        this.status = OrderStatus.PAYMENT_WAITING;
        this.orderedAt = orderedAt;
        this.expiresAt = expiresAt;
    }

    public void addLine(Long productId, String productName, Integer unitPrice, Integer quantity) {
        lines.add(new OrderLine(this, productId, productName, unitPrice, quantity));
        this.totalAmount += unitPrice * quantity;
    }

    // 결제창에 표시할 주문명 - 토스페이먼츠 결제 요청의 orderName으로 사용
    public String getOrderName() {
        String firstProductName = lines.get(0).getProductName();
        return lines.size() == 1 ? firstProductName : firstProductName + " 외 " + (lines.size() - 1) + "건";
    }
}
