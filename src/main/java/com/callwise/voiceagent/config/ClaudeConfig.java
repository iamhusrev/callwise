package com.callwise.voiceagent.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * RestClient bean for the Anthropic Messages API.
 *
 * <p>Each provider gets its own bean (qualified by name) so timeouts, base URLs, and default
 * headers can vary per provider without leaking into a shared client.
 */
@Configuration
public class ClaudeConfig {

    @Bean(name = "claudeRestClient")
    public RestClient claudeRestClient(
            @Value("${anthropic.api-key:}") String apiKey,
            @Value("${anthropic.base-url:https://api.anthropic.com}") String baseUrl,
            @Value("${anthropic.version:2023-06-01}") String anthropicVersion,
            @Value("${anthropic.timeout-ms:8000}") int timeoutMs
    ) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(2000));     // fail fast on DNS / TCP problems
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));   // tail latency budget for AI

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", anthropicVersion)
                .defaultHeader("content-type", "application/json")
                .build();
    }
}
