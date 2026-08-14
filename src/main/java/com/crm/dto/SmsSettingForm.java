package com.crm.dto;

public class SmsSettingForm {
    private Boolean enabled;
    private String username;
    private String password;
    private String senderName;
    private Boolean localDelivery;
    private String relayIp;
    private String senderNameMode;
    private String senderNameFixedList;
    private Integer senderNameRandomLength;
    private String relayToken;
    private Integer ratePerMinute;

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getSenderName() { return senderName; }
    public void setSenderName(String senderName) { this.senderName = senderName; }
    public Boolean getLocalDelivery() { return localDelivery; }
    public void setLocalDelivery(Boolean localDelivery) { this.localDelivery = localDelivery; }
    public String getRelayIp() { return relayIp; }
    public void setRelayIp(String relayIp) { this.relayIp = relayIp; }
    public String getSenderNameMode() { return senderNameMode; }
    public void setSenderNameMode(String senderNameMode) { this.senderNameMode = senderNameMode; }
    public String getSenderNameFixedList() { return senderNameFixedList; }
    public void setSenderNameFixedList(String senderNameFixedList) { this.senderNameFixedList = senderNameFixedList; }
    public Integer getSenderNameRandomLength() { return senderNameRandomLength; }
    public void setSenderNameRandomLength(Integer senderNameRandomLength) { this.senderNameRandomLength = senderNameRandomLength; }
    public String getRelayToken() { return relayToken; }
    public void setRelayToken(String relayToken) { this.relayToken = relayToken; }
    public Integer getRatePerMinute() { return ratePerMinute; }
    public void setRatePerMinute(Integer ratePerMinute) { this.ratePerMinute = ratePerMinute; }
}
