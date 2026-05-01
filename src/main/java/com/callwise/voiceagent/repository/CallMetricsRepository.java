package com.callwise.voiceagent.repository;

import com.callwise.voiceagent.entity.CallMetrics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface CallMetricsRepository extends JpaRepository<CallMetrics, Long> {

    /** Per-turn rows for a session, in chronological order — drives the per-call admin view. */
    List<CallMetrics> findByCallSessionIdOrderByCreatedAtAsc(Long callSessionId);

    /** Aggregate row used by /admin/metrics. Returns one row per provider. */
    @Query("""
            SELECT new com.callwise.voiceagent.repository.CallMetricsRepository$ProviderTotal(
                m.provider,
                COUNT(m),
                COALESCE(SUM(CASE WHEN m.success = true THEN 1 ELSE 0 END), 0),
                COALESCE(SUM(m.inputTokens), 0),
                COALESCE(SUM(m.outputTokens), 0),
                COALESCE(SUM(m.costUsd), 0),
                COALESCE(AVG(m.latencyMs), 0)
            )
            FROM CallMetrics m
            WHERE m.provider IS NOT NULL
            GROUP BY m.provider
            """)
    List<ProviderTotal> aggregateByProvider();

    @Query("SELECT COALESCE(SUM(m.inputTokens),0) + COALESCE(SUM(m.outputTokens),0) FROM CallMetrics m")
    long totalTokens();

    @Query("SELECT COALESCE(SUM(m.costUsd),0) FROM CallMetrics m")
    BigDecimal totalCostUsd();

    /** Projection record returned by {@link #aggregateByProvider()}. */
    record ProviderTotal(
            String provider,
            long turns,
            long successes,
            long inputTokens,
            long outputTokens,
            BigDecimal costUsd,
            double avgLatencyMs
    ) {}
}
