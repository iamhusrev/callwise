package com.callwise.voiceagent.repository;

import com.callwise.voiceagent.entity.ServiceArea;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ServiceAreaRepository extends JpaRepository<ServiceArea, Long> {

    /** All ZIP codes a technician covers — used by the admin technician roster. */
    List<ServiceArea> findByTechnicianIdIn(List<Long> technicianIds);
}
