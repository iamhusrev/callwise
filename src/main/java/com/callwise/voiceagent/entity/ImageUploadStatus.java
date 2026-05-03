package com.callwise.voiceagent.entity;

/**
 * Lifecycle of a Tier 3 image upload row.
 *
 * <ul>
 *   <li>{@link #PENDING}  — token created, email sent, no file yet.</li>
 *   <li>{@link #UPLOADED} — bytes saved on disk; vision analysis kicked off async.</li>
 *   <li>{@link #ANALYZED} — vision provider returned a result; AI may use it.</li>
 *   <li>{@link #FAILED}   — upload was accepted but vision analysis failed (both providers).</li>
 *   <li>{@link #EXPIRED}  — token TTL elapsed before any upload arrived.</li>
 * </ul>
 */
public enum ImageUploadStatus {
    PENDING,
    UPLOADED,
    ANALYZED,
    FAILED,
    EXPIRED
}
