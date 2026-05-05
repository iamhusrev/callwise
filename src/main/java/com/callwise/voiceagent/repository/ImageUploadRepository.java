package com.callwise.voiceagent.repository;

import com.callwise.voiceagent.entity.ImageUpload;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Tier 3 image-upload row access. Two read patterns:
 *
 * <ul>
 *   <li>{@code findByToken} — public upload endpoint hits this once per request.</li>
 *   <li>{@code findFirstByCallSessionIdOrderByCreatedAtDescIdDesc} — the AI's
 *       {@code check_image_status} tool wants the most recent upload for the call.</li>
 * </ul>
 */
public interface ImageUploadRepository extends JpaRepository<ImageUpload, Long> {

    Optional<ImageUpload> findByToken(String token);

    Optional<ImageUpload> findFirstByCallSessionIdOrderByCreatedAtDescIdDesc(Long callSessionId);

    List<ImageUpload> findByCallSessionIdOrderByCreatedAtDesc(Long callSessionId);
}
