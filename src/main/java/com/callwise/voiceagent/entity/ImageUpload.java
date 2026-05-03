package com.callwise.voiceagent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * Tier 3 — one row per requested image upload.
 *
 * <p>The token is the only secret piece. It's part of the email link, so we keep it
 * column-unique and indexed. {@code visionResult} stores the provider's parsed output as
 * JSONB so admin queries can pluck specific fields without a deserialisation round-trip.
 */
@Entity
@Table(name = "image_upload")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ImageUpload {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "call_session_id", nullable = false)
    private Long callSessionId;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "token", nullable = false, unique = true, length = 64)
    private String token;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ImageUploadStatus status;

    @Column(name = "image_path", length = 512)
    private String imagePath;

    @Column(name = "image_mime_type", length = 64)
    private String imageMimeType;

    // Hibernate 6 maps JSONB natively when SqlTypes.JSON is requested.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "vision_result", columnDefinition = "jsonb")
    private String visionResult;

    @Column(name = "vision_provider", length = 32)
    private String visionProvider;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (status == null) status = ImageUploadStatus.PENDING;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
