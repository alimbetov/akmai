package kz.alimbetov.akmai.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import kz.alimbetov.akmai.config.ApiProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestBodySizeFilter extends OncePerRequestFilter {

    private final ApiProperties properties;

    public RequestBodySizeFilter(ApiProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        return !("POST".equals(method)
                || "PUT".equals(method)
                || "PATCH".equals(method));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        long maximum = properties.maxRequestBytes();
        long contentLength = request.getContentLengthLong();
        if (contentLength > maximum) {
            reject(response);
            return;
        }

        if (maximum >= Integer.MAX_VALUE) {
            throw new IllegalStateException(
                    "api.max-request-bytes must be smaller than Integer.MAX_VALUE"
            );
        }

        byte[] body = request.getInputStream().readNBytes((int) maximum + 1);
        if (body.length > maximum) {
            reject(response);
            return;
        }

        filterChain.doFilter(
                new CachedBodyRequest(request, body),
                response
        );
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.sendError(
                HttpStatus.PAYLOAD_TOO_LARGE.value(),
                "Request body exceeds configured maximum"
        );
    }

    private static final class CachedBodyRequest
            extends HttpServletRequestWrapper {

        private final byte[] body;

        private CachedBodyRequest(
                HttpServletRequest request,
                byte[] body
        ) {
            super(request);
            this.body = body.clone();
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    if (readListener == null) {
                        throw new IllegalArgumentException(
                                "readListener must not be null"
                        );
                    }
                    try {
                        if (isFinished()) {
                            readListener.onAllDataRead();
                        } else {
                            readListener.onDataAvailable();
                        }
                    } catch (IOException exception) {
                        readListener.onError(exception);
                    }
                }

                @Override
                public int read() {
                    return input.read();
                }

                @Override
                public int read(byte[] bytes, int offset, int length) {
                    return input.read(bytes, offset, length);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null
                    ? StandardCharsets.UTF_8
                    : Charset.forName(encoding);
            return new BufferedReader(
                    new InputStreamReader(getInputStream(), charset)
            );
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
