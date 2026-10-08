package kz.alimbetov.akmai.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Flow;
import kz.alimbetov.akmai.config.ApiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(
        classes = RequestBodySizeFilterTransportIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "akmai.api.max-request-bytes=32",
                "akmai.api.max-document-chars=100",
                "akmai.api.max-question-chars=100",
                "akmai.api.max-metadata-bytes=100",
                "akmai.api.max-metadata-entries=10",
                "akmai.api.max-metadata-depth=4",
                "akmai.api.max-title-chars=100",
                "akmai.api.max-source-chars=100",
                "management.endpoint.health.validate-group-membership=false",
                "management.health.defaults.enabled=false"
        }
)
class RequestBodySizeFilterTransportIntegrationTest {

    @LocalServerPort
    int port;

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    @Test
    void knownContentLengthAboveCapReturns413() throws Exception {
        HttpResponse<String> response = send(
                HttpRequest.BodyPublishers.ofString("x".repeat(33)),
                MediaType.TEXT_PLAIN_VALUE
        );

        assertThat(response.statusCode()).isEqualTo(413);
    }

    @Test
    void unknownLengthChunkedBodyAboveCapReturns413() throws Exception {
        byte[] body = "x".repeat(33).getBytes(StandardCharsets.UTF_8);
        HttpRequest.BodyPublisher publisher = unknownLength(
                HttpRequest.BodyPublishers.ofByteArray(body)
        );

        HttpResponse<String> response = send(
                publisher,
                MediaType.APPLICATION_OCTET_STREAM_VALUE
        );

        assertThat(response.statusCode()).isEqualTo(413);
    }

    @Test
    void bodyExactlyAtCapReachesController() throws Exception {
        HttpResponse<String> response = send(
                HttpRequest.BodyPublishers.ofString("x".repeat(32)),
                MediaType.TEXT_PLAIN_VALUE
        );

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("32");
    }

    @Test
    void byteOversizedStructuredJsonIsRejectedBeforeController()
            throws Exception {
        String body = "{\"value\":\"" + "x".repeat(40) + "\"}";

        HttpResponse<String> response = send(
                unknownLength(HttpRequest.BodyPublishers.ofString(body)),
                MediaType.APPLICATION_JSON_VALUE
        );

        assertThat(response.statusCode()).isEqualTo(413);
    }

    private HttpResponse<String> send(
            HttpRequest.BodyPublisher publisher,
            String contentType
    ) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/test"))
                .header("Content-Type", contentType)
                .POST(publisher)
                .build();
        return client.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );
    }

    private HttpRequest.BodyPublisher unknownLength(
            HttpRequest.BodyPublisher delegate
    ) {
        return new HttpRequest.BodyPublisher() {
            @Override
            public long contentLength() {
                return -1L;
            }

            @Override
            public void subscribe(
                    Flow.Subscriber<? super ByteBuffer> subscriber
            ) {
                delegate.subscribe(subscriber);
            }
        };
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            UserDetailsServiceAutoConfiguration.class
    })
    @EnableConfigurationProperties(ApiProperties.class)
    static class TestApplication {

        @Bean
        RequestBodySizeFilter requestBodySizeFilter(ApiProperties properties) {
            return new RequestBodySizeFilter(properties);
        }

        @Bean
        EchoController echoController() {
            return new EchoController();
        }

        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http)
                throws Exception {
            http.csrf(csrf -> csrf.disable());
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
            return http.build();
        }
    }

    @RestController
    static class EchoController {

        @PostMapping("/api/test")
        String accept(@RequestBody byte[] body) {
            return Integer.toString(body.length);
        }
    }
}
