package dev.incidentreplay;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class PostmortemGeneratorTest {
    private final LogParser parser = new LogParser();
    private final PostmortemGenerator generator = new PostmortemGenerator();

    @Test
    void exportsAllFactsWithOriginEvidenceUtcAndPartialDataWarning() throws IOException {
        var analysis = parser.parse(LogParserTest.fixture("incident.log"));
        var md = generator.generate(analysis);
        assertThat(md).startsWith("# Incident Replay")
                .contains("Modo: determinista, sin IA", "Zona horaria: UTC", "análisis completo",
                        "Eventos procesados: 11", "Líneas rechazadas: 1", "Líneas vacías: 1",
                        "DEBUG: 2", "INFO: 3", "WARN: 2", "ERROR: 4",
                        "2026-10-15T10:00:00Z → 2026-10-15T10:07:00Z",
                        "Análisis parcial", "Números de línea omitidos: [7]",
                        "connection timeout: 2 eventos (event-6, event-8)");
        int previousIndex = -1;
        for (var event : analysis.events()) {
            String reference = event.id() + ", línea " + event.lineNumber();
            int index = md.indexOf(reference);
            assertThat(index).isGreaterThan(previousIndex);
            previousIndex = index;
            assertThat(md).contains(event.timestampUtc().toString(), "Timestamp original:", "Línea original:");
        }
        assertThat(md).doesNotContain("fictional.database.Connection.open");
        assertThat(generator.generate(analysis)).isEqualTo(md);
    }

    @Test
    void doesNotInventCauseImpactOrActionsAlreadyTaken() throws IOException {
        var md = generator.generate(parser.parse(LogParserTest.fixture("normal.log")));
        assertThat(md).contains("## Impacto", "Desconocido; pendiente", "## Hipótesis pendientes",
                "No se ha determinado la causa", "## Acciones pendientes", "Pendiente: revisar",
                "Correlación temporal no demuestra causalidad", "no confirma la duración del incidente",
                "Sin líneas omitidas", "Sin mensajes repetidos", "revisión humana");
        assertThat(md).doesNotContain("Análisis parcial", "Causa raíz confirmada", "usuarios afectados", "Modo: IA");
    }

    @Test
    void keepsLogContentAsTextRatherThanActiveHtmlOrMarkdown() {
        var md = generator.generate(parser.parse("2026-10-15T10:00:00Z ERROR <svc> - <script>alert(1)</script> [click](https://example.com) `x` & *bold*"));
        assertThat(md).doesNotContain("<script>", "<svc>", "[click](", "`x`", "*bold*")
                .contains("&#60;script&#62;", "&#91;click&#93;", "&#96;x&#96;", "&#38;");
    }

    @Test
    void rejectsMissingOrEmptyAnalysis() {
        assertThatIllegalArgumentException().isThrownBy(() -> generator.generate(null));
        var empty = new Analysis(List.of(), List.of(), 0, List.of(), null, List.of());
        assertThatIllegalArgumentException().isThrownBy(() -> generator.generate(empty));
    }
}
