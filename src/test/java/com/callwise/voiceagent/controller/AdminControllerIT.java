package com.callwise.voiceagent.controller;

import com.callwise.voiceagent.AbstractIntegrationTest;
import com.callwise.voiceagent.entity.CallSession;
import com.callwise.voiceagent.repository.CallSessionRepository;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Smoke-test the admin endpoints over real HTTP + real Postgres.
 *
 * <p>We're not asserting the full transcript shape (covered by service tests) — just that the
 * controller wires the repos correctly and the JSON envelope is what the README documents.
 */
@AutoConfigureMockMvc
@Disabled("TestContainers ↔ Docker Desktop macOS API incompatibility — see SchedulingServiceIT")
class AdminControllerIT extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired CallSessionRepository sessionRepository;

    @Test
    void getCall_unknownCallSid_returns404() throws Exception {
        mockMvc.perform(get("/admin/calls/CA-DOES-NOT-EXIST"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getCall_existingSession_returnsSessionAndTotals() throws Exception {
        CallSession s = new CallSession();
        s.setCallSid("CA-ADMIN-001");
        s.setPhoneNumber("+15555550133");
        s.setStatus("ACTIVE");
        sessionRepository.save(s);

        mockMvc.perform(get("/admin/calls/CA-ADMIN-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session.callSid").value("CA-ADMIN-001"))
                .andExpect(jsonPath("$.totals.input_tokens").exists())
                .andExpect(jsonPath("$.transcript").isArray())
                .andExpect(jsonPath("$.metrics").isArray());
    }

    @Test
    void getMetrics_returnsAggregateEnvelope() throws Exception {
        mockMvc.perform(get("/admin/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_tokens").exists())
                .andExpect(jsonPath("$.total_cost_usd").exists())
                .andExpect(jsonPath("$.by_provider").isArray());
    }

    @Test
    void health_returnsUp() throws Exception {
        mockMvc.perform(get("/admin/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
