package kz.alimbetov.akmai.rag.api;

import jakarta.validation.Valid;
import kz.alimbetov.akmai.rag.service.RagQuestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class RagController {

    private final RagQuestionService questionService;

    @PostMapping("/ask")
    public RagResponse ask(@Valid @RequestBody QuestionRequest request) {
        return questionService.ask(request.question());
    }
}
