package com.crm.service;

import com.crm.dto.ReplyPageSettingForm;
import com.crm.entity.ReplyPageSetting;
import com.crm.repository.ReplyPageSettingRepository;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.regex.Pattern;

@Service
public class ReplyPageSettingService {

    /**
     * Sanitiser for {@code footerHtml}, which is injected directly into the reply page DOM
     * via {@code th:utext} (no iframe sandbox). Allows the structural / formatting tags an
     * operator actually needs for a footer (paragraphs, headings, lists, tables, links,
     * images, basic emphasis) and a small whitelist of CSS properties for inline styling.
     *
     * BLOCKS: {@code <script>}, {@code <iframe>}, {@code <object>}, {@code <embed>},
     * {@code <form>}, event-handler attributes ({@code on*}), {@code javascript:} URLs,
     * {@code data:} URLs other than images, {@code <style>} blocks (nested), and anything
     * else the policy hasn't explicitly opted into. Bypass attempts via case variation,
     * nested tags, or malformed markup are normalised by the underlying parser.
     */
    private static final Pattern SAFE_URL =
            Pattern.compile("^(?:https?://|mailto:|tel:|#).+", Pattern.CASE_INSENSITIVE);
    private static final Pattern SAFE_IMG_SRC =
            Pattern.compile("^(?:https?://|data:image/(?:png|jpe?g|gif|webp);base64,).+",
                    Pattern.CASE_INSENSITIVE);

    private static final PolicyFactory FOOTER_HTML_POLICY = new HtmlPolicyBuilder()
            .allowElements(
                    "p", "div", "span", "br", "hr",
                    "h1", "h2", "h3", "h4", "h5", "h6",
                    "ul", "ol", "li",
                    "blockquote", "pre", "code",
                    "table", "thead", "tbody", "tfoot", "tr", "td", "th", "caption",
                    "strong", "em", "b", "i", "u", "s", "small", "sub", "sup",
                    "a", "img"
            )
            .allowAttributes("href").matching(SAFE_URL).onElements("a")
            .allowAttributes("target").matching(Pattern.compile("_blank|_self")).onElements("a")
            .requireRelNofollowOnLinks()
            .allowAttributes("src").matching(SAFE_IMG_SRC).onElements("img")
            .allowAttributes("alt", "title").onElements("img", "a")
            .allowAttributes("width", "height").matching(Pattern.compile("^[0-9]{1,4}(?:px|%)?$"))
                    .onElements("img", "table", "td", "th")
            .allowAttributes("colspan", "rowspan").matching(Pattern.compile("^[0-9]{1,3}$"))
                    .onElements("td", "th")
            .allowAttributes("class").matching(Pattern.compile("^[A-Za-z0-9_ -]{1,200}$"))
                    .globally()
            .allowStyling()              // inline style="..." with safe CSS properties
            .toFactory();

    private final ReplyPageSettingRepository repository;

    public ReplyPageSettingService(ReplyPageSettingRepository repository) {
        this.repository = repository;
    }

    public ReplyPageSetting getOrCreate() {
        return repository.findById(1L).orElseGet(() -> {
            ReplyPageSetting s = new ReplyPageSetting();
            s.setId(1L);
            s.setRequireLogin(Boolean.FALSE);
            return repository.save(s);
        });
    }

    @Transactional
    public ReplyPageSetting save(ReplyPageSettingForm form) {
        ReplyPageSetting s = getOrCreate();
        // defaultHeaderHtml: rendered inside a sandboxed iframe (srcdoc + sandbox attr), so
        // we keep it raw — full-page HTML with its own <style>/<script>/web-fonts works.
        // Same policy as per-user CrmUser.memo.

        // footerHtml: injected directly into the reply page DOM via th:utext. This is the
        // big stored-XSS surface. Run the full OWASP allow-list policy — anything not on
        // the policy is dropped, including case-variations like <SCRIPT>.
        if (form.getFooterHtml() != null) {
            form.setFooterHtml(FOOTER_HTML_POLICY.sanitize(form.getFooterHtml()));
        }

        // defaultCss: injected into a <style> tag via th:utext (CSS context, not HTML).
        // The OWASP HTML sanitizer doesn't apply here. Risk vectors in CSS:
        //   1) </style> closing-tag escape into HTML context  → strip all '<' / '>'
        //   2) CSS expression() / javascript: in url() / @import to evil URL
        //      → strip those constructs (case-insensitive)
        // CSS doesn't legitimately need '<' or '>' in syntax, so blanket-stripping is safe
        // for a stylesheet body.
        if (form.getDefaultCss() != null) {
            String css = form.getDefaultCss()
                    .replaceAll("[<>]", "")
                    .replaceAll("(?i)expression\\s*\\(", "blocked(")
                    .replaceAll("(?i)javascript\\s*:", "blocked:")
                    .replaceAll("(?i)@import[^;]*;", "/*@import removed*/");
            form.setDefaultCss(css);
        }

        form.applyTo(s);
        return repository.save(s);
    }
}
