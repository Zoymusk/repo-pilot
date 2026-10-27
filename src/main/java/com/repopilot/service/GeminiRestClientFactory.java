package com.repopilot.service;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

final class GeminiRestClientFactory {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    private GeminiRestClientFactory() {
    }

    static RestClient build(RestClient.Builder builder) {
        return build(builder, READ_TIMEOUT);
    }

    static RestClient build(RestClient.Builder builder, Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(readTimeout);
        return builder.requestFactory(requestFactory).build();
    }
}
