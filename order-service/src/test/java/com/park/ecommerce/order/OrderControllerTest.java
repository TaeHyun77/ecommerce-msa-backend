package com.park.ecommerce.order;

import com.park.ecommerce.exception.OrderErrorCode;
import com.park.ecommerce.exception.OrderException;
import com.park.ecommerce.order.domain.OrderStatus;
import com.park.ecommerce.order.dto.OrderCreateRequest;
import com.park.ecommerce.order.dto.OrderCreateResponse;
import com.park.ecommerce.order.dto.OrderDetailResponse;
import com.park.ecommerce.order.dto.OrderPaymentRequest;
import com.park.ecommerce.order.dto.OrderPaymentResponse;
import com.park.ecommerce.order.dto.OrderSummaryResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
class OrderControllerTest {
    private static final String MEMBER_ID_HEADER = "X-Member-Id";
    private static final LocalDateTime EXPIRES_AT = LocalDateTime.of(2026, 9, 22, 12, 10);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderPlacementService orderPlacementService;

    @MockitoBean
    private OrderService orderService;

    @MockitoBean
    private OrderPaymentService orderPaymentService;

    // 메인 클래스의 @EnableJpaAuditing이 웹 슬라이스 테스트에서도 JPA 메타모델을 요구하므로 대체
    @MockitoBean
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    @DisplayName("주문하면 201과 결제창에 필요한 주문번호·주문명·금액·결제 기한을 응답한다")
    void createsOrder() throws Exception {
        OrderCreateRequest request = new OrderCreateRequest(List.of(new OrderCreateRequest.Item(1L, 2)), null);
        given(orderPlacementService.place(1L, request))
                .willReturn(new OrderCreateResponse("ORDER-0001", "유기농 우유 900ml", 7_800, EXPIRES_AT, OrderStatus.PAYMENT_WAITING));

        mockMvc.perform(post("/api/orders")
                        .header(MEMBER_ID_HEADER, 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderRequest(2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderNo").value("ORDER-0001"))
                .andExpect(jsonPath("$.orderName").value("유기농 우유 900ml"))
                .andExpect(jsonPath("$.amount").value(7_800))
                .andExpect(jsonPath("$.expiresAt").value("2026-09-22T12:10:00"))
                .andExpect(jsonPath("$.status").value("PAYMENT_WAITING"));
    }

    @Test
    @DisplayName("쿠폰을 지정해 주문하면 회원 쿠폰 식별자를 함께 넘긴다")
    void createsOrderWithCoupon() throws Exception {
        OrderCreateRequest request = new OrderCreateRequest(List.of(new OrderCreateRequest.Item(1L, 2)), 10L);
        given(orderPlacementService.place(1L, request))
                .willReturn(new OrderCreateResponse("ORDER-0001", "유기농 우유 900ml", 5_800, EXPIRES_AT, OrderStatus.PAYMENT_WAITING));

        mockMvc.perform(post("/api/orders")
                        .header(MEMBER_ID_HEADER, 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "items": [ { "productId": 1, "quantity": 2 } ], "memberCouponId": 10 }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(5_800));
    }

    @Test
    @DisplayName("품목 수량이 1회 구매 제한(99개)을 넘으면 400을 응답한다")
    void rejectsQuantityOverLimit() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .header(MEMBER_ID_HEADER, 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderRequest(100)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("주문 품목이 비어 있으면 400을 응답한다")
    void rejectsEmptyItems() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .header(MEMBER_ID_HEADER, 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"items\": [] }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("회원 식별 헤더가 없으면 400을 응답한다")
    void rejectsMissingMemberHeader() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderRequest(2)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("주문을 조회하면 200과 주문 상태·품목을 응답한다")
    void getsOrder() throws Exception {
        given(orderPaymentService.getOrder(1L, "ORDER-0001")).willReturn(new OrderDetailResponse(
                "ORDER-0001", OrderStatus.PAYMENT_WAITING, "유기농 우유 900ml", 7_800, 0, 7_800, EXPIRES_AT.minusMinutes(10), EXPIRES_AT,
                List.of(new OrderDetailResponse.Line(1L, "유기농 우유 900ml", 3_900, 2)), null
        ));

        mockMvc.perform(get("/api/orders/ORDER-0001").header(MEMBER_ID_HEADER, 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAYMENT_WAITING"))
                .andExpect(jsonPath("$.lines[0].productName").value("유기농 우유 900ml"))
                .andExpect(jsonPath("$.payment").doesNotExist());
    }

    @Test
    @DisplayName("결제된 주문을 조회하면 결제 정보도 응답한다")
    void getsPaidOrderWithPayment() throws Exception {
        given(orderPaymentService.getOrder(1L, "ORDER-0001")).willReturn(new OrderDetailResponse(
                "ORDER-0001", OrderStatus.PAID, "유기농 우유 900ml", 7_800, 0, 7_800, EXPIRES_AT.minusMinutes(10), EXPIRES_AT,
                List.of(new OrderDetailResponse.Line(1L, "유기농 우유 900ml", 3_900, 2)),
                new OrderDetailResponse.Payment("DONE", 7_800, "카드", EXPIRES_AT.minusMinutes(5))
        ));

        mockMvc.perform(get("/api/orders/ORDER-0001").header(MEMBER_ID_HEADER, 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.status").value("DONE"))
                .andExpect(jsonPath("$.payment.amount").value(7_800))
                .andExpect(jsonPath("$.payment.method").value("카드"))
                .andExpect(jsonPath("$.payment.approvedAt").value("2026-09-22T12:05:00"));
    }

    @Test
    @DisplayName("주문 목록을 조회하면 200과 주문번호·주문일·상품 정보를 응답한다")
    void getsOrders() throws Exception {
        given(orderService.getOrderSummaries(1L)).willReturn(List.of(new OrderSummaryResponse(
                "ORDER-0001", EXPIRES_AT.minusMinutes(10),
                List.of(new OrderDetailResponse.Line(1L, "유기농 우유 900ml", 3_900, 2))
        )));

        mockMvc.perform(get("/api/orders").header(MEMBER_ID_HEADER, 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].orderNo").value("ORDER-0001"))
                .andExpect(jsonPath("$[0].orderedAt").value("2026-09-22T12:00:00"))
                .andExpect(jsonPath("$[0].lines[0].productName").value("유기농 우유 900ml"))
                .andExpect(jsonPath("$[0].lines[0].quantity").value(2))
                .andExpect(jsonPath("$[0].status").doesNotExist());
    }

    @Test
    @DisplayName("본인 주문이 아니거나 없는 주문을 조회하면 404를 응답한다")
    void respondsNotFoundForOthersOrder() throws Exception {
        given(orderPaymentService.getOrder(1L, "ORDER-0001")).willThrow(new OrderException(OrderErrorCode.ORDER_NOT_FOUND));

        mockMvc.perform(get("/api/orders/ORDER-0001").header(MEMBER_ID_HEADER, 1L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    @DisplayName("결제가 완료되면 200과 결제 완료 상태를 응답한다")
    void respondsOkWhenPaid() throws Exception {
        given(orderPaymentService.approve(1L, "ORDER-0001", new OrderPaymentRequest("tgen_pay_0001", 7_800)))
                .willReturn(new OrderPaymentResponse("ORDER-0001", OrderStatus.PAID));

        mockMvc.perform(post("/api/orders/ORDER-0001/payment")
                        .header(MEMBER_ID_HEADER, 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentRequest("tgen_pay_0001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));
    }

    @Test
    @DisplayName("결제 결과를 아직 모르면 202와 승인 중 상태를 응답한다 - 클라이언트는 주문 조회로 최종 결과를 확인")
    void respondsAcceptedWhenApproving() throws Exception {
        given(orderPaymentService.approve(1L, "ORDER-0001", new OrderPaymentRequest("tgen_pay_0001", 7_800)))
                .willReturn(new OrderPaymentResponse("ORDER-0001", OrderStatus.APPROVING));

        mockMvc.perform(post("/api/orders/ORDER-0001/payment")
                        .header(MEMBER_ID_HEADER, 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentRequest("tgen_pay_0001")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("APPROVING"));
    }

    @Test
    @DisplayName("선점 만료로 결제하지 못하면 409와 만료 코드를 응답한다")
    void respondsConflictWhenExpired() throws Exception {
        given(orderPaymentService.approve(1L, "ORDER-0001", new OrderPaymentRequest("tgen_pay_0001", 7_800)))
                .willThrow(new OrderException(OrderErrorCode.ORDER_EXPIRED));

        mockMvc.perform(post("/api/orders/ORDER-0001/payment")
                        .header(MEMBER_ID_HEADER, 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentRequest("tgen_pay_0001")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_EXPIRED"));
    }

    @Test
    @DisplayName("결제 키가 비어 있으면 400을 응답한다")
    void rejectsBlankPaymentKey() throws Exception {
        mockMvc.perform(post("/api/orders/ORDER-0001/payment")
                        .header(MEMBER_ID_HEADER, 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentRequest("")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    private static String paymentRequest(String paymentKey) {
        return """
                { "paymentKey": "%s", "amount": 7800 }
                """.formatted(paymentKey);
    }

    private static String orderRequest(int quantity) {
        return """
                { "items": [ { "productId": 1, "quantity": %d } ] }
                """.formatted(quantity);
    }
}
