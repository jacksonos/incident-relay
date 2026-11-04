package dev.incidentreplay;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record Analysis(List<Event> events, List<Integer> invalidLineNumbers,
                       int blankLineCount, List<String> warnings, Summary summary,
                       List<Group> groups) {
    public Analysis {
        events = List.copyOf(events);
        invalidLineNumbers = List.copyOf(invalidLineNumbers);
        warnings = List.copyOf(warnings);
        groups = List.copyOf(groups);
    }

    public enum Level { DEBUG, INFO, WARN, ERROR }

    public record Event(String id, int lineNumber, String timestampOriginal,
                        Instant timestampUtc, Level level, String service,
                        String message, String rawLine) { }

    public record Summary(int processedCount, int rejectedCount, Instant firstEvent,
                          Instant lastEvent, Map<Level, Integer> levelCounts) {
        public Summary {
            levelCounts = Map.copyOf(levelCounts);
        }
    }

    public record Group(String normalizedMessage, List<String> eventIds) {
        public Group {
            eventIds = List.copyOf(eventIds);
        }

        public int count() {
            return eventIds.size();
        }
    }
}
