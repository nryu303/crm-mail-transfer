package com.crm.service;

import com.crm.util.JapanesePhoneNumbers;
import com.crm.util.LogSafe;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Real BytePlus SMS OpenAPI adapter (Basic Auth: Username = Message Group ID,
 * Password = OpenAPI password — no AccessKeyID/SecretAccessKey). Activate with
 * {@code app.sms.adapter=byteplus}.
 *
 * API contract per https://docs.byteplus.com/en/docs/byteplus-sms/docs-openapi-overview and
 * https://docs.byteplus.com/en/docs/byteplus-sms/docs-sendsms (confirmed 2026-07-08):
 *   POST https://sms.byteplusapi.com/sms/openapi/send_sms
 *   Authorization: Basic base64(username:password)
 *   Content-Type: application/json;charset=utf-8
 *   Body: {"PhoneNumbers":"+819...","Content":"...","From":"<senderName>"}
 *   Success: 2xx with Result.MessageID present.
 *
 * Two delivery modes, chosen by {@code sms.local_delivery_enabled} (設定 > SMS配信設定):
 *   - local (default): this method calls BytePlus directly (sendDirect).
 *   - relay (2026-07-13, per operator request): POSTs to the 転送機's own
 *     /api/sms/send (sendViaRelay), which enforces its own hourly cap and makes the
 *     actual BytePlus call itself — mirrors how outbound EMAIL already goes through
 *     the relay rather than straight from this server.
 */
@Service
@ConditionalOnProperty(name = "app.sms.adapter", havingValue = "byteplus")
public class BytePlusSmsOutboundService implements OutboundSmsService {

    private static final Logger log = LoggerFactory.getLogger(BytePlusSmsOutboundService.class);
    private static final String ENDPOINT = "https://sms.byteplusapi.com/sms/openapi/send_sms";

    private final ObjectMapper objectMapper;
    private final SmsSettingService smsSettingService;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public BytePlusSmsOutboundService(ObjectMapper objectMapper,
                                       SmsSettingService smsSettingService,
                                       @Value("${app.sms.byteplus.connect-timeout-ms:10000}") int connectTimeoutMs,
                                       @Value("${app.sms.byteplus.read-timeout-ms:15000}") int readTimeoutMs) {
        this.objectMapper = objectMapper;
        this.smsSettingService = smsSettingService;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    @Override
    public SendResult send(SmsSendRequest req) {
        if (req.username == null || req.username.trim().isEmpty()
                || req.password == null || req.password.trim().isEmpty()) {
            return SendResult.fail("BytePlus username/password is not configured (設定 > SMS配信設定)");
        }
        String e164 = JapanesePhoneNumbers.toE164(req.toPhoneNumber);
        if (e164 == null) {
            return SendResult.fail("invalid phone number: " + LogSafe.of(req.toPhoneNumber));
        }

        if (smsSettingService.isLocalDelivery()) {
            return sendDirect(req, e164);
        }
        return sendViaRelay(req, e164);
    }

    private SendResult sendDirect(SmsSendRequest req, String e164) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("PhoneNumbers", e164);
        payload.put("Content", req.body);
        payload.put("From", req.senderName == null || req.senderName.trim().isEmpty() ? "info" : req.senderName);

        String auth = Base64.getEncoder().encodeToString(
                (req.username + ":" + req.password).getBytes(StandardCharsets.UTF_8));

        RequestConfig rc = RequestConfig.custom()
                .setConnectTimeout(connectTimeoutMs)
                .setConnectionRequestTimeout(connectTimeoutMs)
                .setSocketTimeout(readTimeoutMs)
                .build();

        try (CloseableHttpClient http = HttpClientBuilder.create().setDefaultRequestConfig(rc).build()) {
            String body = objectMapper.writeValueAsString(payload);
            HttpPost post = new HttpPost(ENDPOINT);
            post.setHeader("Authorization", "Basic " + auth);
            post.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON.withCharset(StandardCharsets.UTF_8)));

            try (CloseableHttpResponse resp = http.execute(post)) {
                int code = resp.getStatusLine().getStatusCode();
                String respBody = resp.getEntity() == null ? ""
                        : EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);

                if (code >= 200 && code < 300) {
                    JsonNode root = parseQuietly(respBody);
                    JsonNode messageIds = root == null ? null : root.path("Result").path("MessageID");
                    if (messageIds != null && messageIds.isArray() && messageIds.size() > 0) {
                        log.info("[BYTEPLUS SMS] sent direct: to={} status={} messageId={}",
                                LogSafe.of(req.toPhoneNumber), code, LogSafe.of(messageIds.get(0).asText()));
                        return SendResult.ok();
                    }
                    log.warn("[BYTEPLUS SMS] 2xx but no MessageID in response: to={} status={} resp={}",
                            LogSafe.of(req.toPhoneNumber), code, LogSafe.of(truncate(respBody, 500)));
                    return SendResult.fail("unexpected response: " + truncate(respBody, 200));
                }

