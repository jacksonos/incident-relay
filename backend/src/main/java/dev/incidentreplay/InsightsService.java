package dev.incidentreplay;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Validación sin estado de la selección; no acredita su procedencia del análisis. */
final class InsightsService {
    static final int MAX_ENVELOPE_BYTES = 80 * 1024;
    static final int MAX_EVENTS_BYTES = 64 * 1024;
    private static final Set<String> ROOT_FIELDS = Set.of("consent", "totalEvents", "events");
    private static final Set<String> EVENT_FIELDS = Set.of("id", "lineNumber", "timestampUtc", "level", "service", "message");
    private static final Set<String> LEVELS = Set.of("DEBUG", "INFO", "WARN", "ERROR");
    private final ObjectMapper mapper;

    InsightsService(ObjectMapper mapper) { this.mapper = mapper; }

    record Event(String id, int lineNumber, String timestampUtc, String level, String service, String message) { }
    record Selection(int totalEvents, List<Event> events) { }
    record Hypothesis(String statement, List<String> evidenceEventIds, List<String> uncertainties, List<String> nextChecks) {
        Hypothesis {
            evidenceEventIds = List.copyOf(evidenceEventIds);
            uncertainties = List.copyOf(uncertainties);
            nextChecks = List.copyOf(nextChecks);
        }
    }
    record Coverage(int selectedEvents, int totalEvents) { }
    record Result(String mode, List<Hypothesis> hypotheses, List<String> openQuestions, List<String> warnings, Coverage coverage) {
        Result {
            hypotheses = List.copyOf(hypotheses);
            openQuestions = List.copyOf(openQuestions);
            warnings = List.copyOf(warnings);
        }
    }

    static final class InvalidProvider extends Exception {
        InvalidProvider() { super("Respuesta del proveedor inválida."); }
    }

    static final class InvalidSelection extends Exception {
        private final int status;
        InvalidSelection(int status) { super(status == 413 ? "Límite excedido." : "Entrada inválida."); this.status = status; }
        int status() { return status; }
    }

