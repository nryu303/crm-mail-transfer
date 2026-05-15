package com.crm.service;

import com.crm.dto.ReplyPageSettingForm;
import com.crm.entity.ReplyPageSetting;
import com.crm.repository.ReplyPageSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the C1 sanitiser policy applied in {@link ReplyPageSettingService#save}.
 *
 * Two surfaces, two policies:
 *   - footerHtml: OWASP allow-list (script-class tags / event handlers dropped, structural
 *     tags + safe styling kept)
 *   - defaultCss: raw-CSS context, '<' / '>' stripped + expression()/javascript:/@import neutered
 *
 * Cases mirror the payload list agreed with ops on 2026-05-15: 6 attacks must be neutralised,
 * 2 benign inputs must survive unchanged in spirit.
 */
class ReplyPageSettingServiceXssTest {

    private ReplyPageSettingService service;
    private ReplyPageSettingRepository repo;

    @BeforeEach
    void setUp() {
        repo = mock(ReplyPageSettingRepository.class);
        ReplyPageSetting existing = new ReplyPageSetting();
        existing.setId(1L);
        when(repo.findById(1L)).thenReturn(Optional.of(existing));
        when(repo.save(any(ReplyPageSetting.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new ReplyPageSettingService(repo);
    }

    private String saveAndGetFooter(String raw) {
        ReplyPageSettingForm f = new ReplyPageSettingForm();
        f.setFooterHtml(raw);
        ReplyPageSetting saved = service.save(f);
        return saved.getFooterHtml() == null ? "" : saved.getFooterHtml();
    }

    private String saveAndGetCss(String raw) {
        ReplyPageSettingForm f = new ReplyPageSettingForm();
        f.setDefaultCss(raw);
        ReplyPageSetting saved = service.save(f);
        return saved.getDefaultCss() == null ? "" : saved.getDefaultCss();
    }

    // ---- ATTACK PAYLOADS — must NOT survive ----

    @Test @DisplayName("ATK1: <script> dropped")
    void attack_scriptTag() {
        String out = saveAndGetFooter("hello<script>alert(1)</script>world");
        assertThat(out).doesNotContainIgnoringCase("<script");
        assertThat(out).doesNotContain("alert(1)");
    }

    @Test @DisplayName("ATK2: <img onerror=...> event handler dropped")
    void attack_imgOnerror() {
        String out = saveAndGetFooter("<img src=x onerror=alert(1)>");
        assertThat(out).doesNotContainIgnoringCase("onerror");
        assertThat(out).doesNotContain("alert(1)");
    }

    @Test @DisplayName("ATK3: <iframe srcdoc> dropped (not in allow-list)")
    void attack_iframeSrcdoc() {
        String out = saveAndGetFooter("<iframe srcdoc=\"<script>alert(1)</script>\"></iframe>");
        assertThat(out).doesNotContainIgnoringCase("<iframe");
        assertThat(out).doesNotContain("alert(1)");
    }

    @Test @DisplayName("ATK4: javascript: URL dropped")
    void attack_javascriptUrl() {
        String out = saveAndGetFooter("<a href=\"javascript:alert(1)\">click</a>");
        assertThat(out).doesNotContainIgnoringCase("javascript:");
        assertThat(out).doesNotContain("alert(1)");
    }

    @Test @DisplayName("ATK5: <style>@import url('evil') dropped")
    void attack_styleImport() {
        String out = saveAndGetFooter("<style>@import url('http://evil/x.css');</style>");
        assertThat(out).doesNotContainIgnoringCase("<style");
        assertThat(out).doesNotContain("@import");
    }

    @Test @DisplayName("ATK6: case-variation bypass <SCRIPT> dropped")
    void attack_caseVariation() {
        String out = saveAndGetFooter("<SCRIPT>alert(1)</SCRIPT>");
        assertThat(out).doesNotContainIgnoringCase("<script");
        assertThat(out).doesNotContain("alert(1)");
    }

    // ---- BENIGN PAYLOADS — must survive ----

    @Test @DisplayName("BEN1: <p style=\"color:red\"> survives with inline style")
    void benign_paragraphWithStyle() {
        String out = saveAndGetFooter("<p style=\"color:red\">テキスト</p>");
        assertThat(out).contains("<p");
        assertThat(out).contains("テキスト");
        // style attribute should survive in some form
        assertThat(out.toLowerCase()).contains("color");
    }

    @Test @DisplayName("BEN2: basic table survives")
    void benign_table() {
        String html = "<table><thead><tr><th>名前</th><th>金額</th></tr></thead>"
                    + "<tbody><tr><td>山田</td><td>1,200円</td></tr></tbody></table>";
        String out = saveAndGetFooter(html);
        assertThat(out).contains("<table");
        assertThat(out).contains("<thead");
        assertThat(out).contains("<tbody");
        assertThat(out).contains("名前");
        assertThat(out).contains("1,200円");
    }

    // ---- CSS-context sanitisation ----

    @Test @DisplayName("CSS-ATK1: </style> closing tag stripped — payload cannot escape <style> context")
    void cssAttack_styleClose() {
        // Once '<' and '>' are stripped the script tags are inert syntactic garbage
        // INSIDE the <style> block — CSS doesn't execute JS on its own, so leaving
        // the literal text "alert(1)" in the stylesheet body is XSS-safe. The
        // security guarantee is: no character can escape the <style> tag.
        String out = saveAndGetCss(".x{color:red}</style><script>alert(1)</script>");
        assertThat(out).doesNotContain("<");
        assertThat(out).doesNotContain(">");
    }

    @Test @DisplayName("CSS-ATK2: expression() neutered")
    void cssAttack_expression() {
        String out = saveAndGetCss(".x{width:expression(alert(1))}");
        assertThat(out).doesNotContainIgnoringCase("expression(");
        // Marker we leave behind so an operator can see what got stripped:
        assertThat(out).contains("blocked(");
    }

    @Test @DisplayName("CSS-ATK3: @import directive neutered, normal CSS survives")
    void cssAttack_atImport() {
        // We replace @import...; with a marker comment instead of deleting it, so an
        // operator can see what got blocked. The security guarantee is: no functional
        // @import remains (the url() reference is inside a comment now).
        String out = saveAndGetCss(".header{font-size:14px}\n@import url('http://evil/x.css');\n.footer{color:#444}");
        assertThat(out).doesNotContain("url('http://evil/");  // functional reference gone
        assertThat(out).contains("/*@import removed*/");     // marker present
        assertThat(out).contains("font-size:14px");
        assertThat(out).contains("color:#444");
    }
}
