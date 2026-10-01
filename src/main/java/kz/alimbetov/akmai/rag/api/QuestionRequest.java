package kz.alimbetov.akmai.rag.api;

import jakarta.validation.constraints.NotBlank;

public record QuestionRequest(@NotBlank String question) {
}
