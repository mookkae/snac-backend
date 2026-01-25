package com.ureca.snac.infra.config;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.lang.NonNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * ClientHttpResponse를 버퍼링하여 응답 본문을 여러 번 읽을 수 있게 하는 래퍼
 * 로깅 인터셉터와 에러 핸들러 모두에서 응답 본문에 접근 가능
 */
public class BufferingClientHttpResponseWrapper implements ClientHttpResponse {

    private final ClientHttpResponse response;
    private final byte[] body;

    public BufferingClientHttpResponseWrapper(ClientHttpResponse response) throws IOException {
        this.response = response;
        // 응답 본문을 미리 읽어서 캐싱
        this.body = response.getBody().readAllBytes();
    }

    @Override
    @NonNull
    public InputStream getBody() {
        // 캐싱된 본문을 새 스트림으로 반환 (여러 번 읽기 가능)
        return new ByteArrayInputStream(this.body);
    }

    @Override
    @NonNull
    public HttpStatusCode getStatusCode() throws IOException {
        return response.getStatusCode();
    }

    @Override
    @NonNull
    public String getStatusText() throws IOException {
        return response.getStatusText();
    }

    @Override
    @NonNull
    public HttpHeaders getHeaders() {
        return response.getHeaders();
    }

    @Override
    public void close() {
        response.close();
    }

    /**
     * 캐싱된 응답 본문을 문자열로 반환 (로깅용)
     */
    public String getBodyAsString() {
        return new String(this.body);
    }
}
