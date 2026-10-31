package com.park.ecommerce.reservation;

import com.park.ecommerce.exception.reservation.ReservationErrorCode;
import com.park.ecommerce.exception.reservation.ReservationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StockReservationController.class)
class StockReservationControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StockReservationFacade stockReservationFacade;

    @MockitoBean
    private StockReservationService stockReservationService;

    // 메인 클래스의 @EnableJpaAuditing이 웹 슬라이스 테스트에서도 JPA 메타모델을 요구하므로 대체
    @MockitoBean
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    @DisplayName("재고를 선점하면 204를 응답한다")
    void reserves() throws Exception {
        mockMvc.perform(post("/internal/stock-reservations").contentType(MediaType.APPLICATION_JSON).content(reservation("2099-01-01T00:00:00")))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("만료 시각이 이미 지난 선점 요청은 400을 응답한다")
    void rejectsPastExpiresAt() throws Exception {
        mockMvc.perform(post("/internal/stock-reservations").contentType(MediaType.APPLICATION_JSON).content(reservation("2000-01-01T00:00:00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("선점을 확정하면 204를 응답한다")
    void confirms() throws Exception {
        mockMvc.perform(post("/internal/stock-reservations/ORDER-0001/confirm"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("만료되거나 해제된 선점을 확정하면 409와 오류 코드를 응답한다")
    void rejectsConfirmOfExpiredReservation() throws Exception {
        willThrow(new ReservationException(ReservationErrorCode.RESERVATION_EXPIRED)).given(stockReservationService).confirm("ORDER-0001");

        mockMvc.perform(post("/internal/stock-reservations/ORDER-0001/confirm"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_EXPIRED"));
    }

    @Test
    @DisplayName("선점을 해제하면 204를 응답한다")
    void releases() throws Exception {
        mockMvc.perform(post("/internal/stock-reservations/ORDER-0001/release"))
                .andExpect(status().isNoContent());
    }

    private static String reservation(String expiresAt) {
        return """
                {
                  "orderNo": "ORDER-0001",
                  "expiresAt": "%s",
                  "items": [ { "productId": 1, "quantity": 2 } ]
                }
                """.formatted(expiresAt);
    }
}
