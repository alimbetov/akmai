package kz.alimbetov.akmai.health;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("ollama")
public class OllamaHealthIndicator implements HealthIndicator {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final HttpClient httpClient;
    private final URI tagsUri;

    public OllamaHealthIndicator(
            @Value("${spring.ai.ollama.base-url:http://localhost:11434}")
            String baseUrl
    ) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
        this.tagsUri = URI.create(
                baseUrl.replaceAll("/+$", "") + "/api/tags"
        );
    }

    @Override
    public Health health() {
        try {
            HttpRequest request = HttpRequest.newBuilder(tagsUri)
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<Void> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.discarding()
            );
            if (response.statusCode() >= 200
                    && response.statusCode() < 300) {
                return Health.up().build();
            }
            return Health.down()
                    .withDetail("status", response.statusCode())
                    .build();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Health.down()
                    .withDetail("reason", "interrupted")
                    .build();
        } catch (Exception exception) {
            return Health.down()
                    .withDetail("reason", exception.getClass().getSimpleName())
                    .build();
        }
    }
}
