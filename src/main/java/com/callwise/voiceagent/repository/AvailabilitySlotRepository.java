package com.callwise.voiceagent.repository;

import com.callwise.voiceagent.entity.AvailabilitySlot;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface AvailabilitySlotRepository extends JpaRepository<AvailabilitySlot, Long> {

    /** Slots for a technician in a date range with a given status. Used to surface "next 3 slots". */
    List<AvailabilitySlot> findByTechnicianIdAndStatusAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
            Long technicianId, String status, LocalDate from, LocalDate to);

    /** All slots for a technician in a date range, any status — used by the admin schedule view. */
    List<AvailabilitySlot> findByTechnicianIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
            Long technicianId, LocalDate from, LocalDate to);

    /** Aggregate slot counts per technician — N+1-free roster summary for the admin endpoint. */
    @Query("SELECT s.technicianId, s.status, COUNT(s) FROM AvailabilitySlot s " +
            "WHERE s.technicianId IN :technicianIds AND s.slotDate BETWEEN :from AND :to " +
            "GROUP BY s.technicianId, s.status")
    List<Object[]> countByTechnicianAndStatus(
            @Param("technicianIds") List<Long> technicianIds,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    /**
     * Lock the slot row for update — prevents two concurrent bookings of the same slot.
     * Postgres translates this to {@code SELECT ... FOR UPDATE}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM AvailabilitySlot s WHERE s.id = :id")
    Optional<AvailabilitySlot> findByIdForUpdate(@Param("id") Long id);
}
