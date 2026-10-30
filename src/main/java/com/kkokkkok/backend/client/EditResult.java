package com.kkokkkok.backend.client;

public record EditResult(byte[] image, String contentType, boolean mock,
                         String providerVersion, String mockNotice) {}
