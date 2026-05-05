package com.callwise.voiceagent.ai.provider;

import com.callwise.voiceagent.ai.dto.ChatRequest;
import com.callwise.voiceagent.ai.dto.ChatResponse;
import com.callwise.voiceagent.ai.dto.ImageContent;
import com.callwise.voiceagent.ai.dto.Message;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
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
 * Confirms the Groq provider emits OpenAI-style multimodal content (text + image_url with
 * a {@code data:} URI) when the request carries an image, and switches to the configured
 * vision-capable model id.
 */
class GroqProviderVisionTest {

    private WireMockServer server;
    private GroqProvider provider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();

        // HTTP/1.1 factory — WireMock cancels JDK HttpClient HTTP/2 streams with RST_STREAM.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));
        RestClient client = RestClient.builder()
                .baseUrl(server.baseUrl())
                .requestFactory(factory)
                .defaultHeader("authorization", "Bearer test")
                .defaultHeader("content-type", "application/json")
                .build();
        provider = new GroqProvider(client, objectMapper, "llama-3.3-70b-versatile",
                "meta-llama/llama-4-scout-17b-16e-instruct");
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void chat_withImage_sendsImageUrlPartAndUsesVisionModel() throws Exception {
        server.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withHeader("content-type", "application/json")
                        .withBody("""
                                {
                                  "choices":[{"message":{"content":"Appliance: dryer"},"finish_reason":"stop"}],
                                  "usage":{"prompt_tokens":10,"completion_tokens":4}
                                }""")));

        ChatRequest req = new ChatRequest(
                List.of(Message.user("Look at this dryer")),
                "vision system prompt",
                List.of(),
                0.0,
                256,
                List.of(new ImageContent("image/png", "BBBB")));

        ChatResponse r = provider.chat(req);
        assertThat(r.text()).contains("dryer");

        var requests = server.findAll(postRequestedFor(urlEqualTo("/chat/completions")));
        JsonNode body = objectMapper.readTree(requests.get(0).getBodyAsString());

        // Vision-capable model should be used when images are present.
        assertThat(body.get("model").asText()).isEqualTo("meta-llama/llama-4-scout-17b-16e-instruct");

        JsonNode messages = body.get("messages");
        JsonNode lastUser = messages.get(messages.size() - 1);
        assertThat(lastUser.get("role").asText()).isEqualTo("user");
        JsonNode parts = lastUser.get("content");
        assertThat(parts.isArray()).isTrue();
        boolean foundImage = false;
        for (JsonNode part : parts) {
            if ("image_url".equals(part.path("type").asText())) {
                String url = part.path("image_url").path("url").asText();
                assertThat(url).startsWith("data:image/png;base64,").endsWith("BBBB");
                foundImage = true;
            }
        }
        assertThat(foundImage).as("image_url part present").isTrue();
    }

    @Test
    void chat_withoutImages_keepsTextModelAndPlainContent() throws Exception {
        server.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withHeader("content-type", "application/json")
                        .withBody("""
                                {"choices":[{"message":{"content":"hi"},"finish_reason":"stop"}],
                                 "usage":{"prompt_tokens":1,"completion_tokens":1}}""")));

        ChatRequest req = new ChatRequest(
                List.of(Message.user("hello")),
                "sys", List.of(), 0.0, 32);

        provider.chat(req);

        var requests = server.findAll(postRequestedFor(urlEqualTo("/chat/completions")));
        JsonNode body = objectMapper.readTree(requests.get(0).getBodyAsString());
        assertThat(body.get("model").asText()).isEqualTo("llama-3.3-70b-versatile");
        assertThat(body.get("messages").get(1).get("content").isTextual()).isTrue();
    }
}
