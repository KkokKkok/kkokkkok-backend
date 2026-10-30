package com.kkokkkok.backend.validation;

import com.kkokkkok.backend.apiPayload.exception.ApiFailure;
import com.kkokkkok.backend.domain.InspectionSubmission;
import com.kkokkkok.backend.service.ImageAssetService.StoredAsset;
import com.kkokkkok.backend.web.dto.InspectionRequest;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class InspectionValidator {
    public InspectionSubmission validate(InspectionRequest request, StoredAsset asset) {
        if (request == null || request.assetId() == null || !request.assetId().equals(asset.assetId()))
            throw new ApiFailure(400, "INVALID_ASSET_ID", "분석할 사진 ID를 확인해 주세요.");
        byte[] bytes = asset.bytes();
        if (bytes == null || bytes.length == 0) throw new ApiFailure(400,"INVALID_PHOTO","저장된 사진을 읽을 수 없습니다.");
        if (bytes.length>10*1024*1024) throw new ApiFailure(413,"PHOTO_TOO_LARGE","사진은 10MB 이하여야 합니다.");
        String detectedType=imageType(bytes);
        if (!detectedType.equals(asset.contentType())) throw new ApiFailure(415,"UNSUPPORTED_PHOTO","저장된 사진 형식이 올바르지 않습니다.");
        try {
            var buffer=new ByteArrayOutputStream(); var output=new DataOutputStream(buffer);
            output.writeLong(asset.assetId().getMostSignificantBits());
            output.writeLong(asset.assetId().getLeastSignificantBits());
            output.writeInt(bytes.length); output.write(bytes);
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(buffer.toByteArray()));
            return new InspectionSubmission(asset.assetId(),bytes,detectedType,asset.latitude(),asset.longitude(),asset.capturedAt(),hash);
        } catch(IOException e) { throw new ApiFailure(400,"INVALID_PHOTO","사진을 읽을 수 없습니다."); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private String imageType(byte[] b) {
        if(b.length>=3 && (b[0]&255)==255 && (b[1]&255)==216 && (b[2]&255)==255) return "image/jpeg";
        if(b.length>=8 && Arrays.equals(Arrays.copyOf(b,8),new byte[]{(byte)137,80,78,71,13,10,26,10})) return "image/png";
        if(b.length>=12 && new String(b,0,4,StandardCharsets.US_ASCII).equals("RIFF") && new String(b,8,4,StandardCharsets.US_ASCII).equals("WEBP")) return "image/webp";
        throw new ApiFailure(415,"UNSUPPORTED_PHOTO","JPEG, PNG, WebP 사진만 지원합니다.");
    }
}
