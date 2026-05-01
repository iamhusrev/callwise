package com.callwise.voiceagent.repository;

import com.callwise.voiceagent.entity.Appointment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AppointmentRepository extends JpaRepository<Appointment, Long> {

    /** Voice flow books at most one appointment per call — used by /admin/calls/{sid}. */
    Optional<Appointment> findByCallSessionId(Long callSessionId);
}
