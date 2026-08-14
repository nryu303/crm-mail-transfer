package com.crm.service;

/**
 * SMS channel adapter (BytePlus SMS OpenAPI). Kept as its own interface rather than folding
 * into {@link OutboundMailService} — that interface's {@code OutboundRequest} is SMTP-shaped
 * (host/port/username/password for a mail relay) and doesn't fit an HTTP+Basic-Auth SMS API.
 *
 * The stub impl ({@link StubOutboundSmsService}) is active by default so the whole SMS pipeline
 * (settings → broadcast/reply → dispatch → dashboard) is testable before BytePlus credentials
 * are handed over. Switching to the real adapter is a one-line env var change
 * ({@code app.sms.adapter=byteplus}), no code change.
 */
public interface OutboundSmsService {

    SendResult send(SmsSendRequest req);

    class SmsSendRequest {
        public final String username;
        public final String password;
        public final String senderName;
        public final String toPhoneNumber;
        public final String body;

        public SmsSendRequest(String username, String password, String senderName,
                               String toPhoneNumber, String body) {
            this.username = username;
            this.password = password;
            this.senderName = senderName;
            this.toPhoneNumber = toPhoneNumber;
            this.body = body;
        }
    }

    class SendResult {
        public final boolean success;
        /** True when the failure is transient and the caller should retry later. */
        public final boolean retriable;
        public final String errorMessage;
        private SendResult(boolean success, boolean retriable, String errorMessage) {
            this.success = success;
            this.retriable = retriable;
            this.errorMessage = errorMessage;
        }
        public static SendResult ok() { return new SendResult(true, false, null); }
        public static SendResult fail(String msg) { return new SendResult(false, false, msg); }
        public static SendResult retriable(String msg) { return new SendResult(false, true, msg); }
    }
}
