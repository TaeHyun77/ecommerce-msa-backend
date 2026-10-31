package com.park.ecommerce.reservation;

import com.park.ecommerce.reservation.dto.StockReservationRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 게이트웨이가 외부로 노출하지 않는 서비스 간 내부 통신 전용 API - order-service가 주문/결제 흐름에서 호출함
// 선점,확정,해제는 멱등적으로 동작
@RestController
@RequestMapping("/internal/stock-reservations")
@RequiredArgsConstructor
public class StockReservationController {
    private final StockReservationFacade stockReservationFacade;
    private final StockReservationService stockReservationService;

    // 선점
    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reserve(@Valid @RequestBody StockReservationRequest request) {
        stockReservationFacade.reserve(request);
    }

    // confirm으로 상태 변경
    @PostMapping("/{orderNo}/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirm(@PathVariable String orderNo) {
        stockReservationService.confirm(orderNo);
    }

    // release으로 상태 변경
    @PostMapping("/{orderNo}/release")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@PathVariable String orderNo) {
        stockReservationService.release(orderNo);
    }
}
