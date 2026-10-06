package kz.alimbetov.akmai.rag.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import kz.alimbetov.akmai.rag.query.AdvancedRetrievalProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "akmai.retrieval.advanced",
        name = "colbert-endpoint"
)
public class HttpColbertRerankClient implements ColbertRerankClient {

    private final ObjectMapper objectMapper;
    private final AdvancedRetrievalProperties properties;
    private final HttpClient httpClient;
    private final URI endpoint;

    public HttpColbertRerankClient(
            ObjectMapper objectMapper,
            AdvancedRetrievalProperties properties
    ) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        if (properties.colbertEndpoint().isBlank()) {
            throw new IllegalArgumentException(
                    "colbert-endpoint must not be blank when configured"
            );
        }
        this.endpoint = URI.create(properties.colbertEndpoint());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.colbertTimeout())
                .build();
    }

    @Override
    public List<Double> score(
            String question,
            List<RetrievalHit> candidates
    ) {
        try {
            ColbertRequest payload = new ColbertRequest(
                    question,
                    candidates.stream()
                            .map(hit -> new ColbertDocument(
                                    hit.documentId() + ":" + hit.chunkId(),
                                    hit.text()
                            ))
                            .toList()
            );
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(properties.colbertTimeout())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(payload)
                    ))
                    .build();
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(
                        "ColBERT service returned HTTP " + response.statusCode()
                );
            }
            ColbertResponse decoded = objectMapper.readValue(
                    response.body(),
                    ColbertResponse.class
            );
            return decoded.scores() == null
                    ? List.of()
                    : List.copyOf(decoded.scores());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "ColBERT request interrupted",
                    exception
            );
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "ColBERT request failed",
                    exception
            );
        }
    }

    private record ColbertRequest(
            String query,
            List<ColbertDocument> documents
    ) {
    }

    private record ColbertDocument(
            String id,
            String text
    ) {
    }

    private record ColbertResponse(
            List<Double> scores
    ) {
    }
}
