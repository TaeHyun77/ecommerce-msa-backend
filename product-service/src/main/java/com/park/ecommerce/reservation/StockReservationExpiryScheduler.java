package com.park.ecommerce.reservation;

import com.park.ecommerce.reservation.domain.StockReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class StockReservationExpiryScheduler {
    private static final int BATCH_SIZE = 100;

    private final StockReservationRepository stockReservationRepository;
    private final StockReservationService stockReservationService;

    // 주기마다 만료 시각이 지난 선점을 최대 100건 가져와 한 건씩 해제
    @Scheduled(fixedDelayString = "${stock.reservation.expire-poll-delay}")
    public void releaseExpiredReservations() {
        List<Long> targetIds = stockReservationRepository.findExpiredIds(LocalDateTime.now(), PageRequest.of(0, BATCH_SIZE));

        for (Long reservationId : targetIds) {
            try {
                stockReservationService.expire(reservationId);
            } catch (RuntimeException e) {
                // 한 건의 실패가 같은 회차의 나머지 건 처리를 막지 않도록 건 단위로 격리 - 실패한 건은 다음 회차에 다시 대상이 됨
                log.error("[재고 선점] 만료 해제 중 오류 reservationId={}", reservationId, e);
            }
        }
    }
}
