package kz.alimbetov.akmai.rag.retrieval;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.stereotype.Component;
@Component
public class ContextAssembler {
    public String assemble(List<RetrievalHit> hits) {
        return IntStream.range(0, hits.size()).mapToObj(index -> {
            RetrievalHit hit = hits.get(index);
            return "[SOURCE " + (index + 1) + "]\nretrieval: " + hit.type()
                    + "\ndocumentId: " + hit.documentId()
                    + "\nchunkId: " + hit.chunkId()
                    + "\nmetadata: " + hit.metadata()
                    + "\n\n" + hit.text();
        }).collect(Collectors.joining("\n\n"));
    }
}
