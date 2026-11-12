package com.kkokkkok.backend.service;

import com.kkokkkok.backend.apiPayload.exception.ApiFailure;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.*;

@Component
public class PilotOwnership {
    public static final String ATTRIBUTE = "pilot.authenticated-installation";
    private final boolean enabled;
    private final boolean authEnabled;
    /** Owner id = pilot installation id (pilot mode) or service user id (login mode); null in shared local mode. */
    public PilotOwnership(@Value("${app.pilot.enabled:false}") boolean enabled,
                          @Value("${app.auth.enabled:false}") boolean authEnabled) {
        this.enabled = enabled || authEnabled; this.authEnabled = authEnabled;
    }
    public boolean loginMode() { return authEnabled; }
    public UUID current() {
        if (!enabled) return null;
        var attributes = RequestContextHolder.getRequestAttributes();
        Object owner = attributes == null ? null : attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (!(owner instanceof UUID id)) throw authEnabled
                ? new ApiFailure(401, "AUTH_REQUIRED", "로그인이 필요해요. 다시 로그인해 주세요.")
                : new ApiFailure(401, "PILOT_REGISTRATION_REQUIRED", "기기를 먼저 등록해 주세요.");
        return id;
    }
    public void require(UUID owner) {
        UUID current = current();
        if (current != null && !current.equals(owner))
            throw new ApiFailure(404, "RESOURCE_NOT_FOUND", "요청한 항목을 찾을 수 없어요.");
    }
}
