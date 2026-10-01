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
            SourceRef source = SourceRef.from(index + 1, hit);
            return "[SOURCE " + source.number() + "]"
                    + "\ndocumentId: " + source.documentId()
                    + "\nchunkId: " + source.chunkId()
                    + "\nsource: " + source.source()
                    + "\nlanguage: " + source.language()
                    + "\nsectionPath: " + source.sectionPath()
                    + "\npage: " + source.page()
                    + "\n\n" + hit.text();
        }).collect(Collectors.joining("\n\n"));
    }
}
