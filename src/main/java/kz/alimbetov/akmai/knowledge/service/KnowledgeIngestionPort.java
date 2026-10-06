package kz.alimbetov.akmai.knowledge.service;

import kz.alimbetov.akmai.knowledge.api.AddKnowledgeRequest;
import kz.alimbetov.akmai.knowledge.api.KnowledgeIngestionResponse;

public interface KnowledgeIngestionPort {

    KnowledgeIngestionResponse addText(
            AddKnowledgeRequest request,
            String idempotencyKey
    );
}
