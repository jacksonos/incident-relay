package dev.incidentreplay;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static dev.incidentreplay.Analysis.Level.*;
import static org.assertj.core.api.Assertions.*;

class LogParserTest {
    private final LogParser parser = new LogParser();
    private static final String VALID = "2026-10-15T10:00:00Z INFO app - Ready";

    @ParameterizedTest
    @ValueSource(strings = {"DEBUG", "INFO", "WARN", "ERROR"})
    void parsesEverySupportedLevelAndPreservesEvidence(String level) {
        String raw = "2026-10-15T05:00:00.123-05:00 " + level + " payments - Pago recibido - id=42  ";
        var result = parser.parse(raw.getBytes(StandardCharsets.UTF_8));
        var event = result.events().getFirst();
        assertThat(event.id()).isEqualTo("event-1");
        assertThat(event.lineNumber()).isEqualTo(1);
        assertThat(event.timestampOriginal()).isEqualTo("2026-10-15T05:00:00.123-05:00");
        assertThat(event.timestampUtc()).isEqualTo(Instant.parse("2026-10-15T10:00:00.123Z"));
        assertThat(event.level().name()).isEqualTo(level);
        assertThat(event.service()).isEqualTo("payments");
        assertThat(event.message()).isEqualTo("Pago recibido - id=42  ");
        assertThat(event.rawLine()).isEqualTo(raw);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r\n"})
    void supportsLineEndingsAndCountsPhysicalBlankLines(String newline) {
        var result = parser.parse(VALID + newline + "   " + newline + "broken" + newline + VALID + newline);
        assertThat(result.events()).extracting(Analysis.Event::lineNumber).containsExactly(1, 4);
        assertThat(result.invalidLineNumbers()).containsExactly(3);
        assertThat(result.blankLineCount()).isEqualTo(1);
        assertThat(result.events().getLast().rawLine()).isEqualTo(VALID);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "2026-02-30T10:00:00Z INFO app - bad",
            "2025-02-29T10:00:00Z INFO app - bad",
            "2026-13-15T10:00:00Z INFO app - bad",
            "2026-10-15T24:00:00Z INFO app - bad",
            "2026-10-15T10:60:00Z INFO app - bad",
            "2026-10-15T10:00:60Z INFO app - bad",
            "2026-10-15T10:00:00+19:00 INFO app - bad",
            "2026-10-15T10:00:00 INFO app - no timezone",
            "2026-10-15 INFO app - no time",
            "2026-10-15T10:00:00Z TRACE app - bad level",
            "2026-10-15T10:00:00Z info app - bad level",
            "2026-10-15T10:00:00Z INFO app name - bad service",
            "2026-10-15T10:00:00Z INFO app - ",
            "2026-10-15T10:00:00Z INFO app -    ",
            "2026-10-15T10:00:00Z INFO app message",
            "2026-10-15T10:00:00Z INFO",
            "    at fictional.Stack.frame(Stack.java:1)"})
    void rejectsMalformedLinesWithoutInventingEventsOrDates(String invalid) {
        var result = parser.parse(invalid + "\n" + VALID);
        assertThat(result.events()).hasSize(1);
        assertThat(result.events().getFirst().lineNumber()).isEqualTo(2);
        assertThat(result.invalidLineNumbers()).containsExactly(1);
        assertThat(result.summary().rejectedCount()).isEqualTo(1);
        assertThat(result.warnings()).singleElement().asString().contains("Análisis parcial");
    }

    @Test
    void ordersByInstantThenSourceLineEvenWithOffsetsAndDuplicates() {
        var result = parser.parse(String.join("\n",
                "2026-10-15T10:01:00Z ERROR app - Last",
                "2026-10-15T12:00:00+02:00 WARN app - Tie",
                VALID, VALID,
                "2026-10-15T04:59:00-05:00 DEBUG app - First"));
        assertThat(result.events()).extracting(Analysis.Event::lineNumber).containsExactly(5, 2, 3, 4, 1);
        assertThat(result.events()).extracting(Analysis.Event::id).doesNotHaveDuplicates();
        assertThat(parser.parse(String.join("\n", result.events().stream()
                .sorted(java.util.Comparator.comparingInt(Analysis.Event::lineNumber))
                .map(Analysis.Event::rawLine).toList()))).isEqualTo(result);
        assertThat(result.summary().firstEvent()).isEqualTo(Instant.parse("2026-10-15T09:59:00Z"));
        assertThat(result.summary().lastEvent()).isEqualTo(Instant.parse("2026-10-15T10:01:00Z"));
        assertThat(result.summary().levelCounts()).containsEntry(DEBUG, 1).containsEntry(INFO, 2)
                .containsEntry(WARN, 1).containsEntry(ERROR, 1);
    }

