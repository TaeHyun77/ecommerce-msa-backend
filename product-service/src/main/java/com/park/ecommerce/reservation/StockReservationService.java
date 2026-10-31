package com.park.ecommerce.reservation;

import com.park.ecommerce.exception.reservation.ReservationErrorCode;
import com.park.ecommerce.exception.reservation.ReservationException;
import com.park.ecommerce.product.ProductService;
import com.park.ecommerce.reservation.domain.StockReservation;
import com.park.ecommerce.reservation.domain.StockReservationRepository;
import com.park.ecommerce.reservation.dto.StockReservationRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

// 주문 재고 선점 - 선점 시점에 재고를 바로 차감하고, 해제되면 복구
@Slf4j
@Service
@RequiredArgsConstructor
public class StockReservationService {
    private final StockReservationRepository stockReservationRepository;
    private final ProductService productService;

    // 재고 선점
    // 같은 주문번호로 이미 선점돼 있으면 성공으로 끝냄 - 결과를 모르는 order-service가 같은 요청을 다시 보내도 재고는 한 번만 차감
    // 같은 주문번호가 동시에 들어오면 늦은 쪽은 선점 내역 저장에서 유니크 제약에 막히고, StockReservationFacade가 성공으로 응답
    @Transactional
    public void reserve(StockReservationRequest request) {
        if (stockReservationRepository.existsByOrderNo(request.orderNo())) {
            log.debug("[재고 선점] 이미 선점된 주문 orderNo={}", request.orderNo());
            return;
        }

        // 특정 주문에 대한 선점 내역 저장
        StockReservation reservation = StockReservation.builder()
                .orderNo(request.orderNo())
                .expiresAt(request.expiresAt())
                .build();

        // 선점 내역에 대한 상품들 정보
        request.items().forEach(item -> reservation.addLine(item.productId(), item.quantity()));

        // 재고 차감보다 먼저 저장 - 같은 주문번호의 동시 요청이 유니크 키에서 먼저 온 요청의 커밋을 기다리게 해,
        // 먼저 온 요청이 가져간 재고 때문에 늦은 요청이 재고 부족으로 잘못 거절되지 않도록 함
        stockReservationRepository.saveAndFlush(reservation);

        productService.decreaseStocks(request.quantities()); // 재고 차감

        log.debug("[재고 선점] 선점 완료 orderNo={}, 만료 시각={}", request.orderNo(), request.expiresAt());
    }

    // 만료 시각이 지났으면 아직 해제 전(RESERVED)이어도 확정하지 않는다
    // 상태만 보면 같은 시각의 요청도 스케줄러가 먼저 돌았는지에 따라 성공하기도, 실패하기도 하므로
    @Transactional
    public void confirm(String orderNo) {
        if (stockReservationRepository.confirm(orderNo, LocalDateTime.now()) == 1) {
            log.debug("[재고 선점] 확정 orderNo={}", orderNo);
            return;
        }

        StockReservation reservation = stockReservationRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new ReservationException(ReservationErrorCode.RESERVATION_NOT_FOUND));
        // 승인 결과를 몰라 order-service가 다시 확정을 요청한 경우 - 이미 확정됐으면 성공으로 응답
        if (reservation.isConfirmed()) return;

        throw new ReservationException(ReservationErrorCode.RESERVATION_EXPIRED);
    }

    // 이미 해제됐거나 선점이 없으면 아무것도 하지 않는다 - 승인 복구 과정에서 같은 해제가 반복돼도 재고가 한 번만 복구되도록
    @Transactional
    public void release(String orderNo) {
        if (stockReservationRepository.release(orderNo) == 0) {
            return;
        }

        StockReservation reservation = stockReservationRepository.findByOrderNo(orderNo).orElseThrow();
        productService.increaseStocks(reservation.quantities());
        log.debug("[재고 선점] 해제 orderNo={}", orderNo);
    }

    @Transactional
    public void expire(Long reservationId) {
        if (stockReservationRepository.expire(reservationId, LocalDateTime.now()) == 0) {
            return;
        }

        StockReservation reservation = stockReservationRepository.findById(reservationId).orElseThrow();
        productService.increaseStocks(reservation.quantities());
        log.debug("[재고 선점] 만료 해제 orderNo={}", reservation.getOrderNo());
    }
}
