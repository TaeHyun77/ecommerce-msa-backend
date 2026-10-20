package com.park.ecommerce.cart;

import com.park.ecommerce.cart.dto.CartResponse;
import com.park.ecommerce.exception.OrderErrorCode;
import com.park.ecommerce.exception.OrderException;
import com.park.ecommerce.client.product.ProductApiClient;
import com.park.ecommerce.client.product.dto.ProductSummaryResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CartServiceTest {
    private static final Long MEMBER_ID = 1L;
    private static final Long PRODUCT_ID = 10L;

    @Mock
    private CartItemRepository cartItemRepository;

    @Mock
    private ProductApiClient productApiClient;

    @InjectMocks
    private CartService cartService;

    @Test
    @DisplayName("처음 담는 상품이면 새 항목으로 저장한다")
    void addsNewItem() {
        givenProduct(onSale(10));
        given(cartItemRepository.findByMemberIdAndProductId(MEMBER_ID, PRODUCT_ID)).willReturn(Optional.empty());

        cartService.addItem(MEMBER_ID, PRODUCT_ID, 2);

        ArgumentCaptor<CartItem> captor = ArgumentCaptor.forClass(CartItem.class);
        verify(cartItemRepository).save(captor.capture());
        assertThat(captor.getValue().getProductId()).isEqualTo(PRODUCT_ID);
        assertThat(captor.getValue().getQuantity()).isEqualTo(2);
    }

    @Test
    @DisplayName("이미 담긴 상품이면 수량을 합산한다")
    void addsQuantityToExistingItem() {
        givenProduct(onSale(10));
        CartItem existing = cartItem(3);
        given(cartItemRepository.findByMemberIdAndProductId(MEMBER_ID, PRODUCT_ID)).willReturn(Optional.of(existing));

        cartService.addItem(MEMBER_ID, PRODUCT_ID, 2);

        assertThat(existing.getQuantity()).isEqualTo(5);
        verify(cartItemRepository, never()).save(any());
    }

    @Test
    @DisplayName("등록되지 않은 상품은 담을 수 없다")
    void rejectsUnknownProduct() {
        given(productApiClient.findProducts(anyCollection())).willReturn(List.of());

        assertThatThrownBy(() -> cartService.addItem(MEMBER_ID, PRODUCT_ID, 1))
                .isInstanceOf(OrderException.class)
                .extracting("errorCode")
                .isEqualTo(OrderErrorCode.PRODUCT_NOT_FOUND);
    }

    @Test
    @DisplayName("판매중지 상품은 담을 수 없다")
    void rejectsSuspendedProduct() {
        givenProduct(new ProductSummaryResponse(PRODUCT_ID, "우유", 3_900, null, "SUSPENDED", 10));

        assertThatThrownBy(() -> cartService.addItem(MEMBER_ID, PRODUCT_ID, 1))
                .isInstanceOf(OrderException.class)
                .extracting("errorCode")
                .isEqualTo(OrderErrorCode.PRODUCT_NOT_ON_SALE);
    }

    @Test
    @DisplayName("품절 상품은 담을 수 없다")
    void rejectsSoldOutProduct() {
        givenProduct(onSale(0));

        assertThatThrownBy(() -> cartService.addItem(MEMBER_ID, PRODUCT_ID, 1))
                .isInstanceOf(OrderException.class)
                .extracting("errorCode")
                .isEqualTo(OrderErrorCode.PRODUCT_SOLD_OUT);
    }

    @Test
    @DisplayName("이미 담긴 수량까지 더한 값이 재고를 넘으면 담을 수 없다")
    void rejectsQuantityOverStock() {
        givenProduct(onSale(4));
        given(cartItemRepository.findByMemberIdAndProductId(MEMBER_ID, PRODUCT_ID))
                .willReturn(Optional.of(cartItem(3)));

        assertThatThrownBy(() -> cartService.addItem(MEMBER_ID, PRODUCT_ID, 2))
                .isInstanceOf(OrderException.class)
                .extracting("errorCode")
                .isEqualTo(OrderErrorCode.INSUFFICIENT_STOCK);
    }

    @Test
    @DisplayName("합산 수량이 1회 구매 제한을 넘으면 담을 수 없다")
    void rejectsQuantityOverMaxQuantity() {
        givenProduct(onSale(200));
        given(cartItemRepository.findByMemberIdAndProductId(MEMBER_ID, PRODUCT_ID))
                .willReturn(Optional.of(cartItem(CartItem.MAX_QUANTITY)));

        assertThatThrownBy(() -> cartService.addItem(MEMBER_ID, PRODUCT_ID, 1))
                .isInstanceOf(OrderException.class)
                .extracting("errorCode")
                .isEqualTo(OrderErrorCode.MAX_QUANTITY_EXCEEDED);
    }

    @Test
    @DisplayName("장바구니를 조회하면 상품 정보를 붙여 금액과 함께 응답한다")
    void returnsCartWithProductInfo() {
        given(cartItemRepository.findAllByMemberIdOrderByCreatedAtDesc(MEMBER_ID)).willReturn(List.of(cartItem(2)));
        givenProduct(onSale(10));

        CartResponse response = cartService.getCart(MEMBER_ID);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).name()).isEqualTo("우유");
        assertThat(response.items().get(0).lineAmount()).isEqualTo(7_800);
        assertThat(response.items().get(0).orderable()).isTrue();
        assertThat(response.totalAmount()).isEqualTo(7_800);
    }

    @Test
    @DisplayName("담긴 뒤 품절된 상품은 주문 불가로 표시되고 총액에서 빠진다")
    void marksSoldOutItemNotOrderable() {
        given(cartItemRepository.findAllByMemberIdOrderByCreatedAtDesc(MEMBER_ID)).willReturn(List.of(cartItem(2)));
        givenProduct(onSale(0));

        CartResponse response = cartService.getCart(MEMBER_ID);

        assertThat(response.items().get(0).soldOut()).isTrue();
        assertThat(response.items().get(0).orderable()).isFalse();
        assertThat(response.totalAmount()).isZero();
    }

    @Test
    @DisplayName("담긴 수량이 남은 재고보다 많으면 주문 불가로 표시된다")
    void marksItemNotOrderableWhenStockIsNotEnough() {
        given(cartItemRepository.findAllByMemberIdOrderByCreatedAtDesc(MEMBER_ID)).willReturn(List.of(cartItem(5)));
        givenProduct(onSale(3));

        CartResponse response = cartService.getCart(MEMBER_ID);

        assertThat(response.items().get(0).soldOut()).isFalse();
        assertThat(response.items().get(0).orderable()).isFalse();
        assertThat(response.totalAmount()).isZero();
    }

    @Test
    @DisplayName("삭제된 상품은 장바구니 목록에서 빠진다")
    void excludesDeletedProduct() {
        given(cartItemRepository.findAllByMemberIdOrderByCreatedAtDesc(MEMBER_ID)).willReturn(List.of(cartItem(2)));
        given(productApiClient.findProducts(anyCollection())).willReturn(List.of());

        CartResponse response = cartService.getCart(MEMBER_ID);

        assertThat(response.items()).isEmpty();
    }

    @Test
    @DisplayName("장바구니가 비어 있으면 상품 서비스를 호출하지 않는다")
    void skipsProductCallWhenCartIsEmpty() {
        given(cartItemRepository.findAllByMemberIdOrderByCreatedAtDesc(MEMBER_ID)).willReturn(List.of());

        CartResponse response = cartService.getCart(MEMBER_ID);

        assertThat(response.items()).isEmpty();
        verify(productApiClient, never()).findProducts(anyCollection());
    }

    @Test
    @DisplayName("수량을 변경하면 합산이 아니라 지정한 값으로 설정된다")
    void changesQuantity() {
        givenProduct(onSale(10));
        CartItem existing = cartItem(3);
        given(cartItemRepository.findByMemberIdAndProductId(MEMBER_ID, PRODUCT_ID)).willReturn(Optional.of(existing));

        cartService.changeQuantity(MEMBER_ID, PRODUCT_ID, 5);

        assertThat(existing.getQuantity()).isEqualTo(5);
    }

    @Test
    @DisplayName("변경하려는 수량이 재고를 넘으면 변경할 수 없다")
    void rejectsChangeOverStock() {
        givenProduct(onSale(4));
        given(cartItemRepository.findByMemberIdAndProductId(MEMBER_ID, PRODUCT_ID))
                .willReturn(Optional.of(cartItem(3)));

        assertThatThrownBy(() -> cartService.changeQuantity(MEMBER_ID, PRODUCT_ID, 5))
                .isInstanceOf(OrderException.class)
                .extracting("errorCode")
                .isEqualTo(OrderErrorCode.INSUFFICIENT_STOCK);
    }

    @Test
    @DisplayName("담겨 있지 않은 상품의 수량은 변경할 수 없다")
    void rejectsChangeOnMissingItem() {
        given(cartItemRepository.findByMemberIdAndProductId(MEMBER_ID, PRODUCT_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> cartService.changeQuantity(MEMBER_ID, PRODUCT_ID, 2))
                .isInstanceOf(OrderException.class)
                .extracting("errorCode")
                .isEqualTo(OrderErrorCode.CART_ITEM_NOT_FOUND);
    }

    @Test
    @DisplayName("담긴 항목을 삭제한다")
    void removesItem() {
        CartItem existing = cartItem(2);
        given(cartItemRepository.findByMemberIdAndProductId(MEMBER_ID, PRODUCT_ID)).willReturn(Optional.of(existing));

        cartService.removeItem(MEMBER_ID, PRODUCT_ID);

        verify(cartItemRepository).delete(existing);
    }

    @Test
    @DisplayName("담겨 있지 않은 상품은 삭제할 수 없다")
    void rejectsRemoveOnMissingItem() {
        given(cartItemRepository.findByMemberIdAndProductId(MEMBER_ID, PRODUCT_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> cartService.removeItem(MEMBER_ID, PRODUCT_ID))
                .isInstanceOf(OrderException.class)
                .extracting("errorCode")
                .isEqualTo(OrderErrorCode.CART_ITEM_NOT_FOUND);
    }

    private void givenProduct(ProductSummaryResponse product) {
        given(productApiClient.findProducts(anyCollection())).willReturn(List.of(product));
    }

    private static ProductSummaryResponse onSale(int availableQuantity) {
        return new ProductSummaryResponse(PRODUCT_ID, "우유", 3_900, null, "ON_SALE", availableQuantity);
    }

    private static CartItem cartItem(int quantity) {
        return CartItem.builder()
                .memberId(MEMBER_ID)
                .productId(PRODUCT_ID)
                .quantity(quantity)
                .build();
    }
}
