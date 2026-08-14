package com.crm.service;

import com.crm.entity.Message;
import com.crm.entity.ReplyPage;
import com.crm.repository.MessageRepository;
import com.crm.repository.ReplyPageRepository;
import com.crm.util.TokenGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// imports for DB-backed domain settings

import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class ReplyPageService {

    private static final int TOKEN_MAX_ATTEMPTS = 8;

    private final ReplyPageRepository replyPageRepository;
    private final MessageRepository messageRepository;
    private final DomainSettingService domainSettings;

    private final int expiryDays;

    public ReplyPageService(ReplyPageRepository replyPageRepository,
                            MessageRepository messageRepository,
                            DomainSettingService domainSettings,
                            @Value("${app.reply-page.token-expiry-days}") int expiryDays) {
        this.replyPageRepository = replyPageRepository;
        this.messageRepository = messageRepository;
        this.domainSettings = domainSettings;
        this.expiryDays = expiryDays;
    }

    /**
     * Create a one-shot reply page for an outbound message. Returns the public URL
     * that admin should embed (or that %reply_url% is replaced with).
     */
    @Transactional
    public String createReplyPageFor(Message outboundMessage) {
        String token = generateUniqueToken();
        ReplyPage rp = new ReplyPage();
        rp.setToken(token);
        rp.setMessageId(outboundMessage.getId());
        rp.setUserId(outboundMessage.getUserId());
        rp.setIsActive(true);
        rp.setExpiresAt(LocalDateTime.now().plusDays(expiryDays));
        replyPageRepository.save(rp);

        // Mirror token onto the MESSAGE row so admin can see there's a reply page for it
        outboundMessage.setReplyPageToken(token);
        messageRepository.save(outboundMessage);

        return domainSettings.buildReplyUrl(token);
    }

    /**
     * Same as {@link #createReplyPageFor} but with a short token (10 chars by default, or the
     * active 外部リンクドメイン's configured length) instead of 64 — SMS is billed per ~65-char
     * segment, so the long token alone would consume the whole budget. Use this from any SMS
     * send path (composeSms, createAndQueueSms) that embeds %reply_url%.
     */
    @Transactional
    public String createShortReplyPageFor(Message outboundMessage) {
        String token = generateUniqueShortToken(domainSettings.getActiveShortTokenLength());
        ReplyPage rp = new ReplyPage();
        rp.setToken(token);
        rp.setMessageId(outboundMessage.getId());
        rp.setUserId(outboundMessage.getUserId());
        rp.setIsActive(true);
        rp.setExpiresAt(LocalDateTime.now().plusDays(expiryDays));
        replyPageRepository.save(rp);

        outboundMessage.setReplyPageToken(token);
        messageRepository.save(outboundMessage);

        return domainSettings.buildReplyUrl(token);
    }

    public Optional<ReplyPage> findByToken(String token) {
        return replyPageRepository.findByToken(token);
    }

    /** Bumps view count + last_viewed_at. Caller should already have checked validity. */
    @Transactional
    public void recordView(ReplyPage rp) {
        rp.setViewCount((rp.getViewCount() == null ? 0 : rp.getViewCount()) + 1);
        rp.setLastViewedAt(LocalDateTime.now());
        replyPageRepository.save(rp);
    }

    public boolean isUsable(ReplyPage rp) {
        if (rp == null) return false;
        if (Boolean.FALSE.equals(rp.getIsActive())) return false;
        if (rp.getExpiresAt() != null && rp.getExpiresAt().isBefore(LocalDateTime.now())) return false;
        return true;
    }

    private String generateUniqueToken() {
        for (int i = 0; i < TOKEN_MAX_ATTEMPTS; i++) {
            String candidate = TokenGenerator.generateReplyToken();
            if (!replyPageRepository.existsByToken(candidate)) return candidate;
        }
        throw new IllegalStateException("could not generate unique reply token");
    }

    private String generateUniqueShortToken(int length) {
        for (int i = 0; i < TOKEN_MAX_ATTEMPTS; i++) {
            String candidate = TokenGenerator.generateShortReplyToken(length);
            if (!replyPageRepository.existsByToken(candidate)) return candidate;
        }
        throw new IllegalStateException("could not generate unique short reply token");
    }
}
