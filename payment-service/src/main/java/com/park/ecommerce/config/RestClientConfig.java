package com.park.ecommerce.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

@Configuration
public class RestClientConfig {
    @Bean
    public RestClient tossPaymentsRestClient(
            RestClient.Builder builder,
            @Value("${external.toss-payments.base-url}") String baseUrl,
            @Value("${toss.payments.secret-key}") String secretKey
    ) {
        // 토스페이먼츠 Basic 인증 - 시크릿 키 뒤에 ':'을 붙여 base64로 인코딩 (콜론이 빠지면 인증 실패)
        String credentials = Base64.getEncoder().encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));

        return builder
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + credentials)
                .requestFactory(requestFactory())
                .build();
    }

    private ClientHttpRequestFactory requestFactory() {
        // 토스페이먼츠 권장값 - 결제 처리 API는 카드사 통신이 포함돼 오래 걸릴 수 있어 Read Timeout을 60초로 둠
        HttpClientSettings settings = HttpClientSettings.defaults()
                .withConnectTimeout(Duration.ofSeconds(1))
                .withReadTimeout(Duration.ofSeconds(60));

        return ClientHttpRequestFactoryBuilder.detect().build(settings);
    }
}
