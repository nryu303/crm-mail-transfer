package com.crm.dto;

import com.crm.entity.ReplyPageSetting;

public class ReplyPageSettingForm {
    private String defaultHeaderHtml;
    private String defaultCss;
    private String footerHtml;
    private Boolean requireLogin = Boolean.FALSE;

    public String getDefaultHeaderHtml() { return defaultHeaderHtml; }
    public void setDefaultHeaderHtml(String defaultHeaderHtml) { this.defaultHeaderHtml = defaultHeaderHtml; }
    public String getDefaultCss() { return defaultCss; }
    public void setDefaultCss(String defaultCss) { this.defaultCss = defaultCss; }
    public String getFooterHtml() { return footerHtml; }
    public void setFooterHtml(String footerHtml) { this.footerHtml = footerHtml; }
    public Boolean getRequireLogin() { return requireLogin; }
    public void setRequireLogin(Boolean requireLogin) { this.requireLogin = requireLogin; }

    public static ReplyPageSettingForm from(ReplyPageSetting s) {
        ReplyPageSettingForm f = new ReplyPageSettingForm();
        f.defaultHeaderHtml = s.getDefaultHeaderHtml();
        f.defaultCss = s.getDefaultCss();
        f.footerHtml = s.getFooterHtml();
        f.requireLogin = Boolean.TRUE.equals(s.getRequireLogin());
        return f;
    }

    public void applyTo(ReplyPageSetting s) {
        s.setDefaultHeaderHtml(defaultHeaderHtml);
        s.setDefaultCss(defaultCss);
        s.setFooterHtml(footerHtml);
        s.setRequireLogin(requireLogin == null ? Boolean.FALSE : requireLogin);
    }
}
