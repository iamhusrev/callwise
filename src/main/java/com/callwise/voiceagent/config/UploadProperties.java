package com.callwise.voiceagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Tier 3 upload settings. Bound from {@code callwise.uploads.*} in application.yml.
 */
@Configuration
@ConfigurationProperties(prefix = "callwise.uploads")
public class UploadProperties {

    /** Filesystem dir where uploaded photos are written. Container path; bind-mounted on host. */
    private String dir = "./uploads";

    /** Hard size cap. Defence-in-depth alongside spring.servlet.multipart.max-file-size. */
    private long maxBytes = 10_485_760L;

    /** Token TTL — caller must upload within this window after receiving the email. */
    private int tokenTtlMinutes = 30;

    /** Public URL prefix to embed in emailed links. Typically the ngrok HTTPS URL. */
    private String publicBaseUrl = "";

    /** Email "From:" header. */
    private String mailFrom = "no-reply@callwise.local";

    public String getDir() { return dir; }
    public void setDir(String dir) { this.dir = dir; }

    public long getMaxBytes() { return maxBytes; }
    public void setMaxBytes(long maxBytes) { this.maxBytes = maxBytes; }

    public int getTokenTtlMinutes() { return tokenTtlMinutes; }
    public void setTokenTtlMinutes(int tokenTtlMinutes) { this.tokenTtlMinutes = tokenTtlMinutes; }

    public String getPublicBaseUrl() { return publicBaseUrl; }
    public void setPublicBaseUrl(String publicBaseUrl) { this.publicBaseUrl = publicBaseUrl; }

    public String getMailFrom() { return mailFrom; }
    public void setMailFrom(String mailFrom) { this.mailFrom = mailFrom; }
}
