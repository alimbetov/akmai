package kz.alimbetov.akmai.knowledge.service;

import kz.alimbetov.akmai.knowledge.api.AddKnowledgeRequest;
import kz.alimbetov.akmai.knowledge.api.CanonicalDocument;
import kz.alimbetov.akmai.knowledge.api.CanonicalKnowledgeDocument;
import kz.alimbetov.akmai.knowledge.api.KnowledgeIngestionResponse;
import kz.alimbetov.akmai.knowledge.api.KnowledgeIngestionResult;

public interface KnowledgeIngestionPort {

    KnowledgeIngestionResponse addText(
            AddKnowledgeRequest request,
            String idempotencyKey
    );

    KnowledgeIngestionResponse addCanonical(
            CanonicalDocument document,
            String idempotencyKey
    );

    KnowledgeIngestionResult addCanonicalKnowledge(
            CanonicalKnowledgeDocument document,
            String idempotencyKey
    );
}
