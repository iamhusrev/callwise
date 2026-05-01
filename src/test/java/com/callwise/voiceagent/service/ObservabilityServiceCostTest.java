package com.callwise.voiceagent.service;

import com.callwise.voiceagent.repository.CallMetricsRepository;
import com.callwise.voiceagent.repository.CallSessionRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Cost calculation correctness — voice agents bill by token; if the math is wrong every
 * downstream report (admin endpoint, financial dashboard) is wrong. Direct unit test on the
 * private {@code computeCost} via reflection because the public methods immediately persist
 * async, which is harder to assert on cost alone.
 */
class ObservabilityServiceCostTest {

    private final ObservabilityService service = new ObservabilityService(
            mock(CallMetricsRepository.class),
            mock(CallSessionRepository.class)
    );

    @Test
    void claude_1000inputAnd500output_costsHaikuPricing() throws Exception {
        BigDecimal cost = invokeComputeCost("claude", 1000, 500);

        // 1000 input × $1/Mtok + 500 output × $5/Mtok = 0.001 + 0.0025 = 0.0035
        assertThat(cost).isEqualByComparingTo(new BigDecimal("0.003500"));
    }

    @Test
    void groq_1000inputAnd500output_costsLlamaPricing() throws Exception {
        BigDecimal cost = invokeComputeCost("groq", 1000, 500);

        // 1000 × $0.59/Mtok + 500 × $0.79/Mtok = 0.00059 + 0.000395 = 0.000985
        assertThat(cost).isEqualByComparingTo(new BigDecimal("0.000985"));
    }

    @Test
    void unknownProvider_returnsZero() throws Exception {
        BigDecimal cost = invokeComputeCost("openai", 1_000_000, 1_000_000);

        // No pricing table entry → zero (defensive: don't make up numbers).
        assertThat(cost).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void claude_zeroTokens_returnsZero() throws Exception {
        BigDecimal cost = invokeComputeCost("claude", 0, 0);
        assertThat(cost).isEqualByComparingTo(new BigDecimal("0.000000"));
    }

    @Test
    void caseInsensitive_providerNameStillMatches() throws Exception {
        BigDecimal lower = invokeComputeCost("claude", 1000, 500);
        BigDecimal upper = invokeComputeCost("CLAUDE", 1000, 500);
        assertThat(upper).isEqualByComparingTo(lower);
    }

    private BigDecimal invokeComputeCost(String provider, int in, int out) throws Exception {
        Method m = ObservabilityService.class.getDeclaredMethod(
                "computeCost", String.class, int.class, int.class);
        m.setAccessible(true);
        return (BigDecimal) m.invoke(service, provider, in, out);
    }
}
