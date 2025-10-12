package org.traccar.model;

import org.traccar.storage.StorageName;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@StorageName("tc_payment_requests")
public class PaymentRequest extends ExtendedModel {

    private long userid;
    private String devices;
    private String packageName; // "package" Java keyword olduğu için "packageName" kullanıyoruz
    private int totalAmount;
    private String paylink;
    private String paylinkId;
    private String status;
    private boolean processed;
    private boolean active;
    private Date createdAt;
    private Date updatedAt;

    // Getter ve Setter metodları
// Backend’de sadece hesaplama için kullanılacak
    private List<Long> deviceIds;   // frontend'den gelen array
    private Map<String, Integer> paket;  // frontend'den gelen paket bilgisi

    // Getter ve Setter
    public List<Long> getDeviceIds() {
        return deviceIds;
    }

    public void setDeviceIds(List<Long> deviceIds) {
        this.deviceIds = deviceIds;
        if (deviceIds != null) {
            this.devices = "{" + deviceIds.stream()
                                          .map(String::valueOf)
                                          .collect(Collectors.joining(",")) + "}";
        } else {
            this.devices = "{}";
        }
    }

    public Map<String, Integer> getPaket() {
        return paket;
    }

    public void setPaket(Map<String,Integer> paket) {
        this.paket = paket;
        if (paket != null) {
            this.packageName = new org.json.JSONObject(paket).toString();
        } else {
            this.packageName = "{}";
        }
    }
    public long getUserid() {
        return userid;
    }

    public void setUserid(long userid) {
        this.userid = userid;
    }

    public String getDevices() {
        return devices;
    }

    public void setDevices(String devices) {
        this.devices = devices;
    }

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public int getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(int totalAmount) {
        this.totalAmount = totalAmount;
    }

    public String getPaylink() {
        return paylink;
    }

    public void setPaylink(String paylink) {
        this.paylink = paylink;
    }

    public String getPaylinkId() { 
        return paylinkId; 
    }
    public void setPaylinkId(String paylinkId) { 
        this.paylinkId = paylinkId; 
    }
    
    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public boolean getProcessed() {
        return processed;
    }
    
    public boolean isProcessed() {
        return processed;
    }

    public void setProcessed(boolean processed) {
        this.processed = processed;
    }

    
    public boolean getActive() {
        return active;
    }
    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    
    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }

    public Date getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Date updatedAt) {
        this.updatedAt = updatedAt;
    }
}