    Selection validate(byte[] bytes) throws InvalidSelection {
        if (bytes.length > MAX_ENVELOPE_BYTES) throw new InvalidSelection(413);
        try {
            String json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            JsonNode root;
            try (JsonParser parser = mapper.getFactory().createParser(json)) {
                parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                root = mapper.readTree(parser);
                if (parser.nextToken() != null) throw new InvalidSelection(400);
            }
            if (!fields(root, ROOT_FIELDS) || !root.path("consent").isBoolean() || !root.path("consent").booleanValue()
                    || !integer(root.path("totalEvents")) || root.path("totalEvents").intValue() < 1
                    || root.path("totalEvents").intValue() > 10000) throw new InvalidSelection(400);
            JsonNode events = root.path("events");
            if (!events.isArray() || events.isEmpty() || events.size() > 100
                    || root.path("totalEvents").intValue() < events.size()) throw new InvalidSelection(400);
            if (mapper.writeValueAsString(events).getBytes(StandardCharsets.UTF_8).length > MAX_EVENTS_BYTES) throw new InvalidSelection(413);
            List<Event> result = new ArrayList<>();
            Set<Integer> lines = new HashSet<>();
            Instant previous = null;
            int previousLine = 0;
            for (JsonNode event : events) {
                if (!fields(event, EVENT_FIELDS) || !integer(event.path("lineNumber"))) throw new InvalidSelection(400);
                int line = event.path("lineNumber").intValue();
                String id = text(event, "id"), timestamp = text(event, "timestampUtc"), level = text(event, "level"),
                        service = text(event, "service"), message = text(event, "message");
                if (line < 1 || !lines.add(line) || !id.equals("event-" + line) || !timestamp.endsWith("Z")
                        || !LEVELS.contains(level) || service.isBlank() || service.codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))
                        || message.isBlank()) throw new InvalidSelection(400);
                Instant instant = OffsetDateTime.parse(timestamp).toInstant();
                if (previous != null && (instant.isBefore(previous) || (instant.equals(previous) && line <= previousLine)))
                    throw new InvalidSelection(400);
                previous = instant;
                previousLine = line;
                result.add(new Event(id, line, timestamp, level, service, message));
            }
            return new Selection(root.path("totalEvents").intValue(), List.copyOf(result));
        } catch (IOException | IllegalArgumentException | java.time.DateTimeException e) {
            throw new InvalidSelection(400);
        }
    }

    private static boolean integer(JsonNode node) { return node.isIntegralNumber() && node.canConvertToInt(); }

    Result parseResponse(String providerBody, Selection selection) throws InvalidProvider {
        try {
            JsonNode wrapper = strictJson(providerBody);
            JsonNode choices = wrapper.path("choices");
            if (!choices.isArray() || choices.isEmpty()) throw new InvalidProvider();
            JsonNode choice = choices.get(0);
            if (!choice.path("finish_reason").isTextual() || !choice.path("finish_reason").textValue().equals("stop"))
                throw new InvalidProvider();
            String content = text(choice.path("message"), "content");
            JsonNode root = strictJson(content);
            if (!fields(root, Set.of("hypotheses", "openQuestions"))) throw new InvalidProvider();
            JsonNode hypotheses = root.path("hypotheses");
            if (!hypotheses.isArray() || hypotheses.size() > 5) throw new InvalidProvider();
            List<String> questions = providerTexts(root.path("openQuestions"), 10);
            Set<String> validIds = new HashSet<>();
            for (Event event : selection.events()) validIds.add(event.id());
            List<Hypothesis> accepted = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            for (JsonNode hypothesis : hypotheses) {
                if (!fields(hypothesis, Set.of("statement", "evidenceEventIds", "uncertainties", "nextChecks")))
                    throw new InvalidProvider();
                String statement = providerText(hypothesis.path("statement"));
                List<String> uncertainties = providerTexts(hypothesis.path("uncertainties"), 5);
                List<String> nextChecks = providerTexts(hypothesis.path("nextChecks"), 5);
                JsonNode refs = hypothesis.path("evidenceEventIds");
                if (!refs.isArray() || refs.size() > 100) throw new InvalidProvider();
                List<String> ids = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                boolean valid = !refs.isEmpty();
                for (JsonNode ref : refs) {
                    if (!ref.isTextual()) throw new InvalidProvider();
                    String id = textValue(ref);
                    if (id.length() > 1000) throw new InvalidProvider();
                    // Referencias inválidas no se reflejan en la salida ni se consideran evidencia.
                    if (!validIds.contains(id) || !seen.add(id)) valid = false;
                    ids.add(id);
                }
                if (valid) accepted.add(new Hypothesis(statement, ids, uncertainties, nextChecks));
                else warnings.add("Se descartó una hipótesis por evidencia inválida.");
            }
            if (accepted.isEmpty()) warnings.add("No quedaron hipótesis con evidencia válida.");
            return new Result("assisted", accepted, questions, warnings,
                    new Coverage(selection.events().size(), selection.totalEvents()));
        } catch (IOException | InvalidSelection | IllegalArgumentException e) {
            throw new InvalidProvider();
        }
    }

    private JsonNode strictJson(String json) throws IOException, InvalidProvider {
        try (JsonParser parser = mapper.getFactory().createParser(json)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode root = mapper.readTree(parser);
            if (root == null || parser.nextToken() != null) throw new InvalidProvider();
            return root;
        }
    }

    private static List<String> providerTexts(JsonNode node, int max) throws InvalidProvider, InvalidSelection {
        if (!node.isArray() || node.size() > max) throw new InvalidProvider();
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) values.add(providerText(item));
        return values;
    }

    private static String providerText(JsonNode node) throws InvalidProvider, InvalidSelection {
        if (!node.isTextual()) throw new InvalidProvider();
        String value = textValue(node);
        if (value.isBlank() || value.length() > 1000) throw new InvalidProvider();
        return value;
    }

    private static boolean fields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject() || node.size() != expected.size()) return false;
        var names = node.fieldNames();
        while (names.hasNext()) if (!expected.contains(names.next())) return false;
        return true;
    }

    private static String text(JsonNode node, String field) throws InvalidSelection {
        return textValue(node.path(field));
    }

    private static String textValue(JsonNode value) throws InvalidSelection {
        if (!value.isTextual()) throw new InvalidSelection(400);
        String text = value.textValue();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i == text.length() || !Character.isLowSurrogate(text.charAt(i))) throw new InvalidSelection(400);
            } else if (Character.isLowSurrogate(c)) throw new InvalidSelection(400);
        }
        return text;
    }
}
