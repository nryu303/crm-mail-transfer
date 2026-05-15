package com.crm.service;

import com.crm.util.LogSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.util.Properties;

/**
 * Outbound via the host's local postfix (SMTP to 127.0.0.1:25, no auth).
 *
 * Used when no RELAY_SERVER row is active — the CRM hands the message to the local
 * postfix instance which then performs MX lookup on the recipient's domain and delivers
 * directly. This requires the host to have a properly configured outbound postfix
 * (open port 25 outbound; SPF / rDNS aligned with the FROM domain).
 *
 * No SMTP auth is used — postfix on localhost trusts loopback connections.
 */
@Service
public class LocalPostfixOutboundMailService implements OutboundMailService {

    private static final Logger log = LoggerFactory.getLogger(LocalPostfixOutboundMailService.class);

    @Override
    public SendResult send(OutboundRequest req) {
        try {
            Properties props = new Properties();
            props.put("mail.smtp.host", "127.0.0.1");
            props.put("mail.smtp.port", "25");
            props.put("mail.smtp.auth", "false");
            props.put("mail.smtp.connectiontimeout", "10000");
            props.put("mail.smtp.timeout", "30000");
            props.put("mail.smtp.writetimeout", "30000");
            // No STARTTLS — loopback is plaintext; postfix handles outbound TLS itself.
            props.put("mail.mime.charset", "UTF-8");

            Session session = Session.getInstance(props);

            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(req.fromAddress, false));
            msg.setRecipient(Message.RecipientType.TO, new InternetAddress(req.toAddress, false));
            msg.setSubject(req.subject == null ? "" : req.subject, "UTF-8");
            msg.setText(req.body == null ? "" : req.body, "UTF-8");
            msg.saveChanges();

            Transport.send(msg);
            log.info("[LOCAL POSTFIX] sent: from={} to={} subject=[{}]",
                    LogSafe.of(req.fromAddress), LogSafe.of(req.toAddress), LogSafe.of(req.subject));
            return SendResult.ok();
        } catch (MessagingException e) {
            log.warn("[LOCAL POSTFIX] failed: from={} to={} error={}",
                    LogSafe.of(req.fromAddress), LogSafe.of(req.toAddress), LogSafe.of(e.toString()));
            return SendResult.fail(e.getMessage());
        } catch (Exception e) {
            log.warn("[LOCAL POSTFIX] unexpected error: {}", LogSafe.of(e.toString()), e);
            return SendResult.fail(e.toString());
        }
    }
}
