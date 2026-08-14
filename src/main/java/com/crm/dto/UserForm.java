package com.crm.dto;

import com.crm.entity.CrmUser;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

public class UserForm {

    /** Permissive sanity check rather than strict @Email — docomo issues local-parts
     *  like "sky..1951...@docomo.ne.jp" (leading/trailing/consecutive dot) that fail
     *  Jakarta's @Email but are accepted by docomo's MX when the relay quotes them
     *  (see {@link com.crm.util.CsvUtil#detectInvalidLocalPart} and the relay's
     *  obob.jar quoteIfDotProblematic). Anything with one '@' and a dotted right side
     *  is good enough here; the dot-issue flag still gets recorded on insert. */
    @NotBlank(message = "メールアドレスを入力してください")
    @Pattern(regexp = "^\\S+@\\S+\\.\\S+$",
             message = "メールアドレスの形式が正しくありません")
    @Size(max = 255)
    private String email;

    /** SMS delivery target. Optional — no format validation since carrier phone formats vary. */
    @Size(max = 20)
    private String phoneNumber;

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

    /** Memo is displayed on the public reply page (rendered as HTML, with placeholder substitution).
     *  Slot 1 of six; slots 2-6 are stored in {@link #memo2}..{@link #memo6}. */
    private String memo;
    private String memo2;
    private String memo3;
    private String memo4;
    private String memo5;
    private String memo6;
    /** Which slot (1..6) the reply page should currently render. */
    private Integer activeMemoSlot;

    /** Admin-only internal memo. Never shown to the end user. */
    private String internalMemo;

    // Named placeholder tags. Each maps to a TAG*_VALUE column internally with a fixed key.
    @Size(max = 500) private String amount;       // %amount%
    @Size(max = 500) private String product;      // %product%
    @Size(max = 500) private String fullAddress;  // %full_address%
    @Size(max = 500) private String dateJp;       // %date_jp% (blank = auto-resolve to today)

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }

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

    public String getMemo2() { return memo2; }
    public void setMemo2(String memo2) { this.memo2 = memo2; }
    public String getMemo3() { return memo3; }
    public void setMemo3(String memo3) { this.memo3 = memo3; }
    public String getMemo4() { return memo4; }
    public void setMemo4(String memo4) { this.memo4 = memo4; }
    public String getMemo5() { return memo5; }
    public void setMemo5(String memo5) { this.memo5 = memo5; }
    public String getMemo6() { return memo6; }
    public void setMemo6(String memo6) { this.memo6 = memo6; }
    public Integer getActiveMemoSlot() { return activeMemoSlot == null ? 1 : activeMemoSlot; }
    public void setActiveMemoSlot(Integer s) {
        this.activeMemoSlot = (s == null || s < 1 || s > 6) ? 1 : s;
    }

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
        f.phoneNumber = u.getPhoneNumber();
        f.displayName = u.getDisplayName();
        f.carrierDomain = u.getCarrierDomain();
        f.status = u.getStatus();
        f.folder = u.getFolder();
        f.adCode = u.getAdCode();
        f.gender = u.getGender();
        f.memo = u.getMemo();
        f.memo2 = u.getMemo2();
        f.memo3 = u.getMemo3();
        f.memo4 = u.getMemo4();
        f.memo5 = u.getMemo5();
        f.memo6 = u.getMemo6();
        f.activeMemoSlot = u.getActiveMemoSlot();
        f.internalMemo = u.getInternalMemo();
        f.amount       = readValueForKey(u, "amount");
        f.product      = readValueForKey(u, "product");
        f.fullAddress  = readValueForKey(u, "full_address");
        f.dateJp       = readValueForKey(u, "date_jp");
        return f;
    }

    public void applyTo(CrmUser u) {
        u.setEmail(trim(email));
        u.setPhoneNumber(trim(phoneNumber));
        u.setDisplayName(trim(displayName));
        u.setCarrierDomain(trim(carrierDomain));
        u.setStatus(trim(status));
        u.setFolder(trim(folder));
        u.setAdCode(trim(adCode));
        u.setGender(normalizeGender(gender));
        u.setMemo(memo);
        u.setMemo2(memo2);
        u.setMemo3(memo3);
        u.setMemo4(memo4);
        u.setMemo5(memo5);
        u.setMemo6(memo6);
        u.setActiveMemoSlot(activeMemoSlot);
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
