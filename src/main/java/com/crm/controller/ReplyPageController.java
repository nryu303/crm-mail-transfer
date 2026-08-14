package com.crm.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(ReplyPageController.class);

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
    private final com.crm.service.PlaceholderService placeholderService;
    private final com.crm.service.ReplyAttachmentService attachmentService;
    private final com.crm.service.ExternalLinkDomainService externalLinkDomainService;
    private final com.crm.service.MessageBoxService messageBoxService;

    public ReplyPageController(ReplyPageService replyPageService,
                               CrmUserRepository userRepository,
                               MessageRepository messageRepository,
                               ReplyPageSettingService settingService,
                               UserActivityService userActivityService,
                               ReplyRateLimitService rateLimitService,
                               com.crm.service.PlaceholderService placeholderService,
                               com.crm.service.ReplyAttachmentService attachmentService,
                               com.crm.service.ExternalLinkDomainService externalLinkDomainService,
                               com.crm.service.MessageBoxService messageBoxService) {
        this.replyPageService = replyPageService;
        this.userRepository = userRepository;
        this.messageRepository = messageRepository;
        this.settingService = settingService;
        this.userActivityService = userActivityService;
        this.rateLimitService = rateLimitService;
        this.placeholderService = placeholderService;
        this.attachmentService = attachmentService;
        this.externalLinkDomainService = externalLinkDomainService;
        this.messageBoxService = messageBoxService;
    }

    /**
     * Render a deterrent "page does not exist" view that also discloses the visitor's
     * own IP and User-Agent. Used when the URL is technically valid but the underlying
     * user has been suspended — operators wanted this distinct from a regular 404 so
     * a curious/malicious visitor sees that we logged their fingerprint.
     */
    private String renderNotFoundWithVisitorInfo(HttpServletRequest request, Model model, String reason) {
        String ip = com.crm.util.ClientIpResolver.resolve(request);
        String ua = request.getHeader("User-Agent");
        if (ua == null) ua = "(none)";
        if (ua.length() > 500) ua = ua.substring(0, 500) + "…";
        String accept = request.getHeader("Accept-Language");
        log.info("Reply URL blocked ({}): ip={} ua={}", reason,
                com.crm.util.LogSafe.of(ip), com.crm.util.LogSafe.of(ua));
        model.addAttribute("visitorIp", ip);
        model.addAttribute("visitorUa", ua);
        model.addAttribute("visitorLang", accept == null ? "(none)" : accept);
        model.addAttribute("visitedAt", java.time.LocalDateTime.now());
        return "reply/not-found";
    }

    @GetMapping("/reply/{token}")
    public String show(@PathVariable String token,
                       @RequestParam(name = "box_page", defaultValue = "0") int boxPage,
                       HttpServletRequest request, Model model) {
        Optional<ReplyPage> rpOpt = replyPageService.findByToken(token);
        if (!rpOpt.isPresent() || !replyPageService.isUsable(rpOpt.get())) {
            return "reply/expired";
        }
        ReplyPage rp = rpOpt.get();

        // Block suspended users — show a "page does not exist" deterrent screen that
        // exposes the visitor's IP/UA, on the assumption that anyone hitting the URL
        // after the user was suspended is probing it deliberately.
        Optional<CrmUser> userPre = userRepository.findById(rp.getUserId());
        if (userPre.isPresent() && !CrmUser.STATUS_ACTIVE.equals(userPre.get().getStatus())) {
            return renderNotFoundWithVisitorInfo(request, model, "user_suspended");
        }

        replyPageService.recordView(rp);

        // Fall-back chain for header: REPLY_PAGE.HEADER_HTML → user.memo → site-default.
        // Treat empty/whitespace strings as "not set" so the next fallback layer kicks in
        // (admin reported that user.memo = "" was blocking the site-default from being used).
        Optional<CrmUser> user = userPre;
        String viewHost = resolveRequestHost(request);
        if (!isPreviewBot(request)) {
            String viewIp = ClientIpResolver.resolve(request);
            String viewUa = request.getHeader("User-Agent");
            user.ifPresent(u -> userActivityService.touchLastLogin(
                    u, com.crm.entity.UserAccessLog.SOURCE_REPLY_VIEW, viewIp, viewUa, viewHost));
        }

        // 外部リンクドメイン生成: the domain this click actually arrived on may be configured
        // to redirect to an external destination or serve custom landing-page HTML instead of
        // the normal reply form, once the access above has been logged. Falls through to the
        // reply form (existing behaviour) when the domain isn't registered or is in REPLY_FORM
        // mode — including the legacy single-base-URL path, which has no ExternalLinkDomain row.
        Optional<com.crm.entity.ExternalLinkDomain> clickDomain =
                externalLinkDomainService.findByHost(viewHost);
        if (clickDomain.isPresent()) {
            com.crm.entity.ExternalLinkDomain d = clickDomain.get();
            if (com.crm.entity.ExternalLinkDomain.MODE_REDIRECT.equals(d.getLandingMode())
                    && d.getRedirectUrl() != null && !d.getRedirectUrl().trim().isEmpty()) {
                return "redirect:" + d.getRedirectUrl().trim();
            }
            if (com.crm.entity.ExternalLinkDomain.MODE_CUSTOM_HTML.equals(d.getLandingMode())
                    && d.getLandingHtml() != null && !d.getLandingHtml().trim().isEmpty()) {
                model.addAttribute("landingHtml", d.getLandingHtml());
                return "reply/landing";
            }
        }

        ReplyPageSetting settings = settingService.getOrCreate();
        String headerHtml = blankToNull(rp.getHeaderHtml());
        if (headerHtml == null && user.isPresent()) {
            // Honour the operator's 使用中 slot selection (memo / memo2 / memo3).
            headerHtml = blankToNull(user.get().getActiveMemo());
        }
        if (headerHtml == null) headerHtml = blankToNull(settings.getDefaultHeaderHtml());

        // Apply placeholder substitution against this user's data so the public reply page
        // renders {{name}} / {{tag1}} etc. just like the admin preview does. Previously the
        // preview screen showed substituted text but the live page leaked raw tags through.
        String footerHtml = settings.getFooterHtml();
        if (user.isPresent()) {
            headerHtml = placeholderService.substitute(headerHtml, user.get());
            footerHtml = placeholderService.substitute(footerHtml, user.get());
        }

        // Pull every attachment the user has uploaded against their CURRENT active slot.
        // If the operator switches the active slot in /manager/users/{id}, the next page
        // load here picks up the new slot's attachments and hides the old slot's — exactly
        // the "添付した専用返信画面HTMLに戻った場合は再表示" behaviour the operator asked for.
        java.util.List<com.crm.entity.ReplyPageAttachment> attachments = java.util.Collections.emptyList();
        if (user.isPresent()) {
            attachments = attachmentService.listForActiveSlot(
                    user.get().getId(), user.get().getActiveMemoSlot());
        }

        model.addAttribute("token", token);
        model.addAttribute("headerHtml", headerHtml);
        model.addAttribute("footerHtml", footerHtml);
        model.addAttribute("customCss", settings.getDefaultCss());
        model.addAttribute("form", new ReplyForm());
        model.addAttribute("attachments", attachments);
        model.addAttribute("maxAttachmentSizeMB",
                com.crm.service.ReplyAttachmentService.MAX_SIZE_BYTES / 1024 / 1024);

        // メッセージボックス: this user's past OUT/SENT history (SMS/WEB/BROADCAST), newest first.
        if (user.isPresent()) {
            model.addAttribute("messageBox", messageBoxService.listFor(user.get().getId(), boxPage));
        }
        return "reply/page";
    }

    /** Public attachment upload — the user clicks the "画像添付" button on /reply/{token}.
     *  The new image is tied to whichever slot is currently active for that user. */
    @PostMapping("/reply/{token}/attachment")
    public String uploadAttachment(@PathVariable String token,
                                    @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
                                    HttpServletRequest request,
                                    org.springframework.web.servlet.mvc.support.RedirectAttributes ra) {
        Optional<ReplyPage> rpOpt = replyPageService.findByToken(token);
        if (!rpOpt.isPresent() || !replyPageService.isUsable(rpOpt.get())) {
            return "reply/expired";
        }
        ReplyPage rp = rpOpt.get();
        Optional<CrmUser> uOpt = userRepository.findById(rp.getUserId());
        if (!uOpt.isPresent() || !CrmUser.STATUS_ACTIVE.equals(uOpt.get().getStatus())) {
            return renderNotFoundWithVisitorInfo(request, null, "user_suspended_on_attach");
        }
        String clientIp = com.crm.util.ClientIpResolver.resolve(request);
        if (!rateLimitService.tryAcquire(token, clientIp)) {
            ra.addFlashAttribute("errorMessage", "短時間に送信が多すぎます。少し時間を置いてから再度お試しください。");
            return "redirect:/reply/" + token;
        }
        try {
            attachmentService.upload(uOpt.get().getId(), uOpt.get().getActiveMemoSlot(), file, clientIp);
        } catch (com.crm.service.ReplyAttachmentService.AttachmentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        } catch (java.io.IOException e) {
            log.warn("attachment upload io error: {}", e.toString());
            ra.addFlashAttribute("errorMessage", "アップロードに失敗しました。再度お試しください。");
        }
        return "redirect:/reply/" + token;
    }

    /** Public serve — anyone with a valid token for this user can view the image. */
    @GetMapping("/reply/{token}/attachment/{attId}")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource>
            serveAttachmentPublic(@PathVariable String token, @PathVariable Long attId) {
        Optional<ReplyPage> rpOpt = replyPageService.findByToken(token);
        if (!rpOpt.isPresent() || !replyPageService.isUsable(rpOpt.get())) {
            return org.springframework.http.ResponseEntity.notFound().build();
        }
        com.crm.entity.ReplyPageAttachment att =
                attachmentService.findById(attId, rpOpt.get().getUserId()).orElse(null);
        if (att == null) return org.springframework.http.ResponseEntity.notFound().build();
        java.io.File f = attachmentService.fileFor(att);
        if (f == null) return org.springframework.http.ResponseEntity.notFound().build();
        return org.springframework.http.ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.parseMediaType(att.getContentType()))
                .header("Cache-Control", "private, max-age=300")
                .body(new org.springframework.core.io.FileSystemResource(f));
    }

    @PostMapping("/reply/{token}/send")
    public String submit(@PathVariable String token,
                         @RequestParam(required = false) String subject,
                         @RequestParam(required = false) String body,
                         @RequestParam(required = false) Long replyToMessageId,
                         @RequestParam(name = "files", required = false)
                                 org.springframework.web.multipart.MultipartFile[] files,
                         HttpServletRequest request,
                         Model model) {
        Optional<ReplyPage> rpOpt = replyPageService.findByToken(token);
        if (!rpOpt.isPresent() || !replyPageService.isUsable(rpOpt.get())) {
            return "reply/expired";
        }
        ReplyPage rp = rpOpt.get();
        // Mirror the GET-side suspended-user block so a malicious POST via curl
        // can't bypass it. Same deterrent screen with IP/UA disclosure.
        Optional<CrmUser> postUserCheck = userRepository.findById(rp.getUserId());
        if (postUserCheck.isPresent() && !CrmUser.STATUS_ACTIVE.equals(postUserCheck.get().getStatus())) {
            return renderNotFoundWithVisitorInfo(request, model, "user_suspended_on_post");
        }
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

        // メッセージボックス per-item reply: replyToMessageId (when supplied) targets that
        // specific historical OUT message instead of the top-level rp.getMessageId(). Verify
        // ownership first — a tampered POST could otherwise mis-attribute a reply to another
        // user's message id and cross-contaminate their thread view.
        Long effectiveReplyTo = rp.getMessageId();
        if (replyToMessageId != null) {
            Optional<Message> refMsg = messageRepository.findById(replyToMessageId);
            if (refMsg.isPresent() && refMsg.get().getUserId().equals(rp.getUserId())) {
                effectiveReplyTo = replyToMessageId;
            }
        }
        msg.setReplyToMessageId(effectiveReplyTo);
        Message savedMsg = messageRepository.save(msg);

        // Save each uploaded image with message_id = saved.id so the thread view can show
        // the attachments next to this specific received reply. Individual file failures
        // (size / mime) are reported as a flash but don't block the reply itself.
        java.util.List<String> attachErrors = new java.util.ArrayList<>();
        if (files != null && user.isPresent()) {
            int slot = user.get().getActiveMemoSlot();
            for (org.springframework.web.multipart.MultipartFile f : files) {
                if (f == null || f.isEmpty()) continue;
                try {
                    attachmentService.upload(user.get().getId(), slot, f, clientIp, savedMsg.getId());
                } catch (com.crm.service.ReplyAttachmentService.AttachmentException e) {
                    attachErrors.add(f.getOriginalFilename() + ": " + e.getMessage());
                } catch (java.io.IOException e) {
                    log.warn("attachment upload io error on /send: {}", e.toString());
                    attachErrors.add(f.getOriginalFilename() + ": アップロードに失敗しました");
                }
            }
        }

        String submitUa = request.getHeader("User-Agent");
        String submitHost = resolveRequestHost(request);
        user.ifPresent(u -> userActivityService.touchLastLogin(
                u, com.crm.entity.UserAccessLog.SOURCE_REPLY_SUBMIT, clientIp, submitUa, submitHost));

        if (!attachErrors.isEmpty()) {
            // Reply was saved, but at least one attachment failed — surface the issue on
            // the sent page so the operator sees it instead of silent loss.
            model.addAttribute("attachmentWarnings", attachErrors);
        }
        return "reply/sent";
    }

    /** Host the request actually arrived on (e.g. "ii5gh9ge.jp") — nginx forwards the original
     *  Host header as-is (see ops/nginx-crm.conf), so this reflects whichever 外部リンクドメイン
     *  or base domain the click came in through. */
    private static String resolveRequestHost(HttpServletRequest request) {
        String host = request.getServerName();
        return (host == null || host.trim().isEmpty()) ? null : host.trim();
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
        private Long replyToMessageId;
        public String getSubject() { return subject; }
        public void setSubject(String subject) { this.subject = subject; }
        public String getBody() { return body; }
        public void setBody(String body) { this.body = body; }
        public Long getReplyToMessageId() { return replyToMessageId; }
        public void setReplyToMessageId(Long replyToMessageId) { this.replyToMessageId = replyToMessageId; }
    }
}
