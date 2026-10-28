package com.kkokkkok.backend.validation;

import com.kkokkkok.backend.apiPayload.exception.ApiFailure;
import com.kkokkkok.backend.domain.InspectionSubmission;
import com.kkokkkok.backend.web.dto.InspectionRequest;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class InspectionValidator {
    public InspectionSubmission validate(InspectionRequest request) {
        var photo=request.getPhoto();
        if(photo==null || photo.isEmpty()) throw new ApiFailure(400,"INVALID_PHOTO","사진을 선택해 주세요.");
        if(photo.getSize()>10*1024*1024) throw new ApiFailure(413,"PHOTO_TOO_LARGE","사진은 10MB 이하여야 합니다.");
        String address=normalize(request.getAddress()), place=normalize(request.getPlaceId());
        if(address!=null && address.length()>500) throw new ApiFailure(400,"INVALID_ADDRESS","주소는 500자 이하여야 합니다.");
        if(address!=null && place!=null) throw new ApiFailure(400,"AMBIGUOUS_LOCATION","address 또는 placeId 중 하나를 보내 주세요.");
        if(place!=null) {
            try { if(!UUID.fromString(place).toString().equalsIgnoreCase(place)) throw new IllegalArgumentException(); }
            catch(IllegalArgumentException e) { throw new ApiFailure(400,"INVALID_PLACE_ID","placeId는 UUID여야 합니다."); }
        }
        if(request.getLocationRole()==null || !Set.of("capture","sensitive").contains(request.getLocationRole())) throw new ApiFailure(400,"INVALID_LOCATION_ROLE","locationRole은 capture 또는 sensitive여야 합니다.");
        try {
            byte[] bytes=photo.getBytes(); String mime=imageType(bytes);
            // Length-prefixed fields avoid concatenation ambiguity in duplicate request checks.
            var buffer=new ByteArrayOutputStream(); var output=new DataOutputStream(buffer);
            output.writeInt(bytes.length); output.write(bytes);
            for(String s:new String[]{address,place,request.getLocationRole()}) {
                output.writeBoolean(s!=null); if(s!=null) output.writeUTF(s);
            }
            output.writeBoolean(request.isCompareHistory());
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(buffer.toByteArray()));
            return new InspectionSubmission(bytes,mime,address,place,request.getLocationRole(),request.isCompareHistory(),hash);
        } catch(IOException e) { throw new ApiFailure(400,"INVALID_PHOTO","사진을 읽을 수 없습니다."); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private String normalize(String value) { return value==null || value.isBlank()?null:value.trim(); }
    private String imageType(byte[] b) {
        if(b.length>=3 && (b[0]&255)==255 && (b[1]&255)==216 && (b[2]&255)==255) return "image/jpeg";
        if(b.length>=8 && Arrays.equals(Arrays.copyOf(b,8),new byte[]{(byte)137,80,78,71,13,10,26,10})) return "image/png";
        if(b.length>=12 && new String(b,0,4,StandardCharsets.US_ASCII).equals("RIFF") && new String(b,8,4,StandardCharsets.US_ASCII).equals("WEBP")) return "image/webp";
        throw new ApiFailure(415,"UNSUPPORTED_PHOTO","JPEG, PNG, WebP 사진만 지원합니다.");
    }
}
