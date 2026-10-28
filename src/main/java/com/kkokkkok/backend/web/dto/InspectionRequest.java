package com.kkokkkok.backend.web.dto;

import jakarta.validation.constraints.*;
import org.springframework.web.multipart.MultipartFile;

/** Multipart request model; validation does not depend on the Node transport. */
public class InspectionRequest {
    @NotNull private MultipartFile photo;
    @Size(max=500) private String address;
    private String placeId;
    @NotNull @Pattern(regexp="capture|sensitive") private String locationRole = "capture";
    private boolean compareHistory;
    public MultipartFile getPhoto() { return photo; }
    public void setPhoto(MultipartFile photo) { this.photo=photo; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address=address; }
    public String getPlaceId() { return placeId; }
    public void setPlaceId(String placeId) { this.placeId=placeId; }
    public String getLocationRole() { return locationRole; }
    public void setLocationRole(String locationRole) { this.locationRole=locationRole; }
    public boolean isCompareHistory() { return compareHistory; }
    public void setCompareHistory(boolean compareHistory) { this.compareHistory=compareHistory; }
}
