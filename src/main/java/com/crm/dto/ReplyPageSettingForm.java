package com.crm.dto;

import com.crm.entity.ReplyPageSetting;

public class ReplyPageSettingForm {
    private String defaultHeaderHtml;
    private String defaultCss;
    private String footerHtml;
    private Boolean requireLogin = Boolean.FALSE;
    private String cssPreviewMode = ReplyPageSetting.CSS_PREVIEW_ON;
    private String cssPreviewLabel;
    private Boolean headerVisible = Boolean.TRUE;
    private Boolean replyFormVisible = Boolean.TRUE;
    private String urlLeadText;

    public String getDefaultHeaderHtml() { return defaultHeaderHtml; }
    public void setDefaultHeaderHtml(String defaultHeaderHtml) { this.defaultHeaderHtml = defaultHeaderHtml; }
    public String getDefaultCss() { return defaultCss; }
    public void setDefaultCss(String defaultCss) { this.defaultCss = defaultCss; }
    public String getFooterHtml() { return footerHtml; }
    public void setFooterHtml(String footerHtml) { this.footerHtml = footerHtml; }
    public Boolean getRequireLogin() { return requireLogin; }
    public void setRequireLogin(Boolean requireLogin) { this.requireLogin = requireLogin; }
    public String getCssPreviewMode() { return cssPreviewMode; }
    public void setCssPreviewMode(String cssPreviewMode) { this.cssPreviewMode = cssPreviewMode; }
    public String getCssPreviewLabel() { return cssPreviewLabel; }
    public void setCssPreviewLabel(String cssPreviewLabel) { this.cssPreviewLabel = cssPreviewLabel; }
    public Boolean getHeaderVisible() { return headerVisible; }
    public void setHeaderVisible(Boolean headerVisible) { this.headerVisible = headerVisible; }
    public Boolean getReplyFormVisible() { return replyFormVisible; }
    public void setReplyFormVisible(Boolean replyFormVisible) { this.replyFormVisible = replyFormVisible; }
    public String getUrlLeadText() { return urlLeadText; }
    public void setUrlLeadText(String urlLeadText) { this.urlLeadText = urlLeadText; }

    /** Shown when the operator hasn't customised the preview-pane heading yet. */
    public static final String DEFAULT_CSS_PREVIEW_LABEL = "CSS プレビュー (ミニ返信フォームに適用)";

    public static ReplyPageSettingForm from(ReplyPageSetting s) {
        ReplyPageSettingForm f = new ReplyPageSettingForm();
        f.defaultHeaderHtml = s.getDefaultHeaderHtml();
        f.defaultCss = s.getDefaultCss();
        f.footerHtml = s.getFooterHtml();
        f.requireLogin = Boolean.TRUE.equals(s.getRequireLogin());
        f.cssPreviewMode = s.getCssPreviewMode() == null ? ReplyPageSetting.CSS_PREVIEW_ON : s.getCssPreviewMode();
        f.cssPreviewLabel = (s.getCssPreviewLabel() == null || s.getCssPreviewLabel().isEmpty())
                ? DEFAULT_CSS_PREVIEW_LABEL : s.getCssPreviewLabel();
        f.headerVisible = s.getHeaderVisible() == null ? Boolean.TRUE : s.getHeaderVisible();
        f.replyFormVisible = s.getReplyFormVisible() == null ? Boolean.TRUE : s.getReplyFormVisible();
        f.urlLeadText = s.getUrlLeadText();
        return f;
    }

    public void applyTo(ReplyPageSetting s) {
        s.setDefaultHeaderHtml(defaultHeaderHtml);
        s.setDefaultCss(defaultCss);
        s.setFooterHtml(footerHtml);
        s.setRequireLogin(requireLogin == null ? Boolean.FALSE : requireLogin);
        s.setCssPreviewMode(cssPreviewMode == null ? ReplyPageSetting.CSS_PREVIEW_ON : cssPreviewMode);
        s.setCssPreviewLabel(cssPreviewLabel == null || cssPreviewLabel.trim().isEmpty()
                ? DEFAULT_CSS_PREVIEW_LABEL : cssPreviewLabel.trim());
        s.setHeaderVisible(headerVisible == null ? Boolean.FALSE : headerVisible);
        s.setReplyFormVisible(replyFormVisible == null ? Boolean.FALSE : replyFormVisible);
        s.setUrlLeadText(urlLeadText == null ? null : urlLeadText.trim());
    }
}
