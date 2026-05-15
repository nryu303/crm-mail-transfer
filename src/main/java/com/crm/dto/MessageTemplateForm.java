package com.crm.dto;

import com.crm.entity.MessageTemplate;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

public class MessageTemplateForm {

    @NotBlank(message = "定型文の名前を入力してください")
    @Size(max = 255)
    private String name;

    @Size(max = 500)
    private String subject;

    private String body;

    private Integer displayOrder = 0;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public Integer getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(Integer displayOrder) { this.displayOrder = displayOrder; }

    public static MessageTemplateForm from(MessageTemplate t) {
        MessageTemplateForm f = new MessageTemplateForm();
        f.name = t.getName();
        f.subject = t.getSubject();
        f.body = t.getBody();
        f.displayOrder = t.getDisplayOrder();
        return f;
    }

    public void applyTo(MessageTemplate t) {
        t.setName(name == null ? null : name.trim());
        t.setSubject(subject);
        t.setBody(body);
        t.setDisplayOrder(displayOrder == null ? 0 : displayOrder);
    }
}
