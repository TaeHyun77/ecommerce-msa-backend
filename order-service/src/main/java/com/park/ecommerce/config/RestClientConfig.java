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
    public RestClient productServiceRestClient(
            RestClient.Builder builder,
            @Value("${external.product-service.base-url}") String baseUrl
    ) {
        return builder
                .baseUrl(baseUrl)
                // 내부 서비스 간 호출이라 정상 응답이 수십 ms 수준 - 짧게 잡아 서킷이 열리기 전 스레드 점유를 줄임
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
                            // 4xx는 product-service가 정상이라는 뜻이고 원인은 이쪽의 호출 계약 오류
                            // 서킷이 열리지 않아 조용히 계속 실패하므로 반드시 로그로 남기도록 함
                            log.error("product-service 호출 계약 불일치 - status: {}, uri: {}",
                                    response.getStatusCode(), request.getURI());
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
                // product-service와 같은 내부 호출이라 같은 기준으로 짧게 잡음
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
                            // 서킷이 열리지 않아 조용히 계속 실패하므로 반드시 로그로 남기도록 함
                            log.error("promotion-service 호출 계약 불일치 - status: {}, uri: {}",
                                    response.getStatusCode(), request.getURI());
                            throw new OrderException(OrderErrorCode.PROMOTION_SERVICE_UNAVAILABLE);
                        }
                )
                .build();
    }

    @Bean
    public RestClient paymentServiceRestClient(
            RestClient.Builder builder,
            @Value("${external.payment-service.base-url}") String baseUrl
    ) {
        return builder
                .baseUrl(baseUrl)
                // payment-service가 토스 승인 응답을 최대 60초 기다리므로 그보다 길게 잡아야함
                // 짧으면 payment-service는 결과를 확정했는데 이쪽만 결과 불명이 되어 복구를 기다려야 함
                .requestFactory(requestFactory(Duration.ofSeconds(1), Duration.ofSeconds(70)))
                // 결과 불명(503)을 포함한 5xx는 승인이 처리됐는지 알 수 없으므로 결과 불명으로 다룸
                .defaultStatusHandler(
                        HttpStatusCode::is5xxServerError,
                        (request, response) -> {
                            throw new PaymentServiceUnavailableException("payment-service 응답 status=" + response.getStatusCode().value());
                        }
                )
                .defaultStatusHandler(
                        HttpStatusCode::is4xxClientError,
                        (request, response) -> {
                            // 저장해 둔 결제 키/금액을 그대로 보내므로 4xx는 호출 계약 오류 - 결과를 모르니 결과 불명으로 남기고 로그로 알린다
                            log.error("payment-service 호출 계약 불일치 - status: {}, uri: {}",
                                    response.getStatusCode(), request.getURI());
                            throw new PaymentResultUnknownException("payment-service 호출 계약 불일치 status=" + response.getStatusCode().value());
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
