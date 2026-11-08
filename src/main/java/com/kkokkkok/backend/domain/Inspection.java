package com.kkokkkok.backend.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Stores job metadata and an optional private capture-location fallback. */
@Entity
@Table(name="inspections")
public class Inspection {
    @Id private UUID requestId;
    @Column(nullable=false,length=64,updatable=false) private String fingerprint;
    @Column(unique=true) private UUID inspectionId;
    @Column(name="asset_id") private UUID assetId;
    @Column(name="location_fallback", length=120) private String locationFallback;
    @Column(nullable=false,length=32) private String status;
    @Column(nullable=false,updatable=false) private Instant createdAt;
    @Column(nullable=false) private Instant updatedAt;
    @Version private Long version;
    protected Inspection() {}
    public Inspection(UUID requestId,String fingerprint,UUID assetId,String locationFallback) {
        this.requestId=requestId; this.fingerprint=fingerprint; this.assetId=assetId;
        this.locationFallback=locationFallback; status="submitting";
        createdAt=Instant.now(); updatedAt=createdAt;
    }
    public void update(UUID id,String nextStatus) {
        if(inspectionId!=null && !inspectionId.equals(id)) throw new IllegalStateException("Node idempotency contract violated");
        inspectionId=id;
        // Concurrent polls must not regress a terminal state or processing back to queued.
        if(!status.equals("completed") && !status.equals("failed") && !(status.equals("processing") && nextStatus.equals("queued"))) status=nextStatus;
        updatedAt=Instant.now();
    }
    public UUID getRequestId() {return requestId;}
    public UUID getInspectionId() {return inspectionId;}
    public String getFingerprint() {return fingerprint;}
    public UUID getAssetId() {return assetId;}
    public String getLocationFallback() {return locationFallback;}
    public String getStatus() {return status;}
    public Instant getCreatedAt() {return createdAt;}
    public Instant getUpdatedAt() {return updatedAt;}
}
