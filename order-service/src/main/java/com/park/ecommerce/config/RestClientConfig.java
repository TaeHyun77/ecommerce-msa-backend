package com.park.ecommerce.config;

import com.park.ecommerce.exception.OrderErrorCode;
import com.park.ecommerce.exception.OrderException;
import com.park.ecommerce.exception.PaymentResultUnknownException;
import com.park.ecommerce.exception.PaymentServiceUnavailableException;
import com.park.ecommerce.exception.ProductServiceUnavailableException;
import com.park.ecommerce.exception.PromotionServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Slf4j
@Configuration
public class RestClientConfig {
    @Bean
    public RestClient paymentServiceRestClient(
            RestClient.Builder builder,
            @Value("${external.payment-service.base-url}") String baseUrl
    ) {
        return builder
                .baseUrl(baseUrl)
                // payment-service의 토스 승인 타임아웃(최대 60초)과 내부 처리 시간을 고려해 70초로 설정
                .requestFactory(requestFactory(Duration.ofSeconds(1), Duration.ofSeconds(70)))
                .defaultStatusHandler(
                        HttpStatusCode::is5xxServerError,
                        (request, response) -> {
                            throw new PaymentServiceUnavailableException("payment-service 응답 status=" + response.getStatusCode().value());
                        }
                )
                .defaultStatusHandler(
                        HttpStatusCode::is4xxClientError,
                        (request, response) -> {
                            log.error("payment-service 호출 계약 불일치 - status: {}, uri: {}", response.getStatusCode(), request.getURI());
                            throw new PaymentResultUnknownException("payment-service 호출 계약 불일치 status=" + response.getStatusCode().value());
                        }
                )
                .build();
    }

    @Bean
    public RestClient productServiceRestClient(
            RestClient.Builder builder,
            @Value("${external.product-service.base-url}") String baseUrl
    ) {
        return builder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory(Duration.ofSeconds(1), Duration.ofSeconds(2)))
                .defaultStatusHandler(
                        HttpStatusCode::is5xxServerError,
                        (request, response) -> {
                            throw new ProductServiceUnavailableException();
                        }
                )
                .defaultStatusHandler(
                        HttpStatusCode::is4xxClientError,
                        (request, response) -> {
                            log.error("product-service 호출 계약 불일치 - status: {}, uri: {}", response.getStatusCode(), request.getURI());
                            throw new OrderException(OrderErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
                        }
                )
                .build();
    }

    @Bean
    public RestClient promotionServiceRestClient(
            RestClient.Builder builder,
            @Value("${external.promotion-service.base-url}") String baseUrl
    ) {
        return builder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory(Duration.ofSeconds(1), Duration.ofSeconds(2)))
                .defaultStatusHandler(
                        HttpStatusCode::is5xxServerError,
                        (request, response) -> {
                            throw new PromotionServiceUnavailableException();
                        }
                )
                .defaultStatusHandler(
                        HttpStatusCode::is4xxClientError,
                        (request, response) -> {
                            log.error("promotion-service 호출 계약 불일치 - status: {}, uri: {}", response.getStatusCode(), request.getURI());
                            throw new OrderException(OrderErrorCode.PROMOTION_SERVICE_UNAVAILABLE);
                        }
                )
                .build();
    }

    private ClientHttpRequestFactory requestFactory(Duration connectTimeout, Duration readTimeout) {
        HttpClientSettings settings = HttpClientSettings.defaults()
                .withConnectTimeout(connectTimeout)
                .withReadTimeout(readTimeout);
        return ClientHttpRequestFactoryBuilder.detect().build(settings);
    }
}