                String errMsg = extractErrorMessage(respBody);
                boolean retriable = code >= 500 && code < 600;
                log.warn("[BYTEPLUS SMS] {}: to={} status={} resp={}",
                        retriable ? "transient fail" : "permanent fail",
                        LogSafe.of(req.toPhoneNumber), code, LogSafe.of(truncate(respBody, 500)));
                String msg = "byteplus returned " + code + (errMsg != null ? ": " + errMsg : "");
                return retriable ? SendResult.retriable(msg) : SendResult.fail(msg);
            }
        } catch (java.net.SocketTimeoutException | java.net.ConnectException e) {
            log.warn("[BYTEPLUS SMS] network error (retriable): to={} error={}",
                    LogSafe.of(req.toPhoneNumber), LogSafe.of(e.toString()));
            return SendResult.retriable(e.toString());
        } catch (Exception e) {
            log.warn("[BYTEPLUS SMS] error: to={} error={}",
                    LogSafe.of(req.toPhoneNumber), LogSafe.of(e.toString()));
            return SendResult.fail(e.toString());
        }
    }

    private SendResult sendViaRelay(SmsSendRequest req, String e164) {
        String relayIp = smsSettingService.getRelayIp();
        String relayToken = smsSettingService.getRelayToken();
        if (relayIp == null || relayIp.trim().isEmpty()) {
            return SendResult.fail("転送機IPが設定されていません (設定 > SMS配信設定)");
        }
        if (relayToken == null || relayToken.trim().isEmpty()) {
            return SendResult.fail("転送機トークンが設定されていません (設定 > SMS配信設定)");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("phoneNumber", e164);
        payload.put("content", req.body);
        payload.put("senderName", req.senderName == null || req.senderName.trim().isEmpty() ? "info" : req.senderName);
        payload.put("byteplusUsername", req.username);
        payload.put("byteplusPassword", req.password);

        RequestConfig rc = RequestConfig.custom()
                .setConnectTimeout(connectTimeoutMs)
                .setConnectionRequestTimeout(connectTimeoutMs)
                .setSocketTimeout(readTimeoutMs)
                .build();

        String endpoint = "http://" + relayIp.trim() + "/api/sms/send";
        try (CloseableHttpClient http = HttpClientBuilder.create().setDefaultRequestConfig(rc).build()) {
            String body = objectMapper.writeValueAsString(payload);
            HttpPost post = new HttpPost(endpoint);
            post.setHeader("X-Relay-Token", relayToken);
            post.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON.withCharset(StandardCharsets.UTF_8)));

            try (CloseableHttpResponse resp = http.execute(post)) {
                int code = resp.getStatusLine().getStatusCode();
                String respBody = resp.getEntity() == null ? ""
                        : EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
                JsonNode root = parseQuietly(respBody);
                boolean success = root != null && root.path("success").asBoolean(false);

                if (code == 200 && success) {
                    String messageId = root.path("messageId").asText(null);
                    log.info("[BYTEPLUS SMS] sent via relay: to={} messageId={}",
                            LogSafe.of(req.toPhoneNumber), LogSafe.of(messageId));
                    return SendResult.ok();
                }
                String errMsg = root != null && root.has("error") ? root.get("error").asText() : truncate(respBody, 200);
                boolean retriable = code == 429 || code >= 500 || (root != null && root.path("retriable").asBoolean(false));
                log.warn("[BYTEPLUS SMS] relay {}: to={} status={} resp={}",
                        retriable ? "transient fail" : "permanent fail",
                        LogSafe.of(req.toPhoneNumber), code, LogSafe.of(truncate(respBody, 500)));
                String msg = "relay returned " + code + ": " + errMsg;
                return retriable ? SendResult.retriable(msg) : SendResult.fail(msg);
            }
        } catch (java.net.SocketTimeoutException | java.net.ConnectException e) {
            log.warn("[BYTEPLUS SMS] relay network error (retriable): to={} error={}",
                    LogSafe.of(req.toPhoneNumber), LogSafe.of(e.toString()));
            return SendResult.retriable(e.toString());
        } catch (Exception e) {
            log.warn("[BYTEPLUS SMS] relay error: to={} error={}",
                    LogSafe.of(req.toPhoneNumber), LogSafe.of(e.toString()));
            return SendResult.fail(e.toString());
        }
    }

    private JsonNode parseQuietly(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    /** Best-effort: BytePlus's Volcengine-style error envelopes nest under ResponseMetadata.Error. */
    private String extractErrorMessage(String respBody) {
        JsonNode root = parseQuietly(respBody);
        if (root == null) return null;
        JsonNode err = root.path("ResponseMetadata").path("Error");
        if (!err.isMissingNode() && err.has("Message")) return err.get("Message").asText();
        if (root.has("message")) return root.get("message").asText();
        return null;
    }

    private static String truncate(String s, int n) {
        return s == null ? "" : (s.length() <= n ? s : s.substring(0, n) + "...");
    }
}
