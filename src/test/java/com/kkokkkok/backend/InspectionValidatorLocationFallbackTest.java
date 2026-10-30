package com.kkokkkok.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.kkokkkok.backend.service.ImageAssetService.StoredAsset;
import com.kkokkkok.backend.validation.InspectionValidator;
import com.kkokkkok.backend.web.dto.InspectionRequest;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InspectionValidatorLocationFallbackTest {
    private final InspectionValidator validator = new InspectionValidator();
    private final byte[] jpeg = {(byte) 0xff, (byte) 0xd8, (byte) 0xff};
    private final UUID assetId = UUID.randomUUID();

    @Test
    void manualLocationIsUsedOnlyWhenExifGpsIsMissing() {
        var withoutGps = validator.validate(new InspectionRequest(assetId, "  서울시 영등포구  "),
                new StoredAsset(assetId, "image/jpeg", jpeg, null, null, null));
        var withGps = validator.validate(new InspectionRequest(assetId, "사용자 입력 위치"),
                new StoredAsset(assetId, "image/jpeg", jpeg, 37.5, 126.9, null));

        assertEquals("서울시 영등포구", withoutGps.locationFallback());
        assertNull(withGps.locationFallback());
        assertNotEquals(withoutGps.fingerprint(), validator.validate(new InspectionRequest(assetId, "다른 위치"),
                new StoredAsset(assetId, "image/jpeg", jpeg, null, null, null)).fingerprint());
    }
}
