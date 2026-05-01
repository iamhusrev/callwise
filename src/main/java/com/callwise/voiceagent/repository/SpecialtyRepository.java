package com.callwise.voiceagent.repository;

import com.callwise.voiceagent.entity.Specialty;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SpecialtyRepository extends JpaRepository<Specialty, Long> {

    /** All specialties for a batch of technicians — keeps the admin roster N+1 free. */
    List<Specialty> findByTechnicianIdIn(List<Long> technicianIds);
}
