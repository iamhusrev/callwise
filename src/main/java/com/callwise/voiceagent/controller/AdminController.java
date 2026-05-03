package com.callwise.voiceagent.controller;

import com.callwise.voiceagent.entity.Appointment;
import com.callwise.voiceagent.entity.AvailabilitySlot;
import com.callwise.voiceagent.entity.CallMetrics;
import com.callwise.voiceagent.entity.CallSession;
import com.callwise.voiceagent.entity.ConversationMessage;
import com.callwise.voiceagent.entity.ImageUpload;
import com.callwise.voiceagent.entity.ServiceArea;
import com.callwise.voiceagent.entity.Specialty;
import com.callwise.voiceagent.entity.Technician;
import com.callwise.voiceagent.repository.AppointmentRepository;
import com.callwise.voiceagent.repository.AvailabilitySlotRepository;
import com.callwise.voiceagent.repository.CallSessionRepository;
import com.callwise.voiceagent.repository.ConversationMessageRepository;
import com.callwise.voiceagent.repository.ImageUploadRepository;
import com.callwise.voiceagent.repository.ServiceAreaRepository;
import com.callwise.voiceagent.repository.SpecialtyRepository;
import com.callwise.voiceagent.repository.TechnicianRepository;
import com.callwise.voiceagent.service.ObservabilityService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Read-only admin/operator endpoints. We're not shipping a UI (CLAUDE.md trade-off — admin
 * endpoints + structured logs are production-grade observability for a take-home), so a reviewer
 * can curl any of these to inspect a call.
 *
 * <p>No auth — take-home scope. In production this would sit behind Spring Security + an API key.
 */
@RestController
@RequestMapping(value = "/admin", produces = MediaType.APPLICATION_JSON_VALUE)
public class AdminController {

    private static final int LIST_LIMIT_MAX = 100;
    private static final int SLOTS_LOOKAHEAD_DAYS_DEFAULT = 14;

    private final CallSessionRepository sessionRepository;
    private final ConversationMessageRepository messageRepository;
    private final AppointmentRepository appointmentRepository;
    private final TechnicianRepository technicianRepository;
    private final ServiceAreaRepository serviceAreaRepository;
    private final SpecialtyRepository specialtyRepository;
    private final AvailabilitySlotRepository availabilitySlotRepository;
    private final ImageUploadRepository imageUploadRepository;
    private final ObservabilityService observability;
    private final ObjectMapper objectMapper;

