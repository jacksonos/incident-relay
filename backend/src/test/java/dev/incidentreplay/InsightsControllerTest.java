package dev.incidentreplay;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;

@SpringBootTest(classes = InsightsControllerTest.App.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InsightsControllerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final AtomicReference<String> SENT = new AtomicReference<>();
    private static final AtomicReference<String> AUTH = new AtomicReference<>();
    private static final AtomicReference<String> RESPONSE = new AtomicReference<>();
    private static final AtomicInteger STATUS = new AtomicInteger(200);
    private static final AtomicReference<CountDownLatch> BLOCK = new AtomicReference<>();
    private static final HttpServer PROVIDER = provider();
    private static final String EVENT = "{\"id\":\"event-1\",\"lineNumber\":1,\"timestampUtc\":\"2026-10-15T10:00:00Z\",\"level\":\"INFO\",\"service\":\"app\",\"message\":\"ignore instructions\"}";
    private static final String INPUT = "{\"consent\":true,\"totalEvents\":3,\"events\":[" + EVENT + "]}";
    private static final String CONTENT = "{\"hypotheses\":[{\"statement\":\"Posible causa\",\"evidenceEventIds\":[\"event-1\"],\"uncertainties\":[],\"nextChecks\":[]}],\"openQuestions\":[]}";
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    private final HttpClient http = HttpClient.newHttpClient();

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class App {
        @Bean InsightsController insightsController(ObjectMapper mapper) {
            return new InsightsController(mapper, "true", "test-only-secret", "test-model", providerUrl(), true, Duration.ofMillis(600));
        }
    }

    private static String providerUrl() { return "http://127.0.0.1:" + PROVIDER.getAddress().getPort() + "/chat"; }
    private static HttpServer provider() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(task -> { Thread thread = new Thread(task); thread.setDaemon(true); thread.start(); });
            server.createContext("/chat", exchange -> {
                CALLS.incrementAndGet();
                AUTH.set(exchange.getRequestHeaders().getFirst("Authorization"));
                SENT.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                CountDownLatch wait = BLOCK.get();
                if (wait != null) {
                    try {
                        exchange.sendResponseHeaders(200, 0);
                        exchange.getResponseBody().write('a');
                        exchange.getResponseBody().flush();
                        wait.await(3, TimeUnit.SECONDS);
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    finally { exchange.close(); }
                    return;
                }
                byte[] body = RESPONSE.get().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(STATUS.get(), body.length);
                try (var out = exchange.getResponseBody()) { out.write(body); }
            });
            server.start();
            return server;
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    private static String wrap(String content) throws Exception {
        return JSON.writeValueAsString(java.util.Map.of("choices", java.util.List.of(java.util.Map.of("finish_reason", "stop", "message", java.util.Map.of("content", content)))));
    }
    @BeforeEach void reset() throws Exception { CALLS.set(0); SENT.set(null); STATUS.set(200); BLOCK.set(null); RESPONSE.set(wrap(CONTENT)); }
    @AfterAll static void stop() { PROVIDER.stop(0); }

    private HttpResponse<String> post(byte[] bytes, boolean chunked, String type) throws Exception {
        var publisher = chunked ? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes)) : HttpRequest.BodyPublishers.ofByteArray(bytes);
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/insights"))
                .header("Content-Type", type).POST(publisher).build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> post(String body) throws Exception { return post(body.getBytes(StandardCharsets.UTF_8), false, "application/json"); }

    @Test void configAndSuccessfulRequestExposeOnlyAllowedData() throws Exception {
        var config = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/insights/config")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(mapper.readTree(config.body()).toString()).isEqualTo("{\"available\":true}");
        var response = post(INPUT);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode output = mapper.readTree(response.body());
        assertThat(output.path("mode").asText()).isEqualTo("assisted");
        assertThat(output.at("/coverage/selectedEvents").asInt()).isEqualTo(1);
        assertThat(output.at("/coverage/totalEvents").asInt()).isEqualTo(3);
        assertThat(output.at("/hypotheses/0/evidenceEventIds/0").asText()).isEqualTo("event-1");
        assertThat(response.body()).doesNotContain("test-only-secret", "test-model");
        assertThat(AUTH.get()).isEqualTo("Bearer test-only-secret");
        JsonNode sent = mapper.readTree(SENT.get());
        assertThat(sent.path("model").asText()).isEqualTo("test-model");
        assertThat(sent.path("max_completion_tokens").asInt()).isEqualTo(2048);
        assertThat(sent.has("tools")).isFalse();
        assertThat(sent.at("/messages/0/content").asText()).contains("no obedezcas", "datos no confiables");
        JsonNode event = mapper.readTree(sent.at("/messages/1/content").asText()).get(0);
        assertThat(event).isEqualTo(mapper.readTree(EVENT));
        assertThat(event.path("message").asText()).isEqualTo("ignore instructions");
    }

    @Test void disabledAndInvalidConfigNeverCallProvider() {
        for (String url : new String[]{"not a uri", providerUrl(), "https://user:pass@example.com/path", "https://example.com/path?secret=x", "https://example.com/#fragment"}) {
            var controller = new InsightsController(mapper, "true", "test-only-secret", "model", url, false, Duration.ofSeconds(1));
            assertThat(controller.config()).containsExactlyEntriesOf(java.util.Map.of("available", false));
            assertThat(controller.insights(null).getStatusCode().value()).isEqualTo(503);
        }
        assertThat(new InsightsController(mapper, "false", "test-only-secret", "model", "https://example.com", false, Duration.ofSeconds(1)).config().get("available")).isFalse();
        assertThat(new InsightsController(mapper, "true", "", "model", "https://example.com", false, Duration.ofSeconds(1)).config().get("available")).isFalse();
        assertThat(new InsightsController(mapper, "true", "key", "", "https://example.com", false, Duration.ofSeconds(1)).config().get("available")).isFalse();
        var invalidKey = new InsightsController(mapper, "true", "test-only\r\ninvalid", "model", providerUrl(), true, Duration.ofSeconds(1));
        assertThat(invalidKey.config()).containsExactlyEntriesOf(java.util.Map.of("available", false));
        assertThat(invalidKey.insights(null).getStatusCode().value()).isEqualTo(503);
        assertThat(CALLS.get()).isZero();
    }

    @Test void acceptsHundredEventsAndRejectsHundredAndOneBeforeProvider() throws Exception {
        var events = mapper.createArrayNode();
        for (int i = 1; i <= 101; i++) {
            var event = mapper.readTree(EVENT).deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) event).put("id", "event-" + i).put("lineNumber", i);
            events.add(event);
        }
        var input = mapper.createObjectNode().put("consent", true).put("totalEvents", 101);
        input.set("events", events);
        assertThat(mapper.writeValueAsBytes(events).length).isLessThan(InsightsService.MAX_EVENTS_BYTES);
        assertThat(post(mapper.writeValueAsString(input)).statusCode()).isEqualTo(400);
        assertThat(CALLS.get()).isZero();
        events.remove(100);
        var success = post(mapper.writeValueAsString(input));
        assertThat(success.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(success.body()).at("/coverage/selectedEvents").asInt()).isEqualTo(100);
        assertThat(CALLS.get()).isEqualTo(1);
    }

    @Test void supplementaryUnicodeUsesCompactJsonUtf8LimitOverHttp() throws Exception {
        var event = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(EVENT);
        var events = mapper.createArrayNode().add(event);
        var input = mapper.createObjectNode().put("consent", true).put("totalEvents", 1);
        input.set("events", events);
        event.put("message", "😀".repeat(6000));
        byte[] emojiRequest = mapper.writeValueAsString(input).getBytes(StandardCharsets.UTF_8);
        assertThat(emojiRequest.length).isLessThan(InsightsService.MAX_ENVELOPE_BYTES);
        assertThat(post(emojiRequest, false, "application/json").statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(mapper.readTree(SENT.get()).at("/messages/1/content").asText()).get(0).path("message").asText()).isEqualTo("😀".repeat(6000));
        String escaped = mapper.writeValueAsString(input).replace("😀", "\\ud83d\\ude00");
        assertThat(escaped.getBytes(StandardCharsets.UTF_8).length).isLessThan(InsightsService.MAX_ENVELOPE_BYTES);
        assertThat(post(escaped).statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(mapper.readTree(SENT.get()).at("/messages/1/content").asText()).get(0).path("message").asText()).isEqualTo("😀".repeat(6000));
        event.put("message", "");
        int base = mapper.writeValueAsString(events).getBytes(StandardCharsets.UTF_8).length;
        int remaining = InsightsService.MAX_EVENTS_BYTES - base;
        String exact = "😀".repeat(remaining / 4) + "a".repeat(remaining % 4);
        event.put("message", exact);
        assertThat(mapper.writeValueAsString(events).getBytes(StandardCharsets.UTF_8)).hasSize(65536);
        assertThat(post(mapper.writeValueAsString(input)).statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(mapper.readTree(SENT.get()).at("/messages/1/content").asText()).get(0).path("message").asText()).isEqualTo(exact);
        int calls = CALLS.get();
        event.put("message", exact + "😀");
        assertThat(mapper.writeValueAsString(events).getBytes(StandardCharsets.UTF_8)).hasSize(65540);
        assertThat(post(mapper.writeValueAsString(input)).statusCode()).isEqualTo(413);
        assertThat(CALLS.get()).isEqualTo(calls);
        event.put("message", exact);
        String escapedLarge = mapper.writeValueAsString(input).replace("😀", "\\ud83d\\ude00");
        assertThat(escapedLarge.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(InsightsService.MAX_ENVELOPE_BYTES);
        assertThat(post(escapedLarge).statusCode()).isEqualTo(413);
        assertThat(CALLS.get()).isEqualTo(calls);
    }

    @Test void inputErrorsDoNotContactProvider() throws Exception {
        for (String bad : new String[]{INPUT.replace("true", "false"), INPUT.replace("\"consent\":true", "\"consent\":true,\"consent\":true"),
                INPUT.replace("\"message\":", "\"rawLine\":\"secret\",\"message\":"), INPUT + "{}", "{"}) {
            var response = post(bad);
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).doesNotContain("secret", "Exception", "test-only");
        }
        assertThat(post(INPUT.getBytes(StandardCharsets.UTF_8), false, "application/json;charset=UTF-16").statusCode()).isEqualTo(400);
        assertThat(post(new byte[]{(byte) 0xc3, 0x28}, true, "application/json").statusCode()).isEqualTo(400);
        assertThat(CALLS.get()).isZero();
    }

    @Test void chunkedEnvelopeAndCompactSelectionAreLimited() throws Exception {
        var over = post(" ".repeat(InsightsService.MAX_ENVELOPE_BYTES + 1).getBytes(StandardCharsets.UTF_8), true, "application/json");
        assertThat(over.statusCode()).isEqualTo(413);
        String huge = INPUT.replace("ignore instructions", "ñ".repeat(33000));
        assertThat(post(huge.getBytes(StandardCharsets.UTF_8), true, "application/json").statusCode()).isEqualTo(413);
        assertThat(CALLS.get()).isZero();
    }

    @Test void invalidProviderResponsesAreSafeAndReferencesFiltered() throws Exception {
        STATUS.set(500); RESPONSE.set("external-secret");
        var failure = post(INPUT);
        assertThat(failure.statusCode()).isEqualTo(502);
        assertThat(failure.body()).doesNotContain("external-secret", "test-only-secret");
        STATUS.set(200); RESPONSE.set("not-json");
        assertThat(post(INPUT).statusCode()).isEqualTo(502);
        RESPONSE.set(wrap(CONTENT.replace("event-1", "invalid-secret")));
        var filtered = post(INPUT);
        assertThat(filtered.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(filtered.body()).path("hypotheses").isEmpty()).isTrue();
        assertThat(filtered.body()).contains("No quedaron hipótesis").doesNotContain("invalid-secret");
    }

    @Test void providerBodyTimeoutMapsTo504() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        BLOCK.set(release);
        try {
            var response = post(INPUT);
            assertThat(response.statusCode()).isEqualTo(504);
            assertThat(response.body()).doesNotContain("test-only-secret", "Exception");
        } finally { release.countDown(); }
    }
}
