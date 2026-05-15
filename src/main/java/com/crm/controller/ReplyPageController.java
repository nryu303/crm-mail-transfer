package com.crm.controller;

import com.crm.entity.CrmUser;
import com.crm.entity.Message;
import com.crm.entity.ReplyPage;
import com.crm.entity.ReplyPageSetting;
import com.crm.repository.CrmUserRepository;
import com.crm.repository.MessageRepository;
import com.crm.service.ReplyPageService;
import com.crm.service.ReplyPageSettingService;
import com.crm.service.ReplyRateLimitService;
import com.crm.service.UserActivityService;
import com.crm.util.ClientIpResolver;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import javax.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Public (unauthenticated) reply page. Accessed via /reply/{token}.
 *
 * - GET renders the form (memo header + subject/body inputs)
 * - POST accepts the user's reply, creates MESSAGE(DIRECTION=IN, CHANNEL=WEB_REPLY)
 * - Token is single-bucket: remains usable for the configured expiry window (default 30d).
 */
@Controller
public class ReplyPageController {

    /** Hard caps on the public reply form. Matches the bulk-reply guard in InboxController. */
    private static final int REPLY_SUBJECT_MAX = 500;
    private static final int REPLY_BODY_MAX = 60_000;

    /**
     * User-Agent fragments for known link-preview crawlers. We skip lastLogin updates
     * for these so iMessage/Slack/LINE/Twitter pre-fetches don't inflate "last activity"
     * timestamps before the actual user has clicked the link.
     */
    private static final Pattern PREVIEW_BOT_UA = Pattern.compile(
            "(?i)(facebookexternalhit|Slackbot|Twitterbot|LINE|Discordbot|TelegramBot|"
          + "WhatsApp|SkypeUriPreview|iMessage|Applebot|LinkedInBot|Googlebot|bingbot|"
          + "Bytespider|YandexBot|DuckDuckBot|preview|crawler|spider|bot)");

    private final ReplyPageService replyPageService;
    private final CrmUserRepository userRepository;
    private final MessageRepository messageRepository;
    private final ReplyPageSettingService settingService;
    private final UserActivityService userActivityService;
    private final ReplyRateLimitService rateLimitService;

    public ReplyPageController(ReplyPageService replyPageService,
                               CrmUserRepository userRepository,
                               MessageRepository messageRepository,
                               ReplyPageSettingService settingService,
                               UserActivityService userActivityService,
                               ReplyRateLimitService rateLimitService) {
        this.replyPageService = replyPageService;
        this.userRepository = userRepository;
        this.messageRepository = messageRepository;
        this.settingService = settingService;
        this.userActivityService = userActivityService;
        this.rateLimitService = rateLimitService;
    }

    @GetMapping("/reply/{token}")
    public String show(@PathVariable String token, HttpServletRequest request, Model model) {
        Optional<ReplyPage> rpOpt = replyPageService.findByToken(token);
        if (!rpOpt.isPresent() || !replyPageService.isUsable(rpOpt.get())) {
            return "reply/expired";
        }
        ReplyPage rp = rpOpt.get();
        replyPageService.recordView(rp);

        // Fall-back chain for header: REPLY_PAGE.HEADER_HTML → user.memo → site-default.
        // Treat empty/whitespace strings as "not set" so the next fallback layer kicks in
        // (admin reported that user.memo = "" was blocking the site-default from being used).
        Optional<CrmUser> user = userRepository.findById(rp.getUserId());
        if (!isPreviewBot(request)) {
            user.ifPresent(userActivityService::touchLastLogin);
        }
        ReplyPageSetting settings = settingService.getOrCreate();
        String headerHtml = blankToNull(rp.getHeaderHtml());
        if (headerHtml == null && user.isPresent()) {
            headerHtml = blankToNull(user.get().getMemo());
        }
        if (headerHtml == null) headerHtml = blankToNull(settings.getDefaultHeaderHtml());

        model.addAttribute("token", token);
        model.addAttribute("headerHtml", headerHtml);
        model.addAttribute("footerHtml", settings.getFooterHtml());
        model.addAttribute("customCss", settings.getDefaultCss());
        model.addAttribute("form", new ReplyForm());
        return "reply/page";
    }

    @PostMapping("/reply/{token}/send")
    public String submit(@PathVariable String token,
                         @RequestParam(required = false) String subject,
                         @RequestParam(required = false) String body,
                         HttpServletRequest request,
                         Model model) {
        Optional<ReplyPage> rpOpt = replyPageService.findByToken(token);
        if (!rpOpt.isPresent() || !replyPageService.isUsable(rpOpt.get())) {
            return "reply/expired";
        }
        ReplyPage rp = rpOpt.get();
        // Rate-limit per (token, IP) plus a per-IP global cap — see ReplyRateLimitService.
        String clientIp = ClientIpResolver.resolve(request);
        if (!rateLimitService.tryAcquire(token, clientIp)) {
            model.addAttribute("token", token);
            model.addAttribute("form", new ReplyForm());
            model.addAttribute("errorMessage", "短時間に送信が多すぎます。少し時間を置いてから再度お試しください。");
            return "reply/page";
        }
        if (body == null || body.trim().isEmpty()) {
            ReplyForm form = new ReplyForm();
            form.setSubject(subject);
            form.setBody(body);
            model.addAttribute("token", token);
            model.addAttribute("form", form);
            model.addAttribute("errorMessage", "本文を入力してください");
            return "reply/page";
        }
        if (subject != null && subject.length() > REPLY_SUBJECT_MAX) {
            model.addAttribute("token", token);
            model.addAttribute("form", new ReplyForm());
            model.addAttribute("errorMessage", "件名が長すぎます (" + REPLY_SUBJECT_MAX + "文字以内)");
            return "reply/page";
        }
        if (body.length() > REPLY_BODY_MAX) {
            model.addAttribute("token", token);
            model.addAttribute("form", new ReplyForm());
            model.addAttribute("errorMessage", "本文が長すぎます (" + REPLY_BODY_MAX + "文字以内)");
            return "reply/page";
        }
        Optional<CrmUser> user = userRepository.findById(rp.getUserId());

        // Create a WEB_REPLY inbound message linked back to the original outbound.
        Message msg = new Message();
        msg.setUserId(rp.getUserId());
        msg.setDirection(Message.DIR_IN);
        msg.setChannel(Message.CHANNEL_WEB_REPLY);
        msg.setSubject(subject);
        msg.setBodyText(body);
        msg.setFromAddress(user.map(CrmUser::getEmail).orElse("(web reply)"));
        msg.setToAddress("(web form)");
        msg.setStatus(Message.STATUS_SENT);
        msg.setSentAt(LocalDateTime.now());
        msg.setReplyPageToken(token);
        msg.setReplyToMessageId(rp.getMessageId());
        messageRepository.save(msg);

        user.ifPresent(userActivityService::touchLastLogin);

        return "reply/sent";
    }

    private static boolean isPreviewBot(HttpServletRequest request) {
        String ua = request.getHeader("User-Agent");
        return ua != null && PREVIEW_BOT_UA.matcher(ua).find();
    }

    /** Treat empty / whitespace-only strings as null so fallback chains skip them. */
    private static String blankToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s;
    }

    public static class ReplyForm {
        private String subject;
        private String body;
        public String getSubject() { return subject; }
        public void setSubject(String subject) { this.subject = subject; }
        public String getBody() { return body; }
        public void setBody(String body) { this.body = body; }
    }
}