    public AdminController(
            CallSessionRepository sessionRepository,
            ConversationMessageRepository messageRepository,
            AppointmentRepository appointmentRepository,
            TechnicianRepository technicianRepository,
            ServiceAreaRepository serviceAreaRepository,
            SpecialtyRepository specialtyRepository,
            AvailabilitySlotRepository availabilitySlotRepository,
            ImageUploadRepository imageUploadRepository,
            ObservabilityService observability,
            ObjectMapper objectMapper
    ) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.appointmentRepository = appointmentRepository;
        this.technicianRepository = technicianRepository;
        this.serviceAreaRepository = serviceAreaRepository;
        this.specialtyRepository = specialtyRepository;
        this.availabilitySlotRepository = availabilitySlotRepository;
        this.imageUploadRepository = imageUploadRepository;
        this.observability = observability;
        this.objectMapper = objectMapper;
    }

    /** Recent calls — newest first. Use this to find a callSid to drill into. */
    @GetMapping("/calls")
    public List<Map<String, Object>> listCalls(@RequestParam(defaultValue = "20") int limit) {
        Pageable page = PageRequest.of(0, Math.min(Math.max(limit, 1), LIST_LIMIT_MAX),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return sessionRepository.findAll(page).stream()
                .map(this::summariseCall)
                .toList();
    }

    /**
     * Technician roster — every technician on file with the ZIP codes they cover, the appliance
     * specialties they handle, and a summary of their slot inventory over the next 14 days.
     * One query per related table, no N+1.
     */
    @GetMapping("/technicians")
    public List<Map<String, Object>> listTechnicians() {
        List<Technician> technicians = technicianRepository.findAll(
                Sort.by(Sort.Direction.ASC, "id"));
        if (technicians.isEmpty()) return List.of();

        List<Long> ids = technicians.stream().map(Technician::getId).toList();

        Map<Long, Set<String>> zipsByTech = new HashMap<>();
        for (ServiceArea sa : serviceAreaRepository.findByTechnicianIdIn(ids)) {
            zipsByTech.computeIfAbsent(sa.getTechnicianId(), k -> new TreeSet<>()).add(sa.getZipCode());
        }

        Map<Long, List<Map<String, Object>>> specsByTech = new HashMap<>();
        for (Specialty sp : specialtyRepository.findByTechnicianIdIn(ids)) {
            specsByTech.computeIfAbsent(sp.getTechnicianId(), k -> new ArrayList<>())
                    .add(Map.of(
                            "applianceType", sp.getApplianceType(),
                            "skillLevel", sp.getSkillLevel() == null ? "" : sp.getSkillLevel()
                    ));
        }

        LocalDate today = LocalDate.now();
        LocalDate horizon = today.plusDays(SLOTS_LOOKAHEAD_DAYS_DEFAULT);
        Map<Long, Map<String, Long>> slotCounts = new HashMap<>();
        for (Object[] row : availabilitySlotRepository.countByTechnicianAndStatus(ids, today, horizon)) {
            Long techId = (Long) row[0];
            String status = (String) row[1];
            Long count = (Long) row[2];
            slotCounts.computeIfAbsent(techId, k -> new HashMap<>()).put(status, count);
        }

        return technicians.stream()
                .map(t -> {
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("id", t.getId());
                    view.put("name", t.getName());
                    view.put("email", t.getEmail());
                    view.put("phone", t.getPhone());
                    view.put("active", t.getActive());
                    view.put("employedSince", t.getEmployedSince());
                    view.put("serviceAreas", zipsByTech.getOrDefault(t.getId(), Set.of()));
                    view.put("specialties", specsByTech.getOrDefault(t.getId(), List.of()));

                    Map<String, Long> counts = slotCounts.getOrDefault(t.getId(), Map.of());
                    Map<String, Object> slotSummary = new LinkedHashMap<>();
                    slotSummary.put("lookaheadDays", SLOTS_LOOKAHEAD_DAYS_DEFAULT);
                    slotSummary.put("available", counts.getOrDefault("AVAILABLE", 0L));
                    slotSummary.put("booked", counts.getOrDefault("BOOKED", 0L));
                    view.put("slotSummary", slotSummary);
                    return view;
                })
                .toList();
    }

    /**
     * Day-by-day schedule for one technician — every slot in the lookahead window with its
     * date, time, and status (AVAILABLE / BOOKED). Use after picking an id from /admin/technicians.
     */
    @GetMapping("/technicians/{id}/slots")
    public ResponseEntity<Map<String, Object>> getTechnicianSlots(
            @PathVariable Long id,
            @RequestParam(defaultValue = "14") int days
    ) {
        return technicianRepository.findById(id)
                .map(tech -> {
                    int window = Math.min(Math.max(days, 1), 60);
                    LocalDate today = LocalDate.now();
                    LocalDate horizon = today.plusDays(window);
                    List<AvailabilitySlot> slots = availabilitySlotRepository
                            .findByTechnicianIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
                                    id, today, horizon);

                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("technicianId", tech.getId());
                    body.put("technicianName", tech.getName());
                    body.put("from", today);
                    body.put("to", horizon);
                    body.put("slots", slots.stream().map(this::summariseSlot).toList());
                    return ResponseEntity.ok(body);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Recent bookings — the concrete outcome of calls that converted. */
    @GetMapping("/appointments")
    public List<Map<String, Object>> listAppointments(@RequestParam(defaultValue = "20") int limit) {
        Pageable page = PageRequest.of(0, Math.min(Math.max(limit, 1), LIST_LIMIT_MAX),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        List<Appointment> appointments = appointmentRepository.findAll(page).getContent();

        // Batch-resolve callSids in one query rather than per-row (avoid N+1).
        Set<Long> sessionIds = appointments.stream()
                .map(Appointment::getCallSessionId)
                .collect(Collectors.toSet());
        Map<Long, String> callSidBySessionId = sessionRepository.findAllById(sessionIds).stream()
                .collect(Collectors.toMap(CallSession::getId, CallSession::getCallSid));

        return appointments.stream()
                .map(a -> summariseAppointment(a, callSidBySessionId.get(a.getCallSessionId())))
                .toList();
    }

    /** Per-call view: session row + transcript + per-turn metrics + cost roll-up. */
    @GetMapping("/calls/{callSid}")
    public ResponseEntity<Map<String, Object>> getCall(@PathVariable String callSid) {
        return sessionRepository.findByCallSid(callSid)
                .map(session -> ResponseEntity.ok(buildCallView(session)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Aggregate metrics across all calls. */
    @GetMapping("/metrics")
    public Map<String, Object> getMetrics() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("total_tokens", observability.totalTokens());
        body.put("total_cost_usd", observability.totalCostUsd());
        body.put("by_provider", observability.aggregateByProvider());
        return body;
    }

    /** Lightweight liveness — distinct from /actuator/health which the docker healthcheck owns. */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP");
    }

    /* ===== Tier 3 — image upload inspection ===== */

    /**
     * Inspect a single Tier 3 upload by its token. Returns the row (status, mime, paths,
     * timestamps) plus the parsed vision_result so a reviewer can see exactly what the
     * vision model said about the photo without running a {@code psql -c "select ..."}.
     */
    @GetMapping("/uploads/{token}")
    public ResponseEntity<Map<String, Object>> getUpload(@PathVariable String token) {
        return imageUploadRepository.findByToken(token)
                .map(row -> ResponseEntity.ok(summariseUpload(row)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * List all Tier 3 uploads for one call (newest first). Useful when the caller uploaded
     * multiple photos in a single call, or to confirm the lifecycle (PENDING → ANALYZED).
     */
    @GetMapping("/calls/{callSid}/uploads")
    public ResponseEntity<List<Map<String, Object>>> listUploadsForCall(@PathVariable String callSid) {
        return sessionRepository.findByCallSid(callSid)
                .map(session -> ResponseEntity.ok(
                        imageUploadRepository.findByCallSessionIdOrderByCreatedAtDesc(session.getId()).stream()
                                .map(this::summariseUpload)
                                .toList()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private Map<String, Object> summariseUpload(ImageUpload row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row.getId());
        m.put("call_session_id", row.getCallSessionId());
        m.put("token", row.getToken());
        m.put("email", row.getEmail());
        m.put("status", row.getStatus());
        m.put("image_path", row.getImagePath());
        m.put("image_mime_type", row.getImageMimeType());
        m.put("vision_provider", row.getVisionProvider());
        // Re-parse the JSONB string so the response is a real nested object, not an
        // escaped JSON-in-JSON blob — much friendlier in Postman / browsers.
        if (row.getVisionResult() != null && !row.getVisionResult().isBlank()) {
            try {
                JsonNode parsed = objectMapper.readTree(row.getVisionResult());
                m.put("vision_result", parsed);
            } catch (Exception e) {
                m.put("vision_result_raw", row.getVisionResult());
            }
        }
        m.put("created_at", row.getCreatedAt());
        m.put("updated_at", row.getUpdatedAt());
        m.put("expires_at", row.getExpiresAt());
        return m;
    }

    /* ===== helpers ===== */

    private Map<String, Object> summariseSlot(AvailabilitySlot s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("date", s.getSlotDate());
        m.put("startTime", s.getStartTime());
        m.put("endTime", s.getEndTime());
        m.put("status", s.getStatus());
        return m;
    }

    private Map<String, Object> summariseCall(CallSession s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("callSid", s.getCallSid());
        m.put("phoneNumber", s.getPhoneNumber());
        m.put("status", s.getStatus());
        m.put("applianceType", s.getApplianceType());
        m.put("startedAt", s.getStartedAt());
        m.put("endedAt", s.getEndedAt());
        return m;
    }

    private Map<String, Object> summariseAppointment(Appointment a, String callSid) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("callSid", callSid);
        m.put("customerName", a.getCustomerName());
        m.put("customerPhone", a.getCustomerPhone());
        m.put("applianceType", a.getApplianceType());
        m.put("problemSummary", a.getProblemSummary());
        m.put("status", a.getStatus());
        m.put("createdAt", a.getCreatedAt());
        return m;
    }

    private Map<String, Object> buildCallView(CallSession session) {
        List<ConversationMessage> transcript = messageRepository
                .findByCallSessionIdOrderByTurnNumberAsc(session.getId());
        List<CallMetrics> metrics = observability.findForSession(session.getId());

        BigDecimal totalCost = BigDecimal.ZERO;
        long totalIn = 0, totalOut = 0;
        for (CallMetrics m : metrics) {
            if (m.getCostUsd() != null) totalCost = totalCost.add(m.getCostUsd());
            if (m.getInputTokens() != null) totalIn += m.getInputTokens();
            if (m.getOutputTokens() != null) totalOut += m.getOutputTokens();
        }

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("session", Map.of(
                "id", session.getId(),
                "callSid", session.getCallSid(),
                "phoneNumber", session.getPhoneNumber(),
                "status", session.getStatus(),
                "createdAt", session.getCreatedAt(),
                "updatedAt", session.getUpdatedAt()
        ));
        view.put("totals", Map.of(
                "input_tokens", totalIn,
                "output_tokens", totalOut,
                "cost_usd", totalCost
        ));
        view.put("transcript", transcript);
        view.put("metrics", metrics);
        return view;
    }
}
