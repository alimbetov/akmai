package kz.alimbetov.akmai.rag.service;

import kz.alimbetov.akmai.rag.query.QueryLanguageDetector;
import org.springframework.stereotype.Component;

@Component
public class RagFallbackMessages {

    private final QueryLanguageDetector languageDetector;

    public RagFallbackMessages(QueryLanguageDetector languageDetector) {
        this.languageDetector = languageDetector;
    }

    public String insufficientInformation(String question) {
        return switch (languageDetector.detect(question)) {
            case "kk" -> "Білім базасында жеткілікті ақпарат жоқ.";
            case "ru" -> "В базе знаний недостаточно информации.";
            case "en" -> "There is insufficient information in the knowledge base.";
            case "zh" -> "知识库中的信息不足。";
            case "de" -> "Die Wissensdatenbank enthält nicht genügend Informationen.";
            case "fr" ->
                    "La base de connaissances ne contient pas suffisamment d’informations.";
            case "es" ->
                    "La base de conocimientos no contiene información suficiente.";
            case "pt" ->
                    "A base de conhecimento não contém informação suficiente.";
            case "it" ->
                    "La base di conoscenza non contiene informazioni sufficienti.";
            case "tr" -> "Bilgi tabanında yeterli bilgi yok.";
            case "el" ->
                    "Η βάση γνώσεων δεν περιέχει επαρκείς πληροφορίες.";
            default -> "В базе знаний недостаточно информации.";
        };
    }
}
