package dev.incidentreplay;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import dev.incidentreplay.Analysis.Event;
import dev.incidentreplay.Analysis.Group;
import dev.incidentreplay.Analysis.Level;
import dev.incidentreplay.Analysis.Summary;

@Service
public class LogParser {
    public static class LimitExceededException extends IllegalArgumentException {
        LimitExceededException(String message) { super(message); }
    }
    public static final int MAX_BYTES = 1024 * 1024;
    public static final int MAX_NON_BLANK_LINES = 10_000;
    private static final Pattern LINE = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?(?:Z|[+-]\\d{2}:\\d{2}))"
            + "\\h+(DEBUG|INFO|WARN|ERROR)\\h+(\\S+)\\h+-\\h+(.+)$",
            Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern SPACES = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    public Analysis parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Introduce logs en UTF-8.");
        }
        if (text.length() > MAX_BYTES) {
            throw new LimitExceededException("La entrada supera 1 MiB en UTF-8.");
        }
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(text));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return parse(bytes);
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("La entrada contiene texto Unicode inválido.");
        }
    }

    public Analysis parse(byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("Introduce logs en UTF-8.");
        }
        if (bytes.length > MAX_BYTES) {
            throw new LimitExceededException("La entrada supera 1 MiB en UTF-8.");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("El archivo no tiene codificación UTF-8 válida.");
        }
        if (text.isEmpty() || text.isBlank()) {
            throw new IllegalArgumentException("La entrada está vacía.");
        }
        List<Event> events = new ArrayList<>();
        List<Integer> invalid = new ArrayList<>();
        int blankCount = 0;
        int nonBlankCount = 0;
        List<String> lines = text.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            if (raw.isBlank()) {
                blankCount++;
                continue;
            }
            if (++nonBlankCount > MAX_NON_BLANK_LINES) {
                throw new LimitExceededException("La entrada supera 10 000 líneas no vacías.");
            }
            var match = LINE.matcher(raw);
            if (!match.matches() || match.group(4).isBlank()) {
                invalid.add(i + 1);
                continue;
            }
            try {
                var timestamp = OffsetDateTime.parse(match.group(1)).toInstant();
                events.add(new Event("event-" + (i + 1), i + 1, match.group(1), timestamp,
                        Level.valueOf(match.group(2)), match.group(3), match.group(4), raw));
            } catch (DateTimeParseException e) {
                invalid.add(i + 1);
            }
        }
        if (events.isEmpty()) {
            throw new IllegalArgumentException("No hay eventos válidos. Usa: timestamp nivel servicio - mensaje; incluye zona horaria.");
        }
        events.sort(Comparator.comparing(Event::timestampUtc).thenComparingInt(Event::lineNumber));
        var counts = new EnumMap<Level, Integer>(Level.class);
        for (Level level : Level.values()) {
            counts.put(level, 0);
        }
        var grouped = new LinkedHashMap<String, List<String>>();
        for (Event event : events) {
            counts.merge(event.level(), 1, Integer::sum);
            String normalized = SPACES.matcher(event.message()).replaceAll(" ").strip().toLowerCase(Locale.ROOT);
            grouped.computeIfAbsent(normalized, key -> new ArrayList<>()).add(event.id());
        }
        List<Group> groups = grouped.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(entry -> new Group(entry.getKey(), entry.getValue())).toList();
        List<String> warnings = invalid.isEmpty() ? List.of()
                : List.of("Análisis parcial: se omitieron " + invalid.size() + " líneas no reconocidas.");
        return new Analysis(events, invalid, blankCount, warnings,
                new Summary(events.size(), invalid.size(), events.getFirst().timestampUtc(),
                        events.getLast().timestampUtc(), counts), groups);
    }
}
