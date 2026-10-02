package kz.alimbetov.akmai.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
                byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
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
            VectorStorageProperties properties = new VectorStorageProperties(
                    3,
                    "NONE",
                    "COSINE_DISTANCE",
                    16,
                    Duration.ofMillis(50),
                    Duration.ofSeconds(1),
                    "test"
            );
            OllamaTransportConfiguration configuration =
                    new OllamaTransportConfiguration();
            var api = configuration.vectorWriteOllamaApi(baseUrl, properties);
            var model = configuration.vectorWriteEmbeddingModel(api, "test-model");

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
}
