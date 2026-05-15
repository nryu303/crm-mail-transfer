package com.crm.dto;

import javax.validation.constraints.Email;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

public class CarrierPoolForm {

    @NotBlank(message = "キャリアアドレスを入力してください")
    @Email(message = "アドレスの形式が正しくありません")
    @Size(max = 255)
    private String address;

    @NotBlank(message = "キャリアコードを選択してください")
    @Size(max = 10)
    private String carrierCode;

    @NotBlank(message = "キャリアドメインを入力してください")
    @Size(max = 60)
    private String carrierDomain;

    @NotBlank(message = "SMTPホストを入力してください")
    @Size(max = 255)
    private String smtpHost;

    @NotNull
    @Min(1) @Max(65535)
    private Integer smtpPort = 587;

    @NotBlank(message = "SMTPユーザー名を入力してください")
    @Size(max = 255)
    private String smtpUsername;

    // Required on create; on update the form leaves it blank to keep the stored password.
    // The controller / service enforces non-blank when creating.
    private String smtpPassword;

    /** Edit-form toggle for the row's IS_ACTIVE flag. Null on create (defaults to true). */
    private Boolean isActive;

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public String getCarrierCode() { return carrierCode; }
    public void setCarrierCode(String carrierCode) { this.carrierCode = carrierCode; }

    public String getCarrierDomain() { return carrierDomain; }
    public void setCarrierDomain(String carrierDomain) { this.carrierDomain = carrierDomain; }

    public String getSmtpHost() { return smtpHost; }
    public void setSmtpHost(String smtpHost) { this.smtpHost = smtpHost; }

    public Integer getSmtpPort() { return smtpPort; }
    public void setSmtpPort(Integer smtpPort) { this.smtpPort = smtpPort; }

    public String getSmtpUsername() { return smtpUsername; }
    public void setSmtpUsername(String smtpUsername) { this.smtpUsername = smtpUsername; }

    public String getSmtpPassword() { return smtpPassword; }
    public void setSmtpPassword(String smtpPassword) { this.smtpPassword = smtpPassword; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
}
