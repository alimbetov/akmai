package kz.alimbetov.akmai.knowledge.api;

import jakarta.validation.Valid;
import kz.alimbetov.akmai.knowledge.service.KnowledgeIngestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeIngestionService ingestionService;

    @PostMapping("/text")
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeIngestionResponse addText(
            @Valid @RequestBody AddKnowledgeRequest request
    ) {
        return ingestionService.addText(request);
    }
}
