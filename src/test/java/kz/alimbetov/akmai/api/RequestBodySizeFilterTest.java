package kz.alimbetov.akmai.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import kz.alimbetov.akmai.config.ApiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestBodySizeFilterTest {

    @Test
    void rejectsOversizedWriteRequestBeforeController() throws Exception {
        ApiProperties properties = new ApiProperties(
                32,
                100,
                20,
                100,
                4,
                3,
                20,
                20
        );
        RequestBodySizeFilter filter =
                new RequestBodySizeFilter(properties);
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/knowledge/text");
        request.setContent("x".repeat(33).getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainInvoked = new AtomicBoolean(false);

        filter.doFilter(
                request,
                response,
                (req, res) -> chainInvoked.set(true)
        );

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(chainInvoked).isFalse();
    }

    @Test
    void allowsBodyWithinConfiguredCeiling() throws Exception {
        ApiProperties properties = new ApiProperties(
                32,
                100,
                20,
                100,
                4,
                3,
                20,
                20
        );
        RequestBodySizeFilter filter =
                new RequestBodySizeFilter(properties);
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/knowledge/text");
        request.setContent("x".repeat(32).getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainInvoked = new AtomicBoolean(false);

        filter.doFilter(
                request,
                response,
                (req, res) -> chainInvoked.set(true)
        );

        assertThat(chainInvoked).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