    @Test
    void normalizesOnlyWhitespaceAndCaseWithoutDependingOnSystemLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            var result = parser.parse(String.join("\n",
                    "2026-10-15T10:00:00Z INFO a - ID=42   TIMEOUT ",
                    "2026-10-15T10:00:00Z ERROR b - id=42\t timeout",
                    "2026-10-15T10:00:00Z INFO a - id=43 timeout"));
            assertThat(result.groups()).containsExactly(new Analysis.Group("id=42 timeout", List.of("event-1", "event-2")));
            assertThat(result.groups().getFirst().count()).isEqualTo(2);
            assertThat(result.events().getFirst().message()).isEqualTo("ID=42   TIMEOUT ");
        } finally {
            Locale.setDefault(previous);
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\r\n\t\n"})
    void rejectsMissingOrBlankInput(String input) {
        assertThatIllegalArgumentException().isThrownBy(() -> parser.parse(input));
    }

    @Test
    void rejectsAllInvalidInputWithoutExposingItsContents() {
        assertThatIllegalArgumentException().isThrownBy(() -> parser.parse("secret=fictional\nbroken"))
                .withMessageContaining("No hay eventos válidos").withMessageNotContaining("fictional");
    }

    @Test
    void validatesBytesAndUnicodeStrictly() {
        assertThatIllegalArgumentException().isThrownBy(() -> parser.parse((byte[]) null));
        assertThatIllegalArgumentException().isThrownBy(() -> parser.parse(new byte[0]));
        assertThatIllegalArgumentException().isThrownBy(() -> parser.parse(new byte[]{(byte) 0xc3, 0x28}))
                .withMessageContaining("UTF-8");
        assertThatIllegalArgumentException().isThrownBy(() -> parser.parse(VALID + "\ud800"))
                .withMessageContaining("Unicode inválido");
    }

    @Test
    void acceptsExactlyByteLimitAndRejectsOneByteMoreForBytesAndText() {
        String exact = VALID + "x".repeat(LogParser.MAX_BYTES - VALID.length());
        assertThat(parser.parse(exact).events()).hasSize(1);
        assertThat(parser.parse(exact.getBytes(StandardCharsets.UTF_8)).events()).hasSize(1);
        assertThatIllegalArgumentException().isThrownBy(() -> parser.parse(exact + "x")).withMessageContaining("1 MiB");
        assertThatIllegalArgumentException().isThrownBy(() -> parser.parse((exact + "x").getBytes(StandardCharsets.UTF_8)))
                .withMessageContaining("1 MiB");
    }

    @Test
    void countsUtf8BytesNotJavaCharacters() {
        String text = VALID + "é".repeat(LogParser.MAX_BYTES / 2);
        assertThat(text.length()).isLessThan(LogParser.MAX_BYTES);
        assertThatIllegalArgumentException().isThrownBy(() -> parser.parse(text)).withMessageContaining("1 MiB");
    }

    @Test
    void acceptsExactlyLineLimitAndRejectsOneMoreIncludingInvalidLines() {
        String exact = (VALID + "\n").repeat(LogParser.MAX_NON_BLANK_LINES);
        assertThat(parser.parse(exact).events()).hasSize(10_000);
        assertThatIllegalArgumentException().isThrownBy(() -> parser.parse(exact + VALID))
                .withMessageContaining("10 000");
        assertThatIllegalArgumentException().isThrownBy(() -> parser.parse(VALID + "\ninvalid".repeat(10_000)))
                .withMessageContaining("10 000");
        assertThat(parser.parse(exact + "\n".repeat(10)).blankLineCount()).isEqualTo(10);
    }

    @Test
    void incidentFixtureHasExactCountsStableTiesAndThreeRepeatedGroups() throws IOException {
        var result = parser.parse(fixture("incident.log"));
        assertThat(result.summary().processedCount()).isEqualTo(11);
        assertThat(result.summary().rejectedCount()).isEqualTo(1);
        assertThat(result.invalidLineNumbers()).containsExactly(7);
        assertThat(result.blankLineCount()).isEqualTo(1);
        assertThat(result.summary().levelCounts()).containsEntry(DEBUG, 2).containsEntry(INFO, 3)
                .containsEntry(WARN, 2).containsEntry(ERROR, 4);
        assertThat(result.summary().firstEvent()).isEqualTo(Instant.parse("2026-10-15T10:00:00Z"));
        assertThat(result.summary().lastEvent()).isEqualTo(Instant.parse("2026-10-15T10:07:00Z"));
        assertThat(result.events()).extracting(Analysis.Event::lineNumber).containsExactly(2, 3, 4, 6, 8, 1, 9, 10, 11, 12, 13);
        assertThat(result.groups()).hasSize(3).allSatisfy(group -> assertThat(group.count()).isEqualTo(2));
        assertThat(result.groups()).extracting(Analysis.Group::normalizedMessage)
                .containsExactly("request latency increased", "connection timeout", "http 503 returned");
    }

    @Test
    void normalFixtureHasNoErrorsOrWarningsAndCollectionsAreImmutable() throws IOException {
        var result = parser.parse(fixture("normal.log"));
        assertThat(result.events()).hasSize(3);
        assertThat(result.summary().levelCounts()).containsEntry(ERROR, 0).containsEntry(WARN, 0);
        assertThat(result.invalidLineNumbers()).isEmpty();
        assertThat(result.warnings()).isEmpty();
        assertThat(result.groups()).isEmpty();
        assertThat(result.blankLineCount()).isZero();
        assertThatThrownBy(() -> result.events().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.summary().levelCounts().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    static byte[] fixture(String name) throws IOException {
        try (var stream = LogParserTest.class.getResourceAsStream("/fixtures/" + name)) {
            if (stream == null) {
                throw new IOException("Fixture ausente: " + name);
            }
            return stream.readAllBytes();
        }
    }
}
