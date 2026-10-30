package com.kkokkkok.backend.client;

import com.kkokkkok.backend.domain.EditSubmission;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Development-only no-op. It copies bytes and must never be presented as an actual edit. */
@Component
@ConditionalOnProperty(prefix = "editing", name = "provider", havingValue = "stub", matchIfMissing = true)
public class StubEditClient implements EditClient {
    public static final String NOTICE = "모의 편집입니다. 이미지 내용은 수정하지 않았습니다.";

    @Override
    public EditResult edit(EditSubmission submission) {
        return new EditResult(submission.image().clone(), submission.contentType(), true, "stub-copy-v1", NOTICE);
    }
}
