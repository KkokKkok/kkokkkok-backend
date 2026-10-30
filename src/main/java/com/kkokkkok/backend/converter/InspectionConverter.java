package com.kkokkkok.backend.converter;

import com.kkokkkok.backend.apiPayload.exception.ApiFailure;
import com.kkokkkok.backend.web.dto.InspectionResponse;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class InspectionConverter {
    public InspectionResponse withAssetId(InspectionResponse response,UUID assetId) {
        if (assetId == null) return response;
        Map<String,Object> details=new LinkedHashMap<>(response.details());
        details.put("assetId",assetId.toString());
        return new InspectionResponse(response.inspectionId(),response.status(),Collections.unmodifiableMap(details));
    }

    public InspectionResponse fromNode(Map<String,Object> body,UUID expected) {
        try {
            if(body==null) throw new IllegalArgumentException();
            UUID id=UUID.fromString((String)body.get("inspectionId"));
            String status=(String)body.get("status");
            if(expected!=null && !expected.equals(id)) throw new IllegalArgumentException();
            if(!Set.of("queued","processing","completed","failed").contains(status)) throw new IllegalArgumentException();
            Map<String,Object> details=new LinkedHashMap<>(body);
            details.remove("inspectionId"); details.remove("status");
            return new InspectionResponse(id,status,Collections.unmodifiableMap(details));
        } catch(RuntimeException e) { throw new ApiFailure(502,"INVALID_ANALYSIS_RESPONSE","분석 서버 응답 형식이 올바르지 않습니다."); }
    }
}
