package com.ureca.snac.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * RestClient 공통 인프라 설정
 * 타임아웃 등 공통 설정 제공
 */
@Configuration
public class RestClientConfig {

    // 모든 RestClient에서 타임아웃 설정
    @Bean
    public ClientHttpRequestFactory clientHttpRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);  // 연결 타임아웃 3초
        factory.setReadTimeout(10000);    // 읽기 타임아웃 10초
        return factory;
    }

    /**
     * RestClient.Builder
     * 주입받을 때마다 새로운 인스턴스 생성하여 상태 격리하기위해 Prototype
     *
     * @param requestFactory HTTP 클라이언트 팩토리
     * @return 공통 설정이 적용된 RestClient.Builder
     */
    @Bean
    @Scope("prototype")  // 외부 API 로 인한 인스턴스 공유 방지 상태 객체
    public RestClient.Builder restClientBuilder(ClientHttpRequestFactory requestFactory) {
        return RestClient.builder()
                .requestFactory(requestFactory);
    }
}
/*
 * RestTemplate가 구식이라고 하는 이유로 WebClient 추천
 * 그런데 굳이 비동기를 안쓸꺼라면 쓸 이유를 모르겠다
 * RestClient 동기에다가 더 최신 HTTP 클라이언트
 * 의존성도 필요없음
 */