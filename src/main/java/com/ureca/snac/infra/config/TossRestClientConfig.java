package com.ureca.snac.infra.config;

import com.ureca.snac.infra.TossPaymentsErrorHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Toss Payments API 전용 RestClient 설정
 * 인증 인터셉터 및 에러 핸들러 포함
 */
@Configuration
@EnableConfigurationProperties(TossPaymentProperties.class)
@RequiredArgsConstructor
public class TossRestClientConfig {

    private final TossPaymentProperties tossPaymentProperties;
    private final RestClient.Builder restClientBuilder;  // 공통 빌더 주입

    @Bean
    public TossPaymentsErrorHandler tossPaymentsErrorHandler() {
        return new TossPaymentsErrorHandler();
    }

    @Bean
    public TossLoggingInterceptor tossLoggingInterceptor() {
        return new TossLoggingInterceptor();
    }

    @Bean
    public RestClient tossRestClient(TossPaymentsErrorHandler errorHandler,
                                      TossLoggingInterceptor loggingInterceptor) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(30));

        return restClientBuilder  // 공통 설정 상속
                .requestFactory(requestFactory)
                .baseUrl(tossPaymentProperties.getUrl())
                .requestInterceptor(loggingInterceptor)  // 로깅 먼저 (요청 시작 시간 측정)
                .requestInterceptor(new TossAuthInterceptor(tossPaymentProperties.getSecretKey()))
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .defaultStatusHandler(errorHandler)
                .build();
    }
}
