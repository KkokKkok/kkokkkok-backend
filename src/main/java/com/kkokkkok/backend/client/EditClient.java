package com.kkokkkok.backend.client;

import com.kkokkkok.backend.domain.EditSubmission;

public interface EditClient {
    EditResult edit(EditSubmission submission);
}
