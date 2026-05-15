package com.crm.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.util.Properties;

/**
 * Real SMTP outbound adapter. Uses the host/port/username/plaintext-password from the
 * caller (pool credentials are decrypted by MessageService before calling this).
 *
 * Always registered as a Spring bean (no longer gated by {@code app.outbound.adapter=smtp})
 * so the relay adapter can fall back to it at runtime when the admin flips the
 * "use relay" toggle to OFF on the relay-server settings page.
 *
 * Defaults to STARTTLS on 587. For 465 we assume SSL/TLS (implicit) and switch properties
 * accordingly. Neither mode is disabled, so this works for all three Japanese carriers
 * (smtp.softbank.ne.jp / smtp.au.com / smtp.spmode.ne.jp).
 */
@Service
public class SmtpOutboundMailService implements OutboundMailService {

    private static final Logger log = LoggerFactory.getLogger(SmtpOutboundMailService.class);

    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public SmtpOutboundMailService(
            @Value("${app.outbound.smtp.connect-timeout-ms:10000}") int connectTimeoutMs,
            @Value("${app.outbound.smtp.read-timeout-ms:30000}") int readTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    @Override
    public SendResult send(OutboundRequest req) {
        try {
            Properties props = new Properties();
            props.put("mail.smtp.host", req.smtpHost);
            props.put("mail.smtp.port", String.valueOf(req.smtpPort));
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.connectiontimeout", String.valueOf(connectTimeoutMs));
            props.put("mail.smtp.timeout", String.valueOf(readTimeoutMs));
            props.put("mail.smtp.writetimeout", String.valueOf(readTimeoutMs));
            if (req.smtpPort == 465) {
                props.put("mail.smtp.ssl.enable", "true");
            } else {
                props.put("mail.smtp.starttls.enable", "true");
                props.put("mail.smtp.starttls.required", "true");
            }
            props.put("mail.mime.charset", "UTF-8");

            Session session = Session.getInstance(props, new javax.mail.Authenticator() {
                @Override
                protected javax.mail.PasswordAuthentication getPasswordAuthentication() {
                    return new javax.mail.PasswordAuthentication(req.smtpUsername, req.smtpPassword);
                }
            });

            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(req.fromAddress, false));
            msg.setRecipient(Message.RecipientType.TO, new InternetAddress(req.toAddress, false));
            msg.setSubject(req.subject == null ? "" : req.subject, "UTF-8");
            msg.setText(req.body == null ? "" : req.body, "UTF-8");
            msg.saveChanges();

            Transport.send(msg);
            log.info("[SMTP MAIL] sent: from={} to={} host={}:{} subject=[{}]",
                    req.fromAddress, req.toAddress, req.smtpHost, req.smtpPort, req.subject);
            return SendResult.ok();
        } catch (MessagingException e) {
            log.warn("[SMTP MAIL] failed: from={} to={} host={}:{} error={}",
                    req.fromAddress, req.toAddress, req.smtpHost, req.smtpPort, e.toString());
            return SendResult.fail(e.getMessage());
        } catch (Exception e) {
            log.warn("[SMTP MAIL] unexpected error: {}", e.toString(), e);
            return SendResult.fail(e.toString());
        }
    }
}
