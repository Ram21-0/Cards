package com.cards.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestSizeFilterTest {

    private final RequestSizeFilter filter = new RequestSizeFilter(1000, 5000);

    private MockHttpServletResponse run(String path, int bytes) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRequestURI(path);
        request.setContent(new byte[bytes]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        if (response.getStatus() == 200) assertTrue(chain.getRequest() != null, "passed on");
        return response;
    }

    @Test
    void anApiBodyOverTheLimitIs413WithAJsonError() throws Exception {
        MockHttpServletResponse ok = run("/api/deck-requests", 1000);
        assertEquals(200, ok.getStatus());
        MockHttpServletResponse big = run("/api/deck-requests", 1001);
        assertEquals(413, big.getStatus());
        assertEquals("{\"error\":\"Request body too large\"}", big.getContentAsString());
    }

    @Test
    void adminPathsGetTheBiggerLimit() throws Exception {
        assertEquals(200, run("/api/admin/deck-requests/x", 5000).getStatus());
        assertEquals(413, run("/api/admin/deck-requests/x", 5001).getStatus());
    }

    @Test
    void nonApiPathsAreNotLimited() throws Exception {
        assertEquals(200, run("/something", 100_000).getStatus());
        assertNull(null);
    }
}
