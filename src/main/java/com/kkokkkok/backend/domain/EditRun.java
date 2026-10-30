package com.kkokkkok.backend.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "edit_runs")
public class EditRun {
    @Id private UUID editId;
    @Column(nullable = false, length = 64, updatable = false) private String fingerprint;
    @Column(nullable = false) private UUID inspectionId;
    @Column(nullable = false) private UUID sourceAssetId;
    private UUID editedAssetId;
    private UUID verificationInspectionId;
    @Column(length = 24) private String verificationStatus;
    @Column(nullable = false, length = 24) private String status;
    @Column(nullable = false) private boolean mock;
    @Column(length = 240) private String mockNotice;
    @Column(length = 48) private String providerVersion;
    @Column(nullable = false, updatable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "edit_run_cues", joinColumns = @JoinColumn(name = "edit_id"))
    @Column(name = "cue_id", nullable = false, length = 100)
    private List<String> cueIds = new ArrayList<>();

    protected EditRun() {}

    public EditRun(UUID editId, String fingerprint, UUID inspectionId, UUID sourceAssetId, List<String> cueIds) {
        this.editId = editId;
        this.fingerprint = fingerprint;
        this.inspectionId = inspectionId;
        this.sourceAssetId = sourceAssetId;
        this.cueIds = new ArrayList<>(cueIds);
        this.status = "processing";
        this.verificationStatus = "not_started";
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public void complete(UUID editedAssetId, boolean mock, String providerVersion, String mockNotice) {
        this.editedAssetId = editedAssetId;
        this.mock = mock;
        this.providerVersion = providerVersion;
        this.mockNotice = mockNotice;
        this.status = "completed";
        this.updatedAt = Instant.now();
    }

    public void fail() { this.status = "failed"; this.updatedAt = Instant.now(); }
    public void attachVerification(UUID inspectionId, String status) {
        this.verificationInspectionId = inspectionId;
        this.verificationStatus = status;
        this.updatedAt = Instant.now();
    }
    public void verificationFailed() { this.verificationStatus = "failed"; this.updatedAt = Instant.now(); }
    public UUID getEditId() { return editId; }
    public String getFingerprint() { return fingerprint; }
    public UUID getInspectionId() { return inspectionId; }
    public UUID getSourceAssetId() { return sourceAssetId; }
    public UUID getEditedAssetId() { return editedAssetId; }
    public UUID getVerificationInspectionId() { return verificationInspectionId; }
    public String getVerificationStatus() { return verificationStatus; }
    public String getStatus() { return status; }
    public boolean isMock() { return mock; }
    public String getMockNotice() { return mockNotice; }
    public String getProviderVersion() { return providerVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<String> getCueIds() { return List.copyOf(cueIds); }
}
