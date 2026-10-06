package kz.alimbetov.akmai.knowledge.api;

import jakarta.validation.Valid;
import kz.alimbetov.akmai.api.ApiRequestValidator;
import kz.alimbetov.akmai.knowledge.service.KnowledgeIngestionPort;
import kz.alimbetov.akmai.security.KnowledgeAccessLevelAuthorizer;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeIngestionPort ingestionService;
    private final ApiRequestValidator requestValidator;
    private final KnowledgeAccessLevelAuthorizer accessLevelAuthorizer;

    @PostMapping("/text")
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeIngestionResponse addText(
            @Valid @RequestBody AddKnowledgeRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false)
            String idempotencyKey
    ) {
        requestValidator.validateKnowledge(request);
        requestValidator.validateIdempotencyKey(idempotencyKey);
        accessLevelAuthorizer.requireWriteAccess(request.accessLevel());
        return ingestionService.addText(request, idempotencyKey);
    }
}
