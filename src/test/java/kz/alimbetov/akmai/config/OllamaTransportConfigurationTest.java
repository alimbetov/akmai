package kz.alimbetov.akmai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OllamaTransportConfigurationTest {

    @Test
    void vectorWriteEmbeddingTransportCannotBlockPastConfiguredDeadline()
            throws Exception {
        ExecutorService serverExecutor = Executors.newCachedThreadPool();
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0
        );
        server.setExecutor(serverExecutor);
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(2_000);
                byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            VectorStorageProperties properties = vectorProperties(Duration.ofMillis(50));
            OllamaTransportConfiguration configuration =
                    new OllamaTransportConfiguration();
            var api = configuration.vectorWriteOllamaApi(baseUrl, properties);
            var model = configuration.vectorWriteEmbeddingModel(
                    api,
                    properties,
                    CanonicalEmbeddingContract.MODEL
            );

            assertTimeoutPreemptively(
                    Duration.ofSeconds(1),
                    () -> assertThatThrownBy(() -> model.embed("payload"))
                            .isInstanceOf(RuntimeException.class)
            );
        } finally {
            server.stop(0);
            serverExecutor.shutdownNow();
        }
    }

    @Test
    void embeddingRequestCarriesCanonicalModelAndDimensions() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0
        );
        server.createContext("/", exchange -> {
            capturedBody.set(new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8
            ));
            byte[] body = ("{\"model\":\"qwen3-embedding:4b\","
                    + "\"embeddings\":[[0.0]],"
                    + "\"total_duration\":1,"
                    + "\"load_duration\":1,"
                    + "\"prompt_eval_count\":1}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            VectorStorageProperties properties = vectorProperties(Duration.ofSeconds(1));
            OllamaTransportConfiguration configuration =
                    new OllamaTransportConfiguration();
            var api = configuration.vectorWriteOllamaApi(baseUrl, properties);
            var model = configuration.vectorWriteEmbeddingModel(
                    api,
                    properties,
                    CanonicalEmbeddingContract.MODEL
            );

            model.embed("payload");

            JsonNode request = new ObjectMapper().readTree(capturedBody.get());
            assertThat(request.path("model").asText())
                    .isEqualTo(CanonicalEmbeddingContract.MODEL);
            assertThat(request.path("dimensions").asInt())
                    .isEqualTo(CanonicalEmbeddingContract.DIMENSIONS);
        } finally {
            server.stop(0);
        }
    }

    private VectorStorageProperties vectorProperties(Duration embeddingTimeout) {
        return new VectorStorageProperties(
                CanonicalEmbeddingContract.DIMENSIONS,
                "NONE",
                "COSINE_DISTANCE",
                16,
                embeddingTimeout,
                Duration.ofSeconds(1),
                "test"
        );
    }
}
