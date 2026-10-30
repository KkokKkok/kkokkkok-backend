package com.kkokkkok.backend.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AccessFilterTest {
    @Test
    void apiKeyRemainsRequiredByDefault() throws Exception {
        var request = apiRequest();
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        new AccessFilter("", true).doFilter(request, response, chain);

        assertEquals(503, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void localProfileCanCallApiWithoutEmbeddingAKeyInTheApp() throws Exception {
        var request = apiRequest();
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        new AccessFilter("", false).doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
    }

    private MockHttpServletRequest apiRequest() {
        var request = new MockHttpServletRequest("GET", "/api/assets");
        request.setServletPath("/api/assets");
        return request;
    }
}
