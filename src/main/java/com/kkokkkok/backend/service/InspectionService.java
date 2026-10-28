package com.kkokkkok.backend.service;

import com.kkokkkok.backend.client.AnalysisClient;
import com.kkokkkok.backend.converter.InspectionConverter;
import com.kkokkkok.backend.validation.InspectionValidator;
import com.kkokkkok.backend.web.dto.*;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class InspectionService {
    private final InspectionValidator validator;
    private final AnalysisClient client;
    private final InspectionConverter converter;
    private final InspectionStore store;
    public InspectionService(InspectionValidator validator,AnalysisClient client,InspectionConverter converter,InspectionStore store) {
        this.validator=validator; this.client=client; this.converter=converter; this.store=store;
    }
    public InspectionResponse create(InspectionRequest request,UUID key) {
        var input=validator.validate(request);
        try {store.reserve(key,input.fingerprint());}
        catch(DataIntegrityViolationException concurrentInsert) {store.reserve(key,input.fingerprint());}
        // Same key is also forwarded to Node, including after a timeout or server restart.
        var result=converter.fromNode(client.create(input,key),null);
        store.submitted(key,result);
        return result;
    }
    public InspectionResponse get(UUID id) {
        var result=converter.fromNode(client.get(id),id);
        store.refreshed(result);
        return result;
    }
}
