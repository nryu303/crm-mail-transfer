package com.crm.entity;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.PreUpdate;
import javax.persistence.Table;
import java.time.LocalDateTime;

/** Singleton row (ID=1) holding site-wide defaults for the public reply page. */
@Entity
@Table(name = "REPLY_PAGE_SETTING")
public class ReplyPageSetting {

    public static final String CSS_PREVIEW_ON = "ON";
    public static final String CSS_PREVIEW_OFF = "OFF";
    public static final String CSS_PREVIEW_HIDDEN = "HIDDEN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "DEFAULT_HEADER_HTML", columnDefinition = "LONGTEXT")
    private String defaultHeaderHtml;

    @Column(name = "DEFAULT_CSS", columnDefinition = "LONGTEXT")
    private String defaultCss;

    @Column(name = "FOOTER_HTML", columnDefinition = "LONGTEXT")
    private String footerHtml;

    @Column(name = "REQUIRE_LOGIN")
    private Boolean requireLogin;

    @Column(name = "CSS_PREVIEW_MODE", nullable = false, length = 16)
    private String cssPreviewMode = CSS_PREVIEW_ON;

    /** Editable heading text shown above the CSS-preview iframe on the settings page itself
     *  (e.g. "CSS プレビュー (ミニ返信フォームに適用)"). Purely a label for the admin UI — has
     *  no effect on the public /reply/{token} page. */
    @Column(name = "CSS_PREVIEW_LABEL", length = 200)
    private String cssPreviewLabel;

    /** Whether the ヘッダー block actually renders on the public /reply/{token} page.
     *  Independent of cssPreviewMode, which only affects the admin settings-page preview. */
    @Column(name = "HEADER_VISIBLE", nullable = false)
    private Boolean headerVisible = Boolean.TRUE;

    /** Whether the 本文入力フォーム (subject/body + send button) actually renders on the
     *  public /reply/{token} page. When this and headerVisible are both false, only the
     *  メッセージボックス section remains on the page. */
    @Column(name = "REPLY_FORM_VISIBLE", nullable = false)
    private Boolean replyFormVisible = Boolean.TRUE;

    /** Optional one-line text (e.g. "返信はこちら") inserted on its own line directly above the
     *  expanded URL whenever %reply_url% or %external_url% is substituted. Default empty —
     *  when blank, only the line break before the URL is added. */
    @Column(name = "URL_LEAD_TEXT", length = 500)
    private String urlLeadText;

    @Column(name = "UPDATED_AT", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void stamp() { updatedAt = LocalDateTime.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getDefaultHeaderHtml() { return defaultHeaderHtml; }
    public void setDefaultHeaderHtml(String v) { this.defaultHeaderHtml = v; }
    public String getDefaultCss() { return defaultCss; }
    public void setDefaultCss(String v) { this.defaultCss = v; }
    public String getFooterHtml() { return footerHtml; }
    public void setFooterHtml(String v) { this.footerHtml = v; }
    public Boolean getRequireLogin() { return requireLogin; }
    public void setRequireLogin(Boolean v) { this.requireLogin = v; }
    public String getCssPreviewMode() { return cssPreviewMode; }
    public void setCssPreviewMode(String v) { this.cssPreviewMode = v; }
    public String getCssPreviewLabel() { return cssPreviewLabel; }
    public void setCssPreviewLabel(String v) { this.cssPreviewLabel = v; }
    public Boolean getHeaderVisible() { return headerVisible; }
    public void setHeaderVisible(Boolean v) { this.headerVisible = v; }
    public Boolean getReplyFormVisible() { return replyFormVisible; }
    public void setReplyFormVisible(Boolean v) { this.replyFormVisible = v; }
    public String getUrlLeadText() { return urlLeadText; }
    public void setUrlLeadText(String v) { this.urlLeadText = v; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime v) { this.updatedAt = v; }
}
