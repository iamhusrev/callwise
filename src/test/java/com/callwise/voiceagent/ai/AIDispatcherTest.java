package com.callwise.voiceagent.ai;

import com.callwise.voiceagent.ai.circuit.CircuitBreaker;
import com.callwise.voiceagent.ai.dto.ChatRequest;
import com.callwise.voiceagent.ai.dto.ChatResponse;
import com.callwise.voiceagent.ai.dto.Message;
import com.callwise.voiceagent.ai.provider.ClaudeProvider;
import com.callwise.voiceagent.ai.provider.GroqProvider;
import com.callwise.voiceagent.exception.AllProvidersFailedException;
import com.callwise.voiceagent.exception.ProviderException;
import com.callwise.voiceagent.service.ObservabilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the primary→fallback orchestration. The real wire format is exercised by the
 * provider tests; here we want to know that the dispatcher's decision tree is correct.
 */
class AIDispatcherTest {

    private ClaudeProvider primary;
    private GroqProvider fallback;
    private CircuitBreaker breaker;
    private ObservabilityService observability;
    private AIDispatcher dispatcher;
    private ChatRequest request;

    @BeforeEach
    void setUp() {
        primary = mock(ClaudeProvider.class);
        fallback = mock(GroqProvider.class);
        breaker = mock(CircuitBreaker.class);
        observability = mock(ObservabilityService.class);
        dispatcher = new AIDispatcher(primary, fallback, breaker, observability);
        when(primary.getName()).thenReturn("claude");
        when(fallback.getName()).thenReturn("groq");
        request = new ChatRequest(List.of(Message.user("hi")), "sys", List.of(), 0.2, 256);
    }

    @Test
    void diagnose_primarySucceeds_returnsPrimaryResponse() {
        ChatResponse expected = new ChatResponse("ok", List.of(), "end_turn", "claude", 100, 10, 5, 0);
        when(breaker.allowRequest("claude")).thenReturn(true);
        when(primary.chat(request)).thenReturn(expected);

        ChatResponse result = dispatcher.diagnose(request);

        assertThat(result).isSameAs(expected);
        verify(primary).chat(request);
        verify(fallback, never()).chat(any());
        verify(breaker).recordSuccess("claude");
        verify(observability).recordProviderSuccess(eq("claude"), eq(100L), eq(10), eq(5));
    }

    @Test
    void diagnose_primaryThrows_fallsBackToSecondaryAndReturnsItsResponse() {
        ChatResponse fallbackResp = new ChatResponse("from groq", List.of(), "end_turn", "groq", 80, 7, 3, 0);
        when(breaker.allowRequest("claude")).thenReturn(true);
        when(breaker.allowRequest("groq")).thenReturn(true);
        when(primary.chat(request)).thenThrow(new ProviderException("claude", "down", 503));
        when(fallback.chat(request)).thenReturn(fallbackResp);

        ChatResponse result = dispatcher.diagnose(request);

        assertThat(result).isSameAs(fallbackResp);
        verify(breaker).recordFailure("claude");
        verify(observability).recordProviderFailure(eq("claude"), anyLong(), anyString());
        verify(observability).recordFallback("claude", "groq", "primary_error");
        verify(observability).recordProviderSuccess(eq("groq"), eq(80L), eq(7), eq(3));
    }

    @Test
    void diagnose_primaryCircuitOpenButFallbackUp_skipsPrimaryAndUsesFallback() {
        ChatResponse fallbackResp = new ChatResponse("from groq", List.of(), "end_turn", "groq", 80, 7, 3, 0);
        when(breaker.allowRequest("claude")).thenReturn(false);
        when(breaker.allowRequest("groq")).thenReturn(true);
        when(fallback.chat(request)).thenReturn(fallbackResp);

        ChatResponse result = dispatcher.diagnose(request);

        assertThat(result).isSameAs(fallbackResp);
        verify(primary, never()).chat(any());
        verify(observability).recordFallback("claude", "groq", "circuit_open");
    }

    @Test
    void diagnose_bothCircuitsOpen_throwsAllProvidersFailed() {
        when(breaker.allowRequest("claude")).thenReturn(false);
        when(breaker.allowRequest("groq")).thenReturn(false);

        assertThatThrownBy(() -> dispatcher.diagnose(request))
                .isInstanceOf(AllProvidersFailedException.class)
                .hasMessageContaining("circuit open");
        verify(primary, never()).chat(any());
        verify(fallback, never()).chat(any());
    }

    @Test
    void diagnose_bothProvidersThrow_raisesAllProvidersFailedWrappingFallbackError() {
        when(breaker.allowRequest("claude")).thenReturn(true);
        when(breaker.allowRequest("groq")).thenReturn(true);
        when(primary.chat(request)).thenThrow(new ProviderException("claude", "boom", 500));
        when(fallback.chat(request)).thenThrow(new ProviderException("groq", "down", 502));

        assertThatThrownBy(() -> dispatcher.diagnose(request))
                .isInstanceOf(AllProvidersFailedException.class);

        ArgumentCaptor<String> err = ArgumentCaptor.forClass(String.class);
        verify(observability, times(2)).recordProviderFailure(anyString(), anyLong(), err.capture());
        assertThat(err.getAllValues())
                .anyMatch(s -> s.contains("boom"))
                .anyMatch(s -> s.contains("down"));
    }
}
