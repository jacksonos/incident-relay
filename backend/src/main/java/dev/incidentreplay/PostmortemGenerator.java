package dev.incidentreplay;

import org.springframework.stereotype.Service;

import dev.incidentreplay.Analysis.Level;

@Service
public class PostmortemGenerator {
    public String generate(Analysis analysis) {
        if (analysis == null || analysis.events().isEmpty()) {
            throw new IllegalArgumentException("Se necesita un análisis con eventos válidos.");
        }
        var summary = analysis.summary();
        var md = new StringBuilder("# Incident Replay — borrador de postmortem\n\n")
                .append("Modo: determinista, sin IA. Zona horaria: UTC.\n")
                .append("Alcance: análisis completo, independientemente de los filtros de interfaz.\n\n")
                .append("## Resumen de hechos\n\n")
                .append("- Rango observado (UTC): ").append(summary.firstEvent())
                .append(" → ").append(summary.lastEvent()).append("\n")
                .append("- Eventos procesados: ").append(summary.processedCount()).append("\n")
                .append("- Líneas rechazadas: ").append(summary.rejectedCount()).append("\n")
                .append("- Líneas vacías: ").append(analysis.blankLineCount()).append("\n");
        for (Level level : Level.values()) {
            md.append("- ").append(level).append(": ").append(summary.levelCounts().get(level)).append("\n");
        }
        md.append("\n## Advertencias\n\n");
        if (analysis.warnings().isEmpty()) {
            md.append("Sin líneas omitidas. Esto no demuestra que los logs estén completos.\n");
        } else {
            analysis.warnings().forEach(warning -> md.append("- ").append(escape(warning)).append("\n"));
            md.append("- Números de línea omitidos: ").append(analysis.invalidLineNumbers()).append("\n");
        }
        md.append("\n## Cronología y evidencia\n\n");
        for (var event : analysis.events()) {
            md.append("- ").append(event.timestampUtc()).append(" | ").append(event.level())
                    .append(" | ").append(escape(event.service())).append(" | ").append(escape(event.message()))
                    .append(" — ").append(event.id()).append(", línea ").append(event.lineNumber()).append("\n")
                    .append("  - Timestamp original: ").append(escape(event.timestampOriginal())).append("\n")
                    .append("  - Línea original: ").append(escape(event.rawLine())).append("\n");
        }
        md.append("\n## Mensajes repetidos\n\n");
        if (analysis.groups().isEmpty()) {
            md.append("Sin mensajes repetidos.\n");
        }
        for (var group : analysis.groups()) {
            md.append("- ").append(escape(group.normalizedMessage())).append(": ").append(group.count())
                    .append(" eventos (").append(String.join(", ", group.eventIds())).append(").\n");
        }
        return md.append("\n## Impacto\n\nDesconocido; pendiente de verificación humana.\n")
                .append("\n## Hipótesis pendientes\n\nNo se ha determinado la causa. Formular y contrastar hipótesis con evidencia adicional.\n")
                .append("\n## Acciones pendientes\n\nPendiente: revisar métricas, despliegues y contexto con las personas responsables.\n")
                .append("\n## Limitaciones\n\nCorrelación temporal no demuestra causalidad. El rango observado no confirma la duración del incidente. ")
                .append("Solo se analizan las líneas válidas suministradas; no se infieren causas ni impactos. ")
                .append("Los mensajes repetidos solo colapsan espacios y mayúsculas; conservan números e IDs. ")
                .append("Este borrador requiere revisión humana.\n").toString();
    }

    private static String escape(String text) {
        StringBuilder escaped = new StringBuilder();
        text.codePoints().forEach(character -> {
            if (character < 32 || "\\`*_{}[]<>()#+-.!|&~".indexOf(character) >= 0) {
                escaped.append("&#").append(character).append(';');
            } else {
                escaped.appendCodePoint(character);
            }
        });
        return escaped.toString();
    }
}
