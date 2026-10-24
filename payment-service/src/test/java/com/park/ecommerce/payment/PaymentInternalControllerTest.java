package com.park.ecommerce.payment;

import com.park.ecommerce.exception.PaymentErrorCode;
import com.park.ecommerce.exception.PaymentException;
import com.park.ecommerce.payment.domain.PaymentStatus;
import com.park.ecommerce.payment.dto.PaymentApproveRequest;
import com.park.ecommerce.payment.dto.PaymentApproveResponse;
import com.park.ecommerce.payment.dto.PaymentResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import java.time.LocalDateTime;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentInternalController.class)
class PaymentInternalControllerTest {
    private static final PaymentApproveRequest REQUEST = new PaymentApproveRequest("ORDER-0001", "tgen_pay_0001", 7_800);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService paymentService;

    // 메인 클래스의 @EnableJpaAuditing이 웹 슬라이스 테스트에서도 JPA 메타모델을 요구하므로 대체
    @MockitoBean
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    @DisplayName("승인 결과가 확정되면 200과 결제 상태를 응답한다")
    void respondsApprovedResult() throws Exception {
        given(paymentService.approve(REQUEST)).willReturn(new PaymentApproveResponse("ORDER-0001", PaymentStatus.DONE, null));

        mockMvc.perform(approve(approveRequest("tgen_pay_0001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNo").value("ORDER-0001"))
                .andExpect(jsonPath("$.status").value("DONE"));
    }

    @Test
    @DisplayName("승인 결과를 모르면 503과 결과 불명 코드를 응답한다 - order-service가 다시 요청할지 판단하는 기준")
    void respondsServiceUnavailableWhenResultUnknown() throws Exception {
        given(paymentService.approve(REQUEST)).willThrow(new PaymentException(PaymentErrorCode.PAYMENT_RESULT_UNKNOWN));

        mockMvc.perform(approve(approveRequest("tgen_pay_0001")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PAYMENT_RESULT_UNKNOWN"));
    }

    @Test
    @DisplayName("같은 주문번호로 다른 결제가 오면 409를 응답한다")
    void respondsConflictForDifferentPayment() throws Exception {
        given(paymentService.approve(REQUEST)).willThrow(new PaymentException(PaymentErrorCode.PAYMENT_REQUEST_MISMATCH));

        mockMvc.perform(approve(approveRequest("tgen_pay_0001")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_REQUEST_MISMATCH"));
    }

    @Test
    @DisplayName("결제 키가 비어 있으면 400을 응답한다")
    void rejectsBlankPaymentKey() throws Exception {
        mockMvc.perform(approve(approveRequest("")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("결제 정보를 조회하면 200과 결제 상태·금액·결제 수단·승인 시각을 응답하고 결제 키는 내보내지 않는다")
    void respondsPayment() throws Exception {
        given(paymentService.getPayment("ORDER-0001")).willReturn(
                new PaymentResponse("ORDER-0001", PaymentStatus.DONE, 7_800, "카드", LocalDateTime.of(2026, 9, 22, 12, 0, 5)));

        mockMvc.perform(get("/internal/payments/ORDER-0001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.amount").value(7_800))
                .andExpect(jsonPath("$.method").value("카드"))
                .andExpect(jsonPath("$.approvedAt").value("2026-09-22T12:00:05"))
                .andExpect(jsonPath("$.paymentKey").doesNotExist());
    }

    @Test
    @DisplayName("결제 기록이 없으면 404와 결제 없음 코드를 응답한다")
    void respondsNotFoundWhenNoPayment() throws Exception {
        given(paymentService.getPayment("ORDER-0001")).willThrow(new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND));

        mockMvc.perform(get("/internal/payments/ORDER-0001"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
    }

    private static RequestBuilder approve(String body) {
        return post("/internal/payments/approve").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String approveRequest(String paymentKey) {
        return """
                { "orderNo": "ORDER-0001", "paymentKey": "%s", "amount": 7800 }
                """.formatted(paymentKey);
    }
}
