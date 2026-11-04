package dev.incidentreplay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class InsightsServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final InsightsService service = new InsightsService(mapper);
    private static final String EVENT = "{\"id\":\"event-1\",\"lineNumber\":1,\"timestampUtc\":\"2026-10-15T10:00:00Z\",\"level\":\"INFO\",\"service\":\"app\",\"message\":\"listo\"}";
    private static String request(String events) { return "{\"consent\":true,\"totalEvents\":2,\"events\":" + events + "}"; }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    private void rejects(String json, int status) {
        assertThatThrownBy(() -> service.validate(bytes(json)))
                .isInstanceOfSatisfying(InsightsService.InvalidSelection.class, e -> assertThat(e.status()).isEqualTo(status));
    }

    @Test void acceptsValidSelectionAndPreservesMessage() throws Exception {
        var selection = service.validate(bytes(request("[" + EVENT + "]")));
        assertThat(selection.totalEvents()).isEqualTo(2);
        assertThat(selection.events()).containsExactly(new InsightsService.Event("event-1", 1, "2026-10-15T10:00:00Z", "INFO", "app", "listo"));
        assertThatThrownBy(() -> selection.events().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void rejectsBadEnvelopeAndExactTypes() {
        String valid = request("[" + EVENT + "]");
        for (String bad : new String[]{valid.replace("true", "false"), valid.replace("true", "\"true\""),
                valid.replace("\"totalEvents\":2", "\"totalEvents\":2.0"), valid.replace("\"totalEvents\":2", "\"totalEvents\":10001"),
                valid.replace("\"events\":", "\"extra\":1,\"events\":"), valid + " {}", valid.replace("\"consent\":true", "\"consent\":true,\"consent\":true"),
                request("[]"), valid.replace("\"totalEvents\":2", "\"totalEvents\":0")}) rejects(bad, 400);
    }

    @Test void rejectsInvalidEventsAndUnicode() {
        String valid = request("[" + EVENT + "]");
        for (String bad : new String[]{valid.replace("\"message\":", "\"rawLine\":\"x\",\"message\":"),
                valid.replace("\"lineNumber\":1", "\"lineNumber\":\"1\""), valid.replace("event-1", "event-2"),
                valid.replace("2026-10-15T10:00:00Z", "2026-02-30T10:00:00Z"),
                valid.replace("2026-10-15T10:00:00Z", "2026-10-15T10:00:00+00:00"),
                valid.replace("INFO", "info"), valid.replace("\"app\"", "\"a b\""), valid.replace("listo", " "),
                valid.replace("listo", "\\ud800"), valid.replace("listo", "\\udc00"),
                valid.replace("\"message\":\"listo\"", "\"message\":\"x\",\"message\":\"y\"")}) rejects(bad, 400);
        byte[] malformed = bytes(valid);
        int pos = valid.indexOf("listo");
        malformed[pos] = (byte) 0xc3;
        assertThatThrownBy(() -> service.validate(malformed)).isInstanceOfSatisfying(InsightsService.InvalidSelection.class,
                e -> assertThat(e.status()).isEqualTo(400));
    }

    @Test void rejectsDuplicateAndOutOfOrderLines() {
        String second = EVENT.replace("event-1", "event-2").replace("\"lineNumber\":1", "\"lineNumber\":2");
        rejects(request("[" + EVENT + "," + EVENT + "]"), 400);
        rejects(request("[" + second + "," + EVENT + "]"), 400);
        rejects(request("[" + EVENT + "," + second.replace("10:00:00Z", "09:00:00Z") + "]"), 400);
        rejects(request("[" + EVENT + "," + second + "]").replace("\"totalEvents\":2", "\"totalEvents\":1"), 400);
    }

    @Test void checksCompactUtf8EventBytesExactly() throws Exception {
        int base = bytes("[" + EVENT.replace("listo", "") + "]").length;
        int remaining = InsightsService.MAX_EVENTS_BYTES - base;
        String exact = "ñ".repeat(remaining / 2) + "a".repeat(remaining % 2);
        service.validate(bytes(request("[" + EVENT.replace("listo", exact) + "]")));
        rejects(request("[" + EVENT.replace("listo", exact + "a") + "]"), 413);
    }

    @Test void acceptsSupplementaryUnicodeUsingUtf8CompactJsonSize() throws Exception {
        var event = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(EVENT);
        event.put("message", "😀".repeat(6000));
        var events = mapper.createArrayNode().add(event);
        String compact = mapper.writeValueAsString(events);
        assertThat(compact.getBytes(StandardCharsets.UTF_8).length).isLessThan(InsightsService.MAX_EVENTS_BYTES);
        var root = mapper.createObjectNode().put("consent", true).put("totalEvents", 1).set("events", events);
        var selection = service.validate(mapper.writeValueAsString(root).getBytes(StandardCharsets.UTF_8));
        assertThat(selection.events().getFirst().message()).isEqualTo("😀".repeat(6000));
    }

    @Test void checksEnvelopeBeforeParsing() {
        rejects(" ".repeat(InsightsService.MAX_ENVELOPE_BYTES + 1), 413);
        rejects(" ".repeat(InsightsService.MAX_ENVELOPE_BYTES), 400);
    }

    private InsightsService.Selection selection() throws Exception { return service.validate(bytes(request("[" + EVENT + "]"))); }
    private static final String HYP = "{\"statement\":\"Posible causa\",\"evidenceEventIds\":[\"event-1\"],\"uncertainties\":[\"Faltan datos\"],\"nextChecks\":[\"Revisar\"]}";
    private static String content(String hypotheses) { return "{\"hypotheses\":" + hypotheses + ",\"openQuestions\":[\"¿Qué pasó?\"]}"; }
    private String wrap(String content) throws Exception {
        return mapper.writeValueAsString(java.util.Map.of("choices", java.util.List.of(java.util.Map.of(
                "finish_reason", "stop", "message", java.util.Map.of("content", content)))));
    }
    private void badProvider(String body) throws Exception {
        assertThatThrownBy(() -> service.parseResponse(body, selection()))
                .isInstanceOfSatisfying(InsightsService.InvalidProvider.class, e -> {
                    assertThat(e.getMessage()).isEqualTo("Respuesta del proveedor inválida.");
                    assertThat(e.getCause()).isNull();
                });
    }

    @Test void responseSuccessAndImmutableCollections() throws Exception {
        var result = service.parseResponse(wrap(content("[" + HYP + "]")), selection());
        assertThat(result.mode()).isEqualTo("assisted");
        assertThat(result.coverage()).isEqualTo(new InsightsService.Coverage(1, 2));
        assertThat(result.hypotheses()).containsExactly(new InsightsService.Hypothesis("Posible causa", java.util.List.of("event-1"), java.util.List.of("Faltan datos"), java.util.List.of("Revisar")));
        assertThat(result.openQuestions()).containsExactly("¿Qué pasó?");
        assertThat(result.warnings()).isEmpty();
        assertThatThrownBy(() -> result.hypotheses().getFirst().evidenceEventIds().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.warnings().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void responseDiscardsWholeHypothesisWithUnknownReference() throws Exception {
        String invalid = HYP.replace("event-1", "secret-unknown");
        var result = service.parseResponse(wrap(content("[" + HYP + "," + invalid + "]")), selection());
        assertThat(result.hypotheses()).hasSize(1);
        assertThat(result.warnings()).hasSize(1).noneMatch(w -> w.contains("secret-unknown"));
    }

    @Test void responseDiscardsEmptyAndDuplicateReferences() throws Exception {
        String empty = HYP.replace("[\"event-1\"]", "[]");
        String duplicate = HYP.replace("[\"event-1\"]", "[\"event-1\",\"event-1\"]");
        var result = service.parseResponse(wrap(content("[" + empty + "," + duplicate + "]")), selection());
        assertThat(result.hypotheses()).isEmpty();
        assertThat(result.warnings()).hasSize(3).contains("No quedaron hipótesis con evidencia válida.");
    }

    @Test void responseWarnsWhenNoHypotheses() throws Exception {
        var result = service.parseResponse(wrap(content("[]")), selection());
        assertThat(result.hypotheses()).isEmpty();
        assertThat(result.warnings()).containsExactly("No quedaron hipótesis con evidencia válida.");
    }

    @Test void responseRejectsMalformedDuplicateAndTrailingJson() throws Exception {
        for (String json : new String[]{"{", content("[]") + " {}", "{\"hypotheses\":[],\"hypotheses\":[],\"openQuestions\":[]}"}) badProvider(wrap(json));
        badProvider("{\"choices\":[],\"choices\":[]}");
        badProvider(wrap(content("[]")) + " true");
    }

    @Test void responseRejectsMissingFieldsAndWrongTypes() throws Exception {
        for (String json : new String[]{"{}", "{\"hypotheses\":[],\"openQuestions\":null}",
                content("[" + HYP.replace("\"statement\":\"Posible causa\",", "") + "]"),
                content("[" + HYP.replace("[\"event-1\"]", "[1]") + "]"),
                content("[" + HYP.replace("\"statement\":\"Posible causa\"", "\"statement\":null") + "]"),
                content("[]").replace("\"openQuestions\":", "\"extra\":1,\"openQuestions\":")}) badProvider(wrap(json));
    }

    @Test void responseEnforcesTextAndArrayLimits() throws Exception {
        for (String json : new String[]{content("[" + HYP.replace("Posible causa", "a".repeat(1001)) + "]"),
                content("[" + HYP.replace("Posible causa", " ") + "]"),
                content("[" + String.join(",", java.util.Collections.nCopies(6, HYP)) + "]"),
                content("[" + HYP.replace("[\"Faltan datos\"]", "[" + String.join(",", java.util.Collections.nCopies(6, "\"x\"")) + "]") + "]"),
                content("[]").replace("[\"¿Qué pasó?\"]", "[" + String.join(",", java.util.Collections.nCopies(11, "\"x\"")) + "]"),
                content("[" + HYP.replace("[\"event-1\"]", "[" + String.join(",", java.util.Collections.nCopies(101, "\"event-1\"")) + "]") + "]")}) badProvider(wrap(json));
    }

    @Test void responseRejectsTruncationAndInvalidUnicode() throws Exception {
        badProvider(wrap(content("[]")).replace("\"stop\"", "\"length\""));
        badProvider("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":null}}]}");
        badProvider(wrap(content("[" + HYP.replace("Posible causa", "\\ud800") + "]")));
        badProvider(wrap(content("[" + HYP.replace("event-1", "\\udc00") + "]")));
    }
}
