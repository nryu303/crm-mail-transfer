package com.crm.dto;

public class CarrierPoolSearchForm {
    private String address;
    private String carrierCode;
    /** BOUND | UNBOUND | (empty = all) */
    private String boundFilter;
    private int page = 0;
    private int size = 20;

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public java.util.List<String> addressTokens() {
        if (address == null || address.trim().isEmpty()) return java.util.Collections.emptyList();
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String part : address.split("[\\r\\n,]+")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    public String getCarrierCode() { return carrierCode; }
    public void setCarrierCode(String carrierCode) { this.carrierCode = carrierCode; }

    public String getBoundFilter() { return boundFilter; }
    public void setBoundFilter(String boundFilter) { this.boundFilter = boundFilter; }

    public int getPage() { return page; }
    public void setPage(int page) { this.page = Math.max(page, 0); }

    public int getSize() { return size; }
    public void setSize(int size) { this.size = (size < 1 || size > 200) ? 20 : size; }
}
