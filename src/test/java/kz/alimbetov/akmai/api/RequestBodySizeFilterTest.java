package kz.alimbetov.akmai.api;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import kz.alimbetov.akmai.config.ApiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestBodySizeFilterTest {

    @Test
    void rejectsOversizedWriteRequestBeforeController() throws Exception {
        RequestBodySizeFilter filter = filter(32);
        MockHttpServletRequest request = request("x".repeat(33));
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
    void rejectsOversizedBodyWhenContentLengthIsUnknown() throws Exception {
        RequestBodySizeFilter filter = filter(32);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/knowledge/text"
        ) {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
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
    void allowsBodyAtConfiguredCeilingAndPreservesInputStream() throws Exception {
        RequestBodySizeFilter filter = filter(32);
        MockHttpServletRequest request = request("x".repeat(32));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> consumed = new AtomicReference<>();

        filter.doFilter(
                request,
                response,
                (req, res) -> consumed.set(new String(
                        ((HttpServletRequest) req).getInputStream().readAllBytes(),
                        StandardCharsets.UTF_8
                ))
        );

        assertThat(consumed.get()).isEqualTo("x".repeat(32));
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void preservesBodyThroughReader() throws Exception {
        RequestBodySizeFilter filter = filter(32);
        MockHttpServletRequest request = request("hello");
        request.setCharacterEncoding(StandardCharsets.UTF_8.name());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> consumed = new AtomicReference<>();

        filter.doFilter(
                request,
                response,
                (req, res) -> consumed.set(
                        ((HttpServletRequest) req).getReader().readLine()
                )
        );

        assertThat(consumed.get()).isEqualTo("hello");
        assertThat(response.getStatus()).isEqualTo(200);
    }

    private RequestBodySizeFilter filter(int maxRequestBytes) {
        return new RequestBodySizeFilter(new ApiProperties(
                maxRequestBytes,
                100,
                20,
                100,
                4,
                3,
                20,
                20
        ));
    }

    private MockHttpServletRequest request(String body) {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/knowledge/text");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
