package com.callwise.voiceagent.repository;

import com.callwise.voiceagent.entity.ConversationMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, Long> {

    /** All messages for a session in chronological order — used to rebuild the conversation prefix. */
    List<ConversationMessage> findByCallSessionIdOrderByTurnNumberAsc(Long callSessionId);

    /** Highest turn number on the session, or null if there are no messages yet. */
    @Query("SELECT MAX(m.turnNumber) FROM ConversationMessage m WHERE m.callSessionId = :sessionId")
    Integer findMaxTurnNumber(@Param("sessionId") Long sessionId);
}
