package kz.alimbetov.akmai.knowledge.semantic;

import java.util.List;
import java.util.Map;

final class SemanticExpansionVocabulary {

    private static final Map<String, Vocabulary> BY_LANGUAGE = Map.ofEntries(
            entry("kk",
                    "қолданбалы,теориялық,эксперименттік,сандық,сапалық,есептеуіш,өнеркәсіптік,реттеуші,экономикалық,техникалық,операциялық,стратегиялық,жүйелік,салыстырмалы,болжамдық,тәуекелге негізделген,дәлелдерге негізделген,автоматтандырылған,интеграцияланған",
                    "талдау,модель,әдіс,жүйе,үдеріс,көрсеткіш,тәуекел,стандарт,саясат,технология,басқару,өлшеу,бағалау,мониторинг,оңтайландыру,болжам,қауіпсіздік,өнімділік,өмірлік цикл"),
            entry("ru",
                    "прикладной,теоретический,экспериментальный,количественный,качественный,вычислительный,промышленный,регуляторный,экономический,технический,операционный,стратегический,системный,сравнительный,прогнозный,риск-ориентированный,доказательный,автоматизированный,интегрированный",
                    "анализ,модель,метод,система,процесс,показатель,риск,стандарт,политика,технология,управление,измерение,оценка,мониторинг,оптимизация,прогноз,безопасность,эффективность,жизненный цикл"),
            entry("en",
                    "applied,theoretical,experimental,quantitative,qualitative,computational,industrial,regulatory,economic,technical,operational,strategic,systemic,comparative,predictive,risk-based,evidence-based,automated,integrated",
                    "analysis,model,method,system,process,indicator,risk,standard,policy,technology,management,measurement,evaluation,monitoring,optimization,forecast,safety,performance,lifecycle"),
            entry("zh",
                    "应用,理论,实验,定量,定性,计算,工业,监管,经济,技术,运营,战略,系统,比较,预测,基于风险,基于证据,自动化,集成",
                    "分析,模型,方法,系统,过程,指标,风险,标准,政策,技术,管理,测量,评估,监测,优化,预测,安全,性能,生命周期"),
            entry("de",
                    "angewandt,theoretisch,experimentell,quantitativ,qualitativ,rechnergestützt,industriell,regulatorisch,wirtschaftlich,technisch,operativ,strategisch,systemisch,vergleichend,prädiktiv,risikobasiert,evidenzbasiert,automatisiert,integriert",
                    "Analyse,Modell,Methode,System,Prozess,Indikator,Risiko,Standard,Politik,Technologie,Management,Messung,Bewertung,Überwachung,Optimierung,Prognose,Sicherheit,Leistung,Lebenszyklus"),
            entry("fr",
                    "appliqué,théorique,expérimental,quantitatif,qualitatif,computationnel,industriel,réglementaire,économique,technique,opérationnel,stratégique,systémique,comparatif,prédictif,fondé sur le risque,fondé sur les preuves,automatisé,intégré",
                    "analyse,modèle,méthode,système,processus,indicateur,risque,norme,politique,technologie,gestion,mesure,évaluation,surveillance,optimisation,prévision,sécurité,performance,cycle de vie"),
            entry("es",
                    "aplicado,teórico,experimental,cuantitativo,cualitativo,computacional,industrial,regulatorio,económico,técnico,operativo,estratégico,sistémico,comparativo,predictivo,basado en riesgos,basado en evidencia,automatizado,integrado",
                    "análisis,modelo,método,sistema,proceso,indicador,riesgo,estándar,política,tecnología,gestión,medición,evaluación,monitoreo,optimización,pronóstico,seguridad,rendimiento,ciclo de vida"),
            entry("pt",
                    "aplicado,teórico,experimental,quantitativo,qualitativo,computacional,industrial,regulatório,económico,técnico,operacional,estratégico,sistémico,comparativo,preditivo,baseado em risco,baseado em evidência,automatizado,integrado",
                    "análise,modelo,método,sistema,processo,indicador,risco,norma,política,tecnologia,gestão,medição,avaliação,monitorização,otimização,previsão,segurança,desempenho,ciclo de vida"),
            entry("it",
                    "applicato,teorico,sperimentale,quantitativo,qualitativo,computazionale,industriale,regolamentare,economico,tecnico,operativo,strategico,sistemico,comparativo,predittivo,basato sul rischio,basato su evidenze,automatizzato,integrato",
                    "analisi,modello,metodo,sistema,processo,indicatore,rischio,standard,politica,tecnologia,gestione,misurazione,valutazione,monitoraggio,ottimizzazione,previsione,sicurezza,prestazioni,ciclo di vita"),
            entry("tr",
                    "uygulamalı,teorik,deneysel,nicel,nitel,hesaplamalı,endüstriyel,düzenleyici,ekonomik,teknik,operasyonel,stratejik,sistemik,karşılaştırmalı,öngörücü,risk temelli,kanıta dayalı,otomatik,entegre",
                    "analiz,model,yöntem,sistem,süreç,gösterge,risk,standart,politika,teknoloji,yönetim,ölçüm,değerlendirme,izleme,optimizasyon,tahmin,güvenlik,performans,yaşam döngüsü"),
            entry("el",
                    "εφαρμοσμένος,θεωρητικός,πειραματικός,ποσοτικός,ποιοτικός,υπολογιστικός,βιομηχανικός,ρυθμιστικός,οικονομικός,τεχνικός,λειτουργικός,στρατηγικός,συστημικός,συγκριτικός,προγνωστικός,βασισμένος στον κίνδυνο,τεκμηριωμένος,αυτοματοποιημένος,ολοκληρωμένος",
                    "ανάλυση,μοντέλο,μέθοδος,σύστημα,διαδικασία,δείκτης,κίνδυνος,πρότυπο,πολιτική,τεχνολογία,διαχείριση,μέτρηση,αξιολόγηση,παρακολούθηση,βελτιστοποίηση,πρόβλεψη,ασφάλεια,απόδοση,κύκλος ζωής")
    );

    private SemanticExpansionVocabulary() {
    }

    static Vocabulary require(String language) {
        Vocabulary value = BY_LANGUAGE.get(language);
        if (value == null) {
            throw new IllegalArgumentException(
                    "Unsupported semantic expansion language: " + language
            );
        }
        return value;
    }

    private static Map.Entry<String, Vocabulary> entry(
            String language,
            String qualifiers,
            String aspects
    ) {
        return Map.entry(
                language,
                new Vocabulary(split(qualifiers), split(aspects))
        );
    }

    private static List<String> split(String value) {
        return List.of(value.split(","));
    }

    record Vocabulary(
            List<String> qualifiers,
            List<String> aspects
    ) {
        Vocabulary {
            qualifiers = List.copyOf(qualifiers);
            aspects = List.copyOf(aspects);
            if (qualifiers.size() != 19 || aspects.size() != 19) {
                throw new IllegalArgumentException(
                        "semantic expansion vocabulary must contain 19 qualifiers and 19 aspects"
                );
            }
        }
    }
}
