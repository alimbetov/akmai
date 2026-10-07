package kz.alimbetov.akmai.rag.api;

import jakarta.validation.Valid;
import kz.alimbetov.akmai.api.ApiRequestValidator;
import kz.alimbetov.akmai.rag.access.AccessLevelResolver;
import kz.alimbetov.akmai.rag.learning.RagFeedbackService;
import kz.alimbetov.akmai.rag.service.RagQuestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class RagController {

    private final RagQuestionService questionService;
    private final ApiRequestValidator requestValidator;
    private final AccessLevelResolver accessLevelResolver;
    private final RagFeedbackService feedbackService;
    private RagSourceProvenanceEnricher provenanceEnricher;

    @Autowired(required = false)
    void setProvenanceEnricher(RagSourceProvenanceEnricher provenanceEnricher) {
        this.provenanceEnricher = provenanceEnricher;
    }

    @PostMapping("/ask")
    public RagResponse ask(@Valid @RequestBody QuestionRequest request) {
        requestValidator.validateQuestion(request.question());
        var accessLevels = accessLevelResolver.resolve(request);
        RagResponse response = questionService.ask(
                request.question(),
                accessLevels
        );
        return provenanceEnricher == null
                ? response
                : provenanceEnricher.enrich(response, accessLevels);
    }

    @PostMapping("/feedback")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void feedback(
            @Valid @RequestBody RagFeedbackRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        requestValidator.validateIdempotencyKey(idempotencyKey);
        feedbackService.record(
                request.requestId(),
                request.reason(),
                request.details(),
                accessLevelResolver.resolve(request.accessLevels()),
                idempotencyKey
        );
    }
}
