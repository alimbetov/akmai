package kz.alimbetov.akmai.knowledge.api;

import java.util.UUID;
import kz.alimbetov.akmai.knowledge.ingestion.async.AsyncIngestionAdmissionService;
import kz.alimbetov.akmai.security.KnowledgeAccessLevelAuthorizer;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge/ingestions")
@RequiredArgsConstructor
public class AsyncIngestionController {

    private final AsyncIngestionAdmissionService admissionService;
    private final KnowledgeAccessLevelAuthorizer accessLevelAuthorizer;

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AsyncIngestionAcceptedResponse admit(
            @RequestBody AsyncIngestionRequest request
    ) {
        if (request == null) {
            throw new IllegalArgumentException("async ingestion request is required");
        }
        accessLevelAuthorizer.requireWriteAccess(request.accessLevel());
        return admissionService.admit(request);
    }

    @GetMapping("/{ingestionId}")
    public AsyncIngestionStatusResponse status(
            @PathVariable UUID ingestionId
    ) {
        accessLevelAuthorizer.requireWriteAccess(
                admissionService.accessLevel(ingestionId)
        );
        return admissionService.status(ingestionId);
    }
}
