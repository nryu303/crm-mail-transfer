package com.crm.dto;

import com.crm.entity.CrmUser;

import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

public class UserForm {

    @NotBlank(message = "メールアドレスを入力してください")
    @Email(message = "メールアドレスの形式が正しくありません")
    @Size(max = 255)
    private String email;

    @Size(max = 255)
    private String displayName;

    @Size(max = 60)
    private String carrierDomain;

    @Size(max = 16)
    private String status = CrmUser.STATUS_ACTIVE;

    @Size(max = 64)
    private String folder;

    /** Advertising / agency code that referred this user — matches AD_CODE.code. */
    @Size(max = 64)
    private String adCode;

    /** "M" / "F" / null. Drives the 男性/女性 split on the agency dashboard. */
    @Size(max = 8)
    private String gender;

    /** Memo is displayed on the public reply page (rendered as HTML, with placeholder substitution). */
    private String memo;

    /** Admin-only internal memo. Never shown to the end user. */
    private String internalMemo;

    // Named placeholder tags. Each maps to a TAG*_VALUE column internally with a fixed key.
    @Size(max = 500) private String amount;       // %amount%
    @Size(max = 500) private String product;      // %product%
    @Size(max = 500) private String fullAddress;  // %full_address%
    @Size(max = 500) private String dateJp;       // %date_jp% (blank = auto-resolve to today)

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getCarrierDomain() { return carrierDomain; }
    public void setCarrierDomain(String carrierDomain) { this.carrierDomain = carrierDomain; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getFolder() { return folder; }
    public void setFolder(String folder) { this.folder = folder; }

    public String getAdCode() { return adCode; }
    public void setAdCode(String adCode) { this.adCode = adCode; }

    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }

    public String getMemo() { return memo; }
    public void setMemo(String memo) { this.memo = memo; }

    public String getInternalMemo() { return internalMemo; }
    public void setInternalMemo(String internalMemo) { this.internalMemo = internalMemo; }

    public String getAmount() { return amount; }
    public void setAmount(String amount) { this.amount = amount; }
    public String getProduct() { return product; }
    public void setProduct(String product) { this.product = product; }
    public String getFullAddress() { return fullAddress; }
    public void setFullAddress(String fullAddress) { this.fullAddress = fullAddress; }
    public String getDateJp() { return dateJp; }
    public void setDateJp(String dateJp) { this.dateJp = dateJp; }

    public static UserForm from(CrmUser u) {
        UserForm f = new UserForm();
        f.email = u.getEmail();
        f.displayName = u.getDisplayName();
        f.carrierDomain = u.getCarrierDomain();
        f.status = u.getStatus();
        f.folder = u.getFolder();
        f.adCode = u.getAdCode();
        f.gender = u.getGender();
        f.memo = u.getMemo();
        f.internalMemo = u.getInternalMemo();
        f.amount       = readValueForKey(u, "amount");
        f.product      = readValueForKey(u, "product");
        f.fullAddress  = readValueForKey(u, "full_address");
        f.dateJp       = readValueForKey(u, "date_jp");
        return f;
    }

    public void applyTo(CrmUser u) {
        u.setEmail(trim(email));
        u.setDisplayName(trim(displayName));
        u.setCarrierDomain(trim(carrierDomain));
        u.setStatus(trim(status));
        u.setFolder(trim(folder));
        u.setAdCode(trim(adCode));
        u.setGender(normalizeGender(gender));
        u.setMemo(memo);
        u.setInternalMemo(internalMemo);
        // Named slots: the key is fixed; only the value is editable.
        u.setTag1Key("amount");       u.setTag1Value(trim(amount));
        u.setTag2Key("product");      u.setTag2Value(trim(product));
        u.setTag3Key("full_address"); u.setTag3Value(trim(fullAddress));
        u.setTag4Key("date_jp");      u.setTag4Value(trim(dateJp));
        u.setTag5Key(null);           u.setTag5Value(null);
    }

    private static String readValueForKey(CrmUser u, String key) {
        if (key.equals(u.getTag1Key())) return u.getTag1Value();
        if (key.equals(u.getTag2Key())) return u.getTag2Value();
        if (key.equals(u.getTag3Key())) return u.getTag3Value();
        if (key.equals(u.getTag4Key())) return u.getTag4Value();
        if (key.equals(u.getTag5Key())) return u.getTag5Value();
        return null;
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** Accept a few common spellings for gender and store the canonical "M" / "F" / null. */
    private static String normalizeGender(String s) {
        if (s == null) return null;
        String t = s.trim().toUpperCase();
        if (t.isEmpty()) return null;
        if (t.equals("M") || t.equals("MALE") || t.equals("男") || t.equals("男性")) return "M";
        if (t.equals("F") || t.equals("FEMALE") || t.equals("女") || t.equals("女性")) return "F";
        return null; // unknown → null so we don't store junk values
    }
}
