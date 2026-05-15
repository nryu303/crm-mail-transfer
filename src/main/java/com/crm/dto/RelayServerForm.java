package com.crm.dto;

import com.crm.entity.RelayServer;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

public class RelayServerForm {

    @NotBlank(message = "名前を入力してください")
    @Size(max = 255)
    private String name;

    @NotBlank(message = "IPアドレスを入力してください")
    @Size(max = 64)
    private String ipAddress;

    @NotNull(message = "ポート番号を入力してください")
    @Min(value = 1, message = "ポート番号は1以上で入力してください")
    @Max(value = 65535, message = "ポート番号は65535以下で入力してください")
    private Integer port;

    private Boolean isActive = Boolean.TRUE;

    private String memo;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public String getMemo() { return memo; }
    public void setMemo(String memo) { this.memo = memo; }

    public static RelayServerForm from(RelayServer r) {
        RelayServerForm f = new RelayServerForm();
        f.name = r.getName();
        f.ipAddress = r.getIpAddress();
        f.port = r.getPort();
        f.isActive = r.getIsActive();
        f.memo = r.getMemo();
        return f;
    }

    public void applyTo(RelayServer r) {
        r.setName(name == null ? null : name.trim());
        r.setIpAddress(ipAddress == null ? null : ipAddress.trim());
        r.setPort(port);
        r.setIsActive(isActive == null ? Boolean.TRUE : isActive);
        r.setMemo(memo);
    }
}
