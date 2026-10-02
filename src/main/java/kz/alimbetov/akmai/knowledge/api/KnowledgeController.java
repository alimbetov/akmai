package kz.alimbetov.akmai.knowledge.api;

import jakarta.validation.Valid;
import kz.alimbetov.akmai.api.ApiRequestValidator;
import org.springframework.web.bind.annotation.RequestHeader;
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
    private final ApiRequestValidator requestValidator;

    @PostMapping("/text")
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeIngestionResponse addText(
            @Valid @RequestBody AddKnowledgeRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false)
            String idempotencyKey
    ) {
        requestValidator.validateKnowledge(request);
        requestValidator.validateIdempotencyKey(idempotencyKey);
        return ingestionService.addText(request, idempotencyKey);
    }
}
