package com.kkokkkok.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One independently submitted photo within a batch. */
@Entity
@Table(name = "inspection_batch_items")
public class InspectionBatchItem {
    @Id private UUID itemId;
    @Column(nullable = false, updatable = false) private UUID batchId;
    @Column(nullable = false, updatable = false) private int position;
    @Column(nullable = false, updatable = false) private UUID assetId;
    private UUID inspectionId;
    @Column(nullable = false, length = 32) private String status;
    @Column(nullable = false) private boolean mock;
    @Column(length = 64) private String errorCode;
    @Column(length = 240) private String errorMessage;
    @Column(nullable = false, updatable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;
    protected InspectionBatchItem() {}
    public InspectionBatchItem(UUID itemId, UUID batchId, int position, UUID assetId) {
        this.itemId = itemId;
        this.batchId = batchId;
        this.position = position;
        this.assetId = assetId;
        this.status = "submitting";
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }
    public void submitted(UUID inspectionId, String status, boolean mock) {
        this.inspectionId = inspectionId;
        this.status = status;
        this.mock = mock;
        this.errorCode = null;
        this.errorMessage = null;
        this.updatedAt = Instant.now();
    }
    public void failed(String code, String message) {
        this.status = "failed";
        this.errorCode = code;
        this.errorMessage = message;
        this.updatedAt = Instant.now();
    }
    public void refreshed(String status, boolean mock) {
        if (!this.status.equals("completed") && !this.status.equals("failed")) {
            this.status = status;
            this.mock = mock;
            this.updatedAt = Instant.now();
        }
    }
    public UUID getItemId() { return itemId; }
    public UUID getBatchId() { return batchId; }
    public int getPosition() { return position; }
    public UUID getAssetId() { return assetId; }
    public UUID getInspectionId() { return inspectionId; }
    public String getStatus() { return status; }
    public boolean isMock() { return mock; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
