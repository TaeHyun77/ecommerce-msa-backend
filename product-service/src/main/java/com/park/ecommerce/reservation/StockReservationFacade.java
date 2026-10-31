package com.park.ecommerce.reservation;

import com.park.ecommerce.reservation.domain.StockReservationRepository;
import com.park.ecommerce.reservation.dto.StockReservationRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 같은 주문번호의 선점 요청이 동시에 들어오면 늦은 쪽은 선점 내역의 유니크 제약에 막힘
 * 이 실패는 트랜잭션 안에서 성공으로 바꿀 수 없어(롤백 전용으로 표시됨) 트랜잭션이 끝난 뒤 이 클래스에서 다시 확인
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockReservationFacade {
    private final StockReservationService stockReservationService;
    private final StockReservationRepository stockReservationRepository;

    public void reserve(StockReservationRequest request) {
        try {
            stockReservationService.reserve(request);
        } catch (DataIntegrityViolationException e) {
            // 유니크 제약 외의 위반까지 성공으로 숨기지 않도록 같은 주문번호의 선점이 실제로 있는지 확인
            if (!stockReservationRepository.existsByOrderNo(request.orderNo())) throw e;
            log.debug("[재고 선점] 같은 주문의 동시 요청이 먼저 선점함 orderNo={}", request.orderNo());
        }
    }
}
