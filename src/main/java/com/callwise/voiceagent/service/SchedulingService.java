package com.callwise.voiceagent.service;

import com.callwise.voiceagent.entity.Appointment;
import com.callwise.voiceagent.entity.AvailabilitySlot;
import com.callwise.voiceagent.entity.Technician;
import com.callwise.voiceagent.exception.SlotNotAvailableException;
import com.callwise.voiceagent.repository.AppointmentRepository;
import com.callwise.voiceagent.repository.AvailabilitySlotRepository;
import com.callwise.voiceagent.repository.TechnicianRepository;
import com.callwise.voiceagent.service.dto.BookingRequest;
import com.callwise.voiceagent.service.dto.SlotInfo;
import com.callwise.voiceagent.service.dto.TechnicianAvailability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Phase 4 scheduling logic. Used by {@code FindTechniciansTool} and
 * {@code ScheduleAppointmentTool} to surface candidates and book slots.
 */
@Service
public class SchedulingService {

    private static final Logger log = LoggerFactory.getLogger(SchedulingService.class);

    /** How far into the future we surface slots. 14 days matches the seed window. */
    private static final int LOOKAHEAD_DAYS = 14;

    /** Top-N candidates returned to the AI. Keeps the prompt tight and the spoken offer short. */
    private static final int TOP_N_TECHNICIANS = 3;

    /** Slots quoted per technician — three is enough for "morning / afternoon / next day" choice. */
    private static final int SLOTS_PER_TECHNICIAN = 3;

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("EEEE MMM d");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("h:mm a");

    private final TechnicianRepository technicianRepository;
    private final AvailabilitySlotRepository availabilitySlotRepository;
    private final AppointmentRepository appointmentRepository;

    public SchedulingService(
            TechnicianRepository technicianRepository,
            AvailabilitySlotRepository availabilitySlotRepository,
            AppointmentRepository appointmentRepository
    ) {
        this.technicianRepository = technicianRepository;
        this.availabilitySlotRepository = availabilitySlotRepository;
        this.appointmentRepository = appointmentRepository;
    }

    /**
     * Find up to {@value #TOP_N_TECHNICIANS} technicians who can handle the given appliance in
     * the given ZIP, each with their next {@value #SLOTS_PER_TECHNICIAN} open slots, sorted
     * by earliest-available.
     *
     * <p>Read-only transaction — no row locks required.
     */
    @Transactional(readOnly = true)
    public List<TechnicianAvailability> findAvailableTechnicians(String zipCode, String applianceType) {
        LocalDate today = LocalDate.now();
        LocalDate end = today.plusDays(LOOKAHEAD_DAYS);

        List<Technician> candidates = technicianRepository.findAvailableByZipAndAppliance(
                zipCode, applianceType, today, end);

        List<TechnicianAvailability> result = new ArrayList<>();
        for (Technician t : candidates) {
            List<AvailabilitySlot> slots = availabilitySlotRepository
                    .findByTechnicianIdAndStatusAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
                            t.getId(), AvailabilitySlot.STATUS_AVAILABLE, today, end);
            if (slots.isEmpty()) continue;

            List<SlotInfo> next = slots.stream()
                    .limit(SLOTS_PER_TECHNICIAN)
                    .map(s -> new SlotInfo(
                            s.getId(),
                            s.getSlotDate(),
                            s.getStartTime(),
                            s.getEndTime(),
                            formatDisplay(s)
                    ))
                    .toList();
            result.add(new TechnicianAvailability(t.getId(), t.getName(), next));
        }

        // Sort by each technician's earliest slot, then take top N. Explicit type on the first
        // lambda is needed so generic inference can carry TechnicianAvailability into thenComparing.
        result.sort(Comparator
                .comparing((TechnicianAvailability ta) -> ta.nextSlots().get(0).slotDate())
                .thenComparing(ta -> ta.nextSlots().get(0).startTime()));

        if (result.size() > TOP_N_TECHNICIANS) {
            result = result.subList(0, TOP_N_TECHNICIANS);
        }

        log.info("scheduling.find zip={} appliance={} candidates={} returned={}",
                zipCode, applianceType, candidates.size(), result.size());
        return result;
    }

    /**
     * Atomically book a slot. Concurrent callers racing for the same slot serialise on the
     * row lock; only one wins.
     *
     * <ul>
     *   <li>{@link Propagation#REQUIRES_NEW} — fresh transaction, isolated from any caller's tx</li>
     *   <li>{@link Isolation#REPEATABLE_READ} — once we read the slot, no phantom updates</li>
     *   <li>{@code findByIdForUpdate} adds a Postgres {@code SELECT ... FOR UPDATE}</li>
     * </ul>
     */
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.REPEATABLE_READ,
            rollbackFor = SlotNotAvailableException.class
    )
    public Appointment bookAppointment(BookingRequest request) {
        AvailabilitySlot slot = availabilitySlotRepository.findByIdForUpdate(request.slotId())
                .orElseThrow(() -> new SlotNotAvailableException(request.slotId(),
                        "slot does not exist"));

        if (!AvailabilitySlot.STATUS_AVAILABLE.equals(slot.getStatus())) {
            throw new SlotNotAvailableException(slot.getId(),
                    "slot already " + slot.getStatus().toLowerCase());
        }
        if (!slot.getTechnicianId().equals(request.technicianId())) {
            throw new SlotNotAvailableException(slot.getId(),
                    "slot belongs to a different technician");
        }

        slot.setStatus(AvailabilitySlot.STATUS_BOOKED);
        availabilitySlotRepository.save(slot);

        Appointment appointment = Appointment.builder()
                .callSessionId(request.callSessionId())
                .technicianId(request.technicianId())
                .availabilitySlotId(slot.getId())
                .customerName(request.customerName())
                .customerPhone(request.customerPhone())
                .customerAddress(request.customerAddress())
                .applianceType(request.applianceType())
                .problemSummary(request.problemSummary())
                .status(Appointment.STATUS_CONFIRMED)
                .build();
        appointment = appointmentRepository.save(appointment);

        log.info("scheduling.booked appointmentId={} slotId={} technicianId={} sessionId={}",
                appointment.getId(), slot.getId(), request.technicianId(), request.callSessionId());
        return appointment;
    }

    /* ---------- helpers ---------- */

    private String formatDisplay(AvailabilitySlot s) {
        // "Monday May 5 at 2:00 PM" — TTS-friendly, no commas that the synth might over-pause on
        return s.getSlotDate().format(DAY_FORMAT) + " at " + s.getStartTime().format(TIME_FORMAT);
    }
}
