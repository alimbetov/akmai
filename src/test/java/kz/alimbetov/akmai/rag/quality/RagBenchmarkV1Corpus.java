package kz.alimbetov.akmai.rag.quality;

import java.util.List;
import kz.alimbetov.akmai.knowledge.model.KnowledgeDomain;

public final class RagBenchmarkV1Corpus {

    private RagBenchmarkV1Corpus() {
    }

    public static List<Case> smokeCases() {
        return List.of(
                new Case(
                        "kk-legal-termination",
                        "kk",
                        KnowledgeDomain.LEGAL,
                        "Шартты бұзу талаптары",
                        "25-бап. Егер төлем мерзімі отыз күннен артық кешіктірілсе, банк шартты бұзуға құқылы. Бұл ереже банктің техникалық қатесінен болған кешігуге қолданылмайды.",
                        "Қандай жағдайда банк шартты бұза алады?"
                ),
                new Case(
                        "ru-medical-contra",
                        "ru",
                        KnowledgeDomain.MEDICAL,
                        "Противопоказания препарата Альфа",
                        "Препарат Альфа противопоказан при тяжелой почечной недостаточности. Рекомендуемая доза при нормальной функции почек составляет 10 мг один раз в сутки.",
                        "При каком состоянии препарат Альфа противопоказан?"
                ),
                new Case(
                        "en-technical-conflict",
                        "en",
                        KnowledgeDomain.TECHNICAL,
                        "Payment API idempotency",
                        "POST /api/payments returns HTTP 409 when a payment operation with the same Idempotency-Key already exists. Clients must reuse the original operation result instead of creating a duplicate payment.",
                        "When does POST /api/payments return HTTP 409?"
                ),
                new Case(
                        "zh-legal-payment",
                        "zh",
                        KnowledgeDomain.LEGAL,
                        "合同付款期限",
                        "第十二条。买方应当在收到发票后三十个日历日内支付款项。双方书面同意可以变更付款期限。",
                        "买方应在收到发票后多少天内付款？"
                ),
                new Case(
                        "de-technical-timeout",
                        "de",
                        KnowledgeDomain.TECHNICAL,
                        "Abrufzeitüberschreitung",
                        "Der Retrieval-Dienst beendet eine Anfrage nach Ablauf der konfigurierten Frist. Datenbank- und Modellaufrufe müssen eigene Ressourcen-Zeitlimits verwenden.",
                        "Was muss nach Ablauf der Retrieval-Frist passieren?"
                ),
                new Case(
                        "fr-medical-monitoring",
                        "fr",
                        KnowledgeDomain.MEDICAL,
                        "Surveillance du traitement",
                        "Pendant le traitement, la pression artérielle et la fonction rénale doivent être contrôlées chaque semaine.",
                        "Quelle surveillance hebdomadaire est requise pendant le traitement ?"
                ),
                new Case(
                        "es-legal-notice",
                        "es",
                        KnowledgeDomain.LEGAL,
                        "Notificación de resolución",
                        "La parte que resuelva el contrato deberá notificarlo por escrito con al menos quince días naturales de antelación.",
                        "¿Con cuántos días de antelación debe notificarse la resolución?"
                ),
                new Case(
                        "pt-technical-retry",
                        "pt",
                        KnowledgeDomain.TECHNICAL,
                        "Política de repetição",
                        "Uma operação de ingestão repetida com a mesma chave de idempotência e o mesmo conteúdo deve devolver o resultado original sem criar uma nova geração.",
                        "O que deve acontecer quando a ingestão é repetida com a mesma chave e conteúdo?"
                ),
                new Case(
                        "it-medical-dose",
                        "it",
                        KnowledgeDomain.MEDICAL,
                        "Dose del medicinale Beta",
                        "La dose raccomandata del medicinale Beta è di 20 mg una volta al giorno per via orale.",
                        "Qual è la dose raccomandata del medicinale Beta?"
                ),
                new Case(
                        "tr-legal-retention",
                        "tr",
                        KnowledgeDomain.LEGAL,
                        "Belge saklama süresi",
                        "Sözleşme kayıtları sözleşmenin sona ermesinden sonra beş yıl süreyle saklanmalıdır.",
                        "Sözleşme kayıtları sona ermeden sonra kaç yıl saklanmalıdır?"
                ),
                new Case(
                        "el-technical-publication",
                        "el",
                        KnowledgeDomain.TECHNICAL,
                        "Ατομική δημοσίευση",
                        "Μια νέα γενιά εγγράφου γίνεται αναζητήσιμη μόνο μετά την επιτυχή ατομική δημοσίευση. Η αποτυχημένη ενδιάμεση γενιά δεν πρέπει να εμφανίζεται στην ανάκτηση.",
                        "Πότε γίνεται αναζητήσιμη μια νέα γενιά εγγράφου;"
                )
        );
    }

    public record Case(
            String id,
            String language,
            KnowledgeDomain domain,
            String title,
            String text,
            String question
    ) {
    }
}
