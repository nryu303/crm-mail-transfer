package com.crm.dto;

import org.springframework.format.annotation.DateTimeFormat;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.time.LocalDateTime;

public class BroadcastForm {

    /** Admin-only label. Auto-filled from subject if blank. */
    @Size(max = 500)
    private String title;

    /**
     * Required for EMAIL, not for SMS (SMS has no subject line) — so this can't be a plain
     * {@code @NotBlank}; the controller enforces it conditionally on channel instead. The SMS
     * form used to submit a hidden "SMS配信" placeholder to satisfy an unconditional @NotBlank
     * here, but a th:field-bound hidden input always renders the bound (blank) property value
     * and ignores a literal value="..." attribute — so it silently submitted blank, tripped
     * this validation, and every SMS broadcast bounced back to the same form with no visible
     * error (subject has no on-screen field for SMS to show one against). Fixed 2026-07-09.
     */
    @Size(max = 500)
    private String subject;

    @NotBlank(message = "本文を入力してください")
    private String body;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime scheduledAt;

    /** Target filter: carrierCode (softbank/docomo/au or empty=all) */
    @Size(max = 10)
    private String targetCarrierCode;

    /** Target filter: user status (ACTIVE/SUSPENDED or empty=all, default ACTIVE) */
    @Size(max = 16)
    private String targetStatus = "ACTIVE";

    @Min(value = 1, message = "1分あたりの送信件数は1以上で指定してください")
    private Integer ratePerMinute = 60;

    /**
     * Explicit user-id list (from "選択一斉送信" on the user list).
     * When non-empty, the carrier/status filters are ignored and ONLY these users are
     * targeted. When empty/null, the filters drive selection.
     */
    private java.util.List<Long> targetUserIds;

    /** "EMAIL" (default) or "SMS" — set by the 選択一斉SMS配信 button on the user list. */
    private String channel = "EMAIL";

    public java.util.List<Long> getTargetUserIds() { return targetUserIds; }
    public void setTargetUserIds(java.util.List<Long> targetUserIds) { this.targetUserIds = targetUserIds; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public LocalDateTime getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(LocalDateTime scheduledAt) { this.scheduledAt = scheduledAt; }
    public String getTargetCarrierCode() { return targetCarrierCode; }
    public void setTargetCarrierCode(String targetCarrierCode) { this.targetCarrierCode = targetCarrierCode; }
    public String getTargetStatus() { return targetStatus; }
    public void setTargetStatus(String targetStatus) { this.targetStatus = targetStatus; }
    public Integer getRatePerMinute() { return ratePerMinute; }
    public void setRatePerMinute(Integer ratePerMinute) { this.ratePerMinute = ratePerMinute; }
}
