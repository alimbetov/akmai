package kz.alimbetov.akmai.rag.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class RagBenchmarkDatasetLoader {

    private RagBenchmarkDatasetLoader() {
    }

    public static RagBenchmarkDataset load(
            Path root,
            ObjectMapper objectMapper
    ) throws IOException {
        if (root == null || objectMapper == null) {
            throw new IllegalArgumentException("root and objectMapper are required");
        }
        Manifest manifest = objectMapper.readValue(
                required(root.resolve("manifest.json")).toFile(),
                Manifest.class
        );
        List<RagBenchmarkDataset.Document> documents = readJsonLines(
                required(root.resolve("documents.jsonl")),
                objectMapper,
                RagBenchmarkDataset.Document.class
        );
        List<RagBenchmarkDataset.Query> queries = readJsonLines(
                required(root.resolve("queries.jsonl")),
                objectMapper,
                RagBenchmarkDataset.Query.class
        );
        return new RagBenchmarkDataset(
                manifest.benchmarkVersion(),
                manifest.corpusVersion(),
                manifest.releaseQualified(),
                documents,
                queries,
                manifest.metadata()
        );
    }

    private static <T> List<T> readJsonLines(
            Path path,
            ObjectMapper objectMapper,
            Class<T> type
    ) throws IOException {
        List<T> result = new ArrayList<>();
        int lineNumber = 0;
        for (String line : Files.readAllLines(path)) {
            lineNumber++;
            String value = line.trim();
            if (value.isEmpty()) {
                continue;
            }
            try {
                result.add(objectMapper.readValue(value, type));
            } catch (IOException exception) {
                throw new IOException(
                        "Invalid JSON at " + path + ":" + lineNumber,
                        exception
                );
            }
        }
        return List.copyOf(result);
    }

    private static Path required(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException(
                    "Missing benchmark dataset file: " + path
            );
        }
        return path;
    }

    private record Manifest(
            String benchmarkVersion,
            String corpusVersion,
            boolean releaseQualified,
            Map<String, String> metadata
    ) {
        private Manifest {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }
}
