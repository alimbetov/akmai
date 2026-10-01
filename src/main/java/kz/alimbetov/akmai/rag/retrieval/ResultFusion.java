package kz.alimbetov.akmai.rag.retrieval;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
@Component
public class ResultFusion {
    public List<RetrievalHit> fuse(List<RetrievalHit> hits) {
        Map<String, RetrievalHit> unique = new LinkedHashMap<>();
        for (RetrievalHit hit : hits) {
            String key = !hit.chunkId().isBlank() ? hit.chunkId() : hit.type() + "|" + hit.documentId() + "|" + hit.text();
            unique.putIfAbsent(key, hit);
        }
        return List.copyOf(unique.values());
    }
}
