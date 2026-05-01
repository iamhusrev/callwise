package com.callwise.voiceagent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * RestClient bean for Groq's OpenAI-compatible Chat Completions API.
 *
 * <p>Different timeouts and base URL from Claude — Groq is sub-second on Llama 3.3 70B, so
 * we keep the read timeout aggressive (the fallback path should not eat the latency budget the
 * primary already burned through).
 */
@Configuration
public class GroqConfig {

    @Bean(name = "groqRestClient")
    public RestClient groqRestClient(
            @Value("${groq.api-key:}") String apiKey,
            @Value("${groq.base-url:https://api.groq.com/openai/v1}") String baseUrl,
            @Value("${groq.timeout-ms:8000}") int timeoutMs
    ) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(2000));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("content-type", "application/json")
                .build();
    }
}
