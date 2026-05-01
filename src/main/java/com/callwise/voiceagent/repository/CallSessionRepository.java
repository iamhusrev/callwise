package com.callwise.voiceagent.repository;

import com.callwise.voiceagent.entity.CallSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CallSessionRepository extends JpaRepository<CallSession, Long> {

    Optional<CallSession> findByCallSid(String callSid);
}
