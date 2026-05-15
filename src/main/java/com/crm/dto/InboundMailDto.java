package com.crm.dto;

import javax.validation.constraints.Size;

/**
 * Payload shape for the AMG → system webhook.
 * Exact field names are still subject to the AMG contract; this is the best-effort
 * mapping of "from / to / subject / body / raw / message_id" that we expect.
 *
 * Size caps are deliberately generous (RFC line limit for headers, 200KB body) but
 * exist so that a malicious or buggy peer cannot push arbitrarily large payloads
 * into JSON deserialisation memory or DB columns.
 */
public class InboundMailDto {

    @Size(max = 998)
    private String from;

    @Size(max = 998)
    private String to;

    @Size(max = 998)
    private String subject;

    @Size(max = 200_000)
    private String body;

    /** Full raw RFC822 content, optional. Truncated server-side regardless. */
    @Size(max = 65_535)
    private String raw;

    /** RFC822 Message-ID header value (ideally without angle brackets). Used for dedup. */
    @Size(max = 998)
    private String messageId;

    public String getFrom() { return from; }
    public void setFrom(String from) { this.from = from; }
    public String getTo() { return to; }
    public void setTo(String to) { this.to = to; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public String getRaw() { return raw; }
    public void setRaw(String raw) { this.raw = raw; }
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
}
