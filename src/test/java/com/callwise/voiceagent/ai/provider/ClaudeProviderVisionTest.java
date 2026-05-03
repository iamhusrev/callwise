package com.callwise.voiceagent.ai.provider;

import com.callwise.voiceagent.ai.dto.ChatRequest;
import com.callwise.voiceagent.ai.dto.ChatResponse;
import com.callwise.voiceagent.ai.dto.ImageContent;
import com.callwise.voiceagent.ai.dto.Message;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * WireMock-driven test confirming the Anthropic provider emits a base64 image content block
 * when {@link ChatRequest#images()} is non-empty.
 */
class ClaudeProviderVisionTest {

    private WireMockServer server;
    private ClaudeProvider provider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();

        // Match production: SimpleClientHttpRequestFactory uses HTTP/1.1, which WireMock supports
        // out of the box. Default RestClient builder uses JDK HttpClient with HTTP/2 negotiation
        // that WireMock cancels with RST_STREAM.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));
        RestClient client = RestClient.builder()
                .baseUrl(server.baseUrl())
                .requestFactory(factory)
                .defaultHeader("x-api-key", "test")
                .defaultHeader("anthropic-version", "2023-06-01")
                .defaultHeader("content-type", "application/json")
                .build();
        provider = new ClaudeProvider(client, objectMapper, "claude-haiku-4-5", "claude-haiku-4-5");
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void chat_withImage_sendsBase64ImageBlockOnLastUserMessage() throws Exception {
        server.stubFor(post(urlEqualTo("/v1/messages"))
                .willReturn(aResponse()
                        .withHeader("content-type", "application/json")
                        .withBody("""
                                {
                                  "content": [{"type":"text","text":"Appliance: washer"}],
                                  "stop_reason": "end_turn",
                                  "usage": {"input_tokens": 10, "output_tokens": 5}
                                }""")));

        ChatRequest req = new ChatRequest(
                List.of(Message.user("Look at this")),
                "vision system prompt",
                List.of(),
                0.0,
                256,
                List.of(new ImageContent("image/jpeg", "AAAA")));

        ChatResponse r = provider.chat(req);
        assertThat(r.text()).contains("washer");

        var requests = server.findAll(postRequestedFor(urlEqualTo("/v1/messages")));
        assertThat(requests).hasSize(1);
        JsonNode body = objectMapper.readTree(requests.get(0).getBodyAsString());
        JsonNode lastUser = body.get("messages").get(body.get("messages").size() - 1);
        assertThat(lastUser.get("role").asText()).isEqualTo("user");
        JsonNode content = lastUser.get("content");
        assertThat(content.isArray()).isTrue();
        boolean foundImage = false;
        for (JsonNode block : content) {
            if ("image".equals(block.path("type").asText())) {
                assertThat(block.path("source").path("type").asText()).isEqualTo("base64");
                assertThat(block.path("source").path("media_type").asText()).isEqualTo("image/jpeg");
                assertThat(block.path("source").path("data").asText()).isEqualTo("AAAA");
                foundImage = true;
            }
        }
        assertThat(foundImage).as("image content block present").isTrue();
    }

    @Test
    void chat_withoutImages_sendsPlainTextContentString() throws Exception {
        server.stubFor(post(urlEqualTo("/v1/messages"))
                .willReturn(aResponse()
                        .withHeader("content-type", "application/json")
                        .withBody("""
                                {"content":[{"type":"text","text":"hi"}],"stop_reason":"end_turn",
                                 "usage":{"input_tokens":1,"output_tokens":1}}""")));

        ChatRequest req = new ChatRequest(
                List.of(Message.user("hello")),
                "sys", List.of(), 0.0, 64);

        provider.chat(req);

        var requests = server.findAll(postRequestedFor(urlEqualTo("/v1/messages")));
        JsonNode body = objectMapper.readTree(requests.get(0).getBodyAsString());
        JsonNode lastUser = body.get("messages").get(0);
        assertThat(lastUser.get("content").isTextual()).isTrue();
    }
}
