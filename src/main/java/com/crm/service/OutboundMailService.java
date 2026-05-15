package com.crm.service;

/**
 * Relay-server-side adapter. Real implementation POSTs to the 転送機 API at 160.251.199.135.
 * For now the stub impl just records "sent" so the whole UI flow is testable without the API
 * contract being finalised.
 */
public interface OutboundMailService {

    SendResult send(OutboundRequest req);

    class OutboundRequest {
        public final String fromAddress;
        public final String toAddress;
        public final String subject;
        public final String body;
        public final String smtpHost;
        public final int smtpPort;
        public final String smtpUsername;
        /** Plaintext (already decrypted by caller). Never log this. */
        public final String smtpPassword;

        public OutboundRequest(String fromAddress, String toAddress, String subject, String body,
                               String smtpHost, int smtpPort,
                               String smtpUsername, String smtpPassword) {
            this.fromAddress = fromAddress;
            this.toAddress = toAddress;
            this.subject = subject;
            this.body = body;
            this.smtpHost = smtpHost;
            this.smtpPort = smtpPort;
            this.smtpUsername = smtpUsername;
            this.smtpPassword = smtpPassword;
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
        /** Permanent failure — will not be retried automatically. */
        public static SendResult fail(String msg) { return new SendResult(false, false, msg); }
        /** Transient failure (e.g. relay TEMPFAIL, connection timeout) — caller should retry. */
        public static SendResult retriable(String msg) { return new SendResult(false, true, msg); }
    }
}
