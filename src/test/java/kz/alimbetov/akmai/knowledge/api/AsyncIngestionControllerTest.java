package kz.alimbetov.akmai.knowledge.api;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kz.alimbetov.akmai.knowledge.ingestion.async.AsyncIngestionAdmissionService;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;
import kz.alimbetov.akmai.security.KnowledgeAccessLevelAuthorizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AsyncIngestionControllerTest {

    AsyncIngestionAdmissionService admissionService;
    KnowledgeAccessLevelAuthorizer authorizer;
    ObjectMapper objectMapper;
    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        admissionService = org.mockito.Mockito.mock(AsyncIngestionAdmissionService.class);
        authorizer = org.mockito.Mockito.mock(KnowledgeAccessLevelAuthorizer.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        mockMvc = MockMvcBuilders.standaloneSetup(
                new AsyncIngestionController(admissionService, authorizer)
        ).build();
    }

    @Test
    void postReturns202OnlyThroughAdmissionService() throws Exception {
        CanonicalKnowledgeDocument document = document();
        AsyncIngestionRequest request = new AsyncIngestionRequest(
                1,
                "event-1",
                "request-1",
                document.documentId(),
                document.accessLevel(),
                document
        );
        UUID ingestionId = UUID.randomUUID();
        when(admissionService.admit(request)).thenReturn(
                new AsyncIngestionAcceptedResponse(
                        1,
                        ingestionId,
                        document.documentId(),
                        "ACCEPTED"
                )
        );

        mockMvc.perform(post("/api/knowledge/ingestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.ingestionId").value(ingestionId.toString()))
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        verify(authorizer).requireWriteAccess(document.accessLevel());
        verify(admissionService).admit(request);
    }

    @Test
    void statusUsesSingleReadForAccessCheckAndResponse() throws Exception {
        UUID ingestionId = UUID.randomUUID();
        AsyncIngestionStatusResponse response =
                new AsyncIngestionStatusResponse(
                        1,
                        ingestionId,
                        "doc-1",
                        "PROCESSING",
                        1,
                        0,
                        null,
                        null,
                        Instant.parse("2026-10-09T00:00:00Z"),
                        Instant.parse("2026-10-09T00:00:01Z"),
                        null
                );
        when(admissionService.statusView(ingestionId)).thenReturn(
                new AsyncIngestionAdmissionService.StatusView(7L, response)
        );

        mockMvc.perform(get("/api/knowledge/ingestions/{id}", ingestionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"));

        verify(admissionService).statusView(ingestionId);
        verify(authorizer).requireWriteAccess(7L);
    }

    private CanonicalKnowledgeDocument document() {
        return new CanonicalKnowledgeDocument(
                1,
                "doc-1",
                "1",
                "Title",
                "ru",
                KnowledgeDomain.TECHNICAL,
                1L,
                new CanonicalKnowledgeDocument.Source(
                        CanonicalKnowledgeDocument.SourceType.FILE,
                        "file-1",
                        "1",
                        "doc.pdf",
                        "application/pdf",
                        "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        new CanonicalKnowledgeDocument.StorageReference(
                                "rustfs",
                                "knowledge",
                                "tenant/doc.pdf",
                                "v1"
                        )
                ),
                new CanonicalKnowledgeDocument.Processing(
                        "pdf-parser",
                        "1.0",
                        Instant.parse("2026-10-09T00:00:00Z")
                ),
                List.of(new CanonicalKnowledgeDocument.Block(
                        "b1",
                        CanonicalKnowledgeDocument.BlockType.PARAGRAPH,
                        "Useful knowledge text.",
                        null,
                        1,
                        1,
                        List.of("Section"),
                        null
                )),
                Map.of()
        );
    }
}
