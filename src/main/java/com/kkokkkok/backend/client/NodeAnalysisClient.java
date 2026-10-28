package com.kkokkkok.backend.client;

import com.kkokkkok.backend.apiPayload.exception.ApiFailure;
import com.kkokkkok.backend.domain.InspectionSubmission;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.*;

@Component
public class NodeAnalysisClient implements AnalysisClient {
    private static final ParameterizedTypeReference<Map<String,Object>> BODY=new ParameterizedTypeReference<>() {};
    private final RestClient client;
    private final boolean configured;
    public NodeAnalysisClient(RestClient analysisRestClient,@Value("${analysis.base-url:}") String url) {
        client=analysisRestClient; configured=!url.isBlank();
    }
    public Map<String,Object> create(InspectionSubmission input,UUID key) {
        var parts=new LinkedMultiValueMap<String,Object>();
        var headers=new HttpHeaders(); headers.setContentType(MediaType.parseMediaType(input.contentType()));
        parts.add("photo",new HttpEntity<>(new ByteArrayResource(input.photo()) {
            @Override public String getFilename() {return "photo."+(input.contentType().equals("image/jpeg")?"jpg":input.contentType().substring(6));}
        },headers));
        if(input.address()!=null) parts.add("address",input.address());
        if(input.placeId()!=null) parts.add("placeId",input.placeId());
        parts.add("locationRole",input.locationRole()); parts.add("compareHistory",String.valueOf(input.compareHistory()));
        return call(()->client.post().uri("/api/inspections").header("Idempotency-Key",key.toString())
            .contentType(MediaType.MULTIPART_FORM_DATA).body(parts).retrieve().body(BODY));
    }
    public Map<String,Object> get(UUID id) {return call(()->client.get().uri("/api/inspections/{id}",id).retrieve().body(BODY));}
    private Map<String,Object> call(Supplier<Map<String,Object>> action) {
        if(!configured) throw new ApiFailure(503,"ANALYSIS_NOT_CONFIGURED","분석 서버 주소가 설정되지 않았습니다.");
        try {return action.get();}
        catch(RestClientResponseException e) {
            int status=e.getStatusCode().value();
            if(Set.of(400,404,409,413,415,429).contains(status)) throw new ApiFailure(status,"ANALYSIS_REQUEST_REJECTED","분석 요청을 처리할 수 없습니다. 입력 또는 작업 상태를 확인해 주세요.");
            throw new ApiFailure(502,"ANALYSIS_UNAVAILABLE","분석 서버에 오류가 발생했습니다.");
        } catch(ResourceAccessException e) {throw new ApiFailure(504,"ANALYSIS_CONNECTION_FAILED","분석 서버 연결이 실패하거나 시간이 초과되었습니다. 같은 Idempotency-Key로 다시 요청해 주세요.");}
        catch(RestClientException e) {throw new ApiFailure(502,"INVALID_ANALYSIS_RESPONSE","분석 서버 응답 형식이 올바르지 않습니다.");}
    }
}
