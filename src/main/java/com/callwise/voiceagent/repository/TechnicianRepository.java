package com.callwise.voiceagent.repository;

import com.callwise.voiceagent.entity.Technician;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface TechnicianRepository extends JpaRepository<Technician, Long> {

    /**
     * Active technicians who cover {@code zipCode}, are qualified for {@code applianceType},
     * and have at least one AVAILABLE slot between {@code fromDate} and {@code toDate}.
     *
     * <p>Uses a native query because the join spans three FK tables that we don't model with
     * {@code @ManyToOne} (deliberately — see CLAUDE.md: minimise JPA navigation in favour of
     * derived queries + native joins where joins span multiple tables).
     */
    @Query(value = """
            SELECT DISTINCT t.*
              FROM technicians t
              JOIN service_areas sa ON sa.technician_id = t.id
              JOIN specialties sp ON sp.technician_id = t.id
              JOIN availability_slots av ON av.technician_id = t.id
             WHERE t.active = true
               AND sa.zip_code = :zipCode
               AND sp.appliance_type = :applianceType
               AND av.status = 'AVAILABLE'
               AND av.slot_date BETWEEN :fromDate AND :toDate
            """, nativeQuery = true)
    List<Technician> findAvailableByZipAndAppliance(
            @Param("zipCode") String zipCode,
            @Param("applianceType") String applianceType,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate
    );
}
