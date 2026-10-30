package com.kkokkkok.backend.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Stores job metadata only. Original photos, addresses and Node payloads are not duplicated here. */
@Entity
@Table(name="inspections")
public class Inspection {
    @Id private UUID requestId;
    @Column(nullable=false,length=64,updatable=false) private String fingerprint;
    @Column(unique=true) private UUID inspectionId;
    @Column(name="asset_id") private UUID assetId;
    @Column(nullable=false,length=32) private String status;
    @Column(nullable=false,updatable=false) private Instant createdAt;
    @Column(nullable=false) private Instant updatedAt;
    @Version private Long version;
    protected Inspection() {}
    public Inspection(UUID requestId,String fingerprint,UUID assetId) {
        this.requestId=requestId; this.fingerprint=fingerprint; this.assetId=assetId; status="submitting";
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
    public String getStatus() {return status;}
    public Instant getCreatedAt() {return createdAt;}
    public Instant getUpdatedAt() {return updatedAt;}
}
