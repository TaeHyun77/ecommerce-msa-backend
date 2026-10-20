package com.park.ecommerce.search;

import com.park.ecommerce.client.product.ProductApiClient;
import com.park.ecommerce.exception.SearchErrorCode;
import com.park.ecommerce.exception.SearchException;
import com.park.ecommerce.keyword.SearchKeywordService;
import com.park.ecommerce.keyword.SearchSource;
import com.park.ecommerce.search.dto.ProductSearchPageResponse;
import com.park.ecommerce.search.dto.ProductSearchResponse;
import com.park.ecommerce.search.dto.ProductSummaryResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ProductSearchServiceTest {
    private static final ProductSearchHits NO_HITS = new ProductSearchHits(List.of(), 0);
    private static final String SEARCHER = "v:visitor-1";

    @Mock
    private ProductSearchRepository productSearchRepository;

    @Mock
    private ProductApiClient productApiClient;

    @Mock
    private SearchKeywordService searchKeywordService;

    @InjectMocks
    private ProductSearchService productSearchService;

    @Test
    @DisplayName("검색어의 앞뒤 공백과 연속 공백을 정리해 검색한다")
    void normalizesQuery() {
        given(productSearchRepository.search("고등어 구이", 0, 20)).willReturn(NO_HITS);

        productSearchService.search("  고등어   구이 ", 0, 20, SearchSource.TYPED, SEARCHER);

        verify(productSearchRepository).search("고등어 구이", 0, 20);
    }

    @Test
    @DisplayName("공백뿐인 검색어는 거부하고 검색하지 않는다")
    void rejectsBlankQuery() {
        assertThatThrownBy(() -> productSearchService.search("   ", 0, 20, SearchSource.TYPED, SEARCHER))
                .isInstanceOf(SearchException.class)
                .extracting("errorCode")
                .isEqualTo(SearchErrorCode.INVALID_SEARCH_QUERY);

        verify(productSearchRepository, never()).search(anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("검색어는 50자까지 허용하고 넘으면 거부한다")
    void limitsQueryLength() {
        given(productSearchRepository.search(anyString(), anyInt(), anyInt())).willReturn(NO_HITS);

        assertThatCode(() -> productSearchService.search("가".repeat(50), 0, 20, SearchSource.TYPED, SEARCHER)).doesNotThrowAnyException();
        assertThatThrownBy(() -> productSearchService.search("가".repeat(51), 0, 20, SearchSource.TYPED, SEARCHER))
                .isInstanceOf(SearchException.class)
                .extracting("errorCode")
                .isEqualTo(SearchErrorCode.INVALID_SEARCH_QUERY);
    }

    @Test
    @DisplayName("1만 번째 결과까지 조회할 수 있고 그 뒤를 요구하는 페이지는 거부한다")
    void limitsSearchDepth() {
        given(productSearchRepository.search(anyString(), anyInt(), anyInt())).willReturn(NO_HITS);

        assertThatCode(() -> productSearchService.search("고등어", 499, 20, SearchSource.TYPED, SEARCHER)).doesNotThrowAnyException();
        assertThatThrownBy(() -> productSearchService.search("고등어", 500, 20, SearchSource.TYPED, SEARCHER))
                .isInstanceOf(SearchException.class)
                .extracting("errorCode")
                .isEqualTo(SearchErrorCode.PAGE_OUT_OF_RANGE);
    }

    @Test
    @DisplayName("ES가 찾은 순서를 유지하고, 판매중이 아니거나 product-service에 없는 상품은 뺀다")
    void mergesInSearchOrder() {
        given(productSearchRepository.search("고등어", 0, 20)).willReturn(new ProductSearchHits(List.of(3L, 1L, 2L, 4L), 4));
        given(productApiClient.findProducts(List.of(3L, 1L, 2L, 4L))).willReturn(List.of(
                summary(1L, "ON_SALE", 5),
                summary(2L, "SUSPENDED", 5),
                summary(3L, "ON_SALE", 0)
        ));

        ProductSearchPageResponse response = productSearchService.search("고등어", 0, 20, SearchSource.TYPED, SEARCHER);

        assertThat(response.content())
                .extracting(ProductSearchResponse::productId, ProductSearchResponse::soldOut)
                .containsExactly(tuple(3L, true), tuple(1L, false));
        assertThat(response.content().get(0)).isEqualTo(new ProductSearchResponse(
                3L, "상품 3", "컬리", 14_900, "FROZEN", "https://cdn.example.com/3.jpg", 2L, true
        ));
    }

    @Test
    @DisplayName("전체 건수는 ES 기준이고, 이를 바탕으로 페이지 정보를 계산한다")
    void calculatesPageInfo() {
        given(productSearchRepository.search("고등어", 1, 20)).willReturn(new ProductSearchHits(List.of(1L), 45));
        given(productApiClient.findProducts(List.of(1L))).willReturn(List.of(summary(1L, "ON_SALE", 5)));

        ProductSearchPageResponse response = productSearchService.search("고등어", 1, 20, SearchSource.TYPED, SEARCHER);

        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.totalElements()).isEqualTo(45);
        assertThat(response.totalPages()).isEqualTo(3);
        assertThat(response.hasNext()).isTrue();
    }

    @Test
    @DisplayName("검색 결과가 없으면 product-service를 호출하지 않는다")
    void skipsProductServiceWhenNoHits() {
        given(productSearchRepository.search("없는상품", 0, 20)).willReturn(NO_HITS);

        ProductSearchPageResponse response = productSearchService.search("없는상품", 0, 20, SearchSource.TYPED, SEARCHER);

        assertThat(response.content()).isEmpty();
        assertThat(response.totalElements()).isZero();
        assertThat(response.hasNext()).isFalse();
        verify(productApiClient, never()).findProducts(any());
    }

    @Test
    @DisplayName("첫 페이지에 결과가 있으면 정리한 검색어를 출처·검색한 사람과 함께 기록한다")
    void recordsKeywordOnFirstPageWithHits() {
        given(productSearchRepository.search("고등어 구이", 0, 20)).willReturn(new ProductSearchHits(List.of(1L), 1));
        given(productApiClient.findProducts(List.of(1L))).willReturn(List.of(summary(1L, "ON_SALE", 5)));

        productSearchService.search(" 고등어  구이 ", 0, 20, SearchSource.SUGGESTION, SEARCHER);

        verify(searchKeywordService).record("고등어 구이", SearchSource.SUGGESTION, SEARCHER);
    }

    @Test
    @DisplayName("같은 검색의 다음 페이지와 결과가 없는 검색은 기록하지 않는다")
    void skipsRecordOnNextPageOrNoHits() {
        given(productSearchRepository.search("고등어", 1, 20)).willReturn(new ProductSearchHits(List.of(1L), 45));
        given(productApiClient.findProducts(List.of(1L))).willReturn(List.of(summary(1L, "ON_SALE", 5)));
        given(productSearchRepository.search("없는상품", 0, 20)).willReturn(NO_HITS);

        productSearchService.search("고등어", 1, 20, SearchSource.TYPED, SEARCHER);
        productSearchService.search("없는상품", 0, 20, SearchSource.TYPED, SEARCHER);

        verify(searchKeywordService, never()).record(anyString(), any(), any());
    }

    private static ProductSummaryResponse summary(Long productId, String status, int availableQuantity) {
        return new ProductSummaryResponse(
                productId, "상품 " + productId, "컬리", 14_900, "FROZEN",
                "https://cdn.example.com/" + productId + ".jpg", 2L, status, availableQuantity
        );
    }
}
