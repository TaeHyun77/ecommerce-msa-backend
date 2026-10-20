package com.park.ecommerce.cart;

import com.park.ecommerce.cart.dto.CartItemResponse;
import com.park.ecommerce.cart.dto.CartResponse;
import com.park.ecommerce.exception.OrderErrorCode;
import com.park.ecommerce.exception.OrderException;
import com.park.ecommerce.client.product.ProductApiClient;
import com.park.ecommerce.client.product.dto.ProductSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CartService {
    private final CartItemRepository cartItemRepository;
    private final ProductApiClient productApiClient;

    // 이미 담긴 상품이면 수량을 합산합니다.
    @Transactional
    public CartItemResponse addItem(Long memberId, Long productId, int quantity) {
        ProductSummaryResponse product = findOrderableProduct(productId);

        CartItem cartItem = cartItemRepository.findByMemberIdAndProductId(memberId, productId)
                .orElse(null);

        int totalQuantity = cartItem == null ? quantity : cartItem.getQuantity() + quantity;
        validateQuantity(product, totalQuantity);

        if (cartItem == null) {
            cartItem = CartItem.builder()
                    .memberId(memberId)
                    .productId(productId)
                    .quantity(quantity)
                    .build();
            cartItemRepository.save(cartItem);
        } else {
            cartItem.addQuantity(quantity);
        }

        return CartItemResponse.of(cartItem, product);
    }

    public CartResponse getCart(Long memberId) {
        List<CartItem> cartItems = cartItemRepository.findAllByMemberIdOrderByCreatedAtDesc(memberId);
        if (cartItems.isEmpty()) {
            return CartResponse.from(List.of());
        }

        Map<Long, ProductSummaryResponse> products = findProducts(cartItems);

        List<CartItemResponse> items = cartItems.stream()
                // 삭제된 상품은 목록에서 빼기만 하고 장바구니 행은 지우지 않는다 - 조회가 데이터를 바꾸지 않도록
                .filter(cartItem -> products.containsKey(cartItem.getProductId()))
                .map(cartItem -> CartItemResponse.of(cartItem, products.get(cartItem.getProductId())))
                .toList();

        return CartResponse.from(items);
    }

    // 담기와 달리 합산하지 않고 지정한 수량으로 설정한다
    @Transactional
    public CartItemResponse changeQuantity(Long memberId, Long productId, int quantity) {
        CartItem cartItem = findCartItem(memberId, productId);
        ProductSummaryResponse product = findOrderableProduct(productId);

        validateQuantity(product, quantity);
        cartItem.changeQuantity(quantity);

        return CartItemResponse.of(cartItem, product);
    }

    @Transactional
    public void removeItem(Long memberId, Long productId) {
        cartItemRepository.delete(findCartItem(memberId, productId));
    }

    private CartItem findCartItem(Long memberId, Long productId) {
        return cartItemRepository.findByMemberIdAndProductId(memberId, productId)
                .orElseThrow(() -> new OrderException(OrderErrorCode.CART_ITEM_NOT_FOUND));
    }

    private ProductSummaryResponse findOrderableProduct(Long productId) {
        ProductSummaryResponse product = productApiClient.findProducts(List.of(productId)).stream()
                .findFirst()
                // 등록되지 않은 상품 식별자는 응답에서 빠진다
                .orElseThrow(() -> new OrderException(OrderErrorCode.PRODUCT_NOT_FOUND));

        if (!product.isOnSale()) {
            throw new OrderException(OrderErrorCode.PRODUCT_NOT_ON_SALE);
        }
        if (product.isSoldOut()) {
            throw new OrderException(OrderErrorCode.PRODUCT_SOLD_OUT);
        }
        return product;
    }

    private Map<Long, ProductSummaryResponse> findProducts(List<CartItem> cartItems) {
        List<Long> productIds = cartItems.stream()
                .map(CartItem::getProductId)
                .toList();

        return productApiClient.findProducts(productIds).stream()
                .collect(Collectors.toMap(ProductSummaryResponse::productId, Function.identity()));
    }

    private static void validateQuantity(ProductSummaryResponse product, int quantity) {
        if (quantity > CartItem.MAX_QUANTITY) {
            throw new OrderException(OrderErrorCode.MAX_QUANTITY_EXCEEDED);
        }
        if (!product.canCover(quantity)) {
            throw new OrderException(OrderErrorCode.INSUFFICIENT_STOCK);
        }
    }
}
