package dev.incidentreplay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "IR_AI_ENABLED=false")
class ApiControllerTest {
    private static final String VALID = "2026-10-15T10:00:00Z INFO app - Ready";
    private final HttpClient client = HttpClient.newHttpClient();
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired LogParser parser;
    @Autowired PostmortemGenerator generator;

    private HttpResponse<String> post(byte[] body, boolean streaming) throws Exception {
        return post(body, streaming, "application/json");
    }

    private HttpResponse<String> post(byte[] body, boolean streaming, String contentType) throws Exception {
        var publisher = streaming ? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body))
                : HttpRequest.BodyPublishers.ofByteArray(body);
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/analyze"))
                .header("Content-Type", contentType).POST(publisher).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> text(String text) throws Exception {
        return post(mapper.writeValueAsBytes(java.util.Map.of("text", text)), false);
    }

    @Test void healthAndFixture() throws Exception {
        var health = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/health"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(health.body()).path("status").asText()).isEqualTo("ok");
        String fixture = new String(LogParserTest.fixture("incident.log"), StandardCharsets.UTF_8);
        var expected = parser.parse(fixture);
        var response = text(fixture);
        assertThat(response.statusCode()).isEqualTo(200);
        var root = mapper.readTree(response.body());
        var analysis = root.path("analysis");
        assertThat(analysis).isEqualTo(mapper.valueToTree(expected));
        int[] lines = {2, 3, 4, 6, 8, 1, 9, 10, 11, 12, 13};
        String[] original = fixture.split("\\R");
        assertThat(analysis.path("events").size()).isEqualTo(11);
        for (int i = 0; i < lines.length; i++) {
            var event = analysis.path("events").get(i);
            assertThat(event.path("id").asText()).isEqualTo("event-" + lines[i]);
            assertThat(event.path("lineNumber").asInt()).isEqualTo(lines[i]);
            assertThat(event.path("rawLine").asText()).isEqualTo(original[lines[i] - 1]);
            assertThat(event.path("timestampUtc").asText()).isEqualTo(expected.events().get(i).timestampUtc().toString());
        }
        assertThat(analysis.at("/summary/processedCount").asInt()).isEqualTo(11);
        assertThat(analysis.at("/summary/rejectedCount").asInt()).isEqualTo(1);
        assertThat(analysis.at("/summary/firstEvent").asText()).isEqualTo("2026-10-15T10:00:00Z");
        assertThat(analysis.at("/summary/lastEvent").asText()).isEqualTo("2026-10-15T10:07:00Z");
        assertThat(analysis.path("invalidLineNumbers").toString()).isEqualTo("[7]");
        assertThat(analysis.path("blankLineCount").asInt()).isEqualTo(1);
        assertThat(analysis.path("warnings").size()).isEqualTo(1);
        assertThat(analysis.at("/summary/levelCounts").toString()).contains("\"DEBUG\":2", "\"INFO\":3", "\"WARN\":2", "\"ERROR\":4");
        assertThat(analysis.path("groups").size()).isEqualTo(3);
        assertThat(analysis.at("/groups/0/eventIds").toString()).isEqualTo("[\"event-4\",\"event-10\"]");
        assertThat(analysis.at("/groups/1/eventIds").toString()).isEqualTo("[\"event-6\",\"event-8\"]");
        assertThat(analysis.at("/groups/2/eventIds").toString()).isEqualTo("[\"event-1\",\"event-9\"]");
        assertThat(root.path("postmortemMarkdown").asText()).isEqualTo(generator.generate(expected));
        assertThat(text(fixture).body()).isEqualTo(response.body());
    }

    @Test void disabledInsightsAreUnavailableOverHttp() throws Exception {
        var config = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/insights/config"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(mapper.readTree(config.body()).toString()).isEqualTo("{\"available\":false}");
        var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/insights"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("not-json"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).doesNotContain("not-json", "Exception");
    }

    @Test void rejectsMalformedAndUnsafeInput() throws Exception {
        for (String json : new String[]{"", "null", "[]", "\"root\"", "{", "{}", "{\"text\":\"   \"}", "{\"text\":null}", "{\"text\":12}",
                "{\"text\":true}", "{\"text\":[]}", "{\"text\":\"\"}", "{\"text\":\"secret\"}",
                "{\"text\":\"\\ud800\"}", "{\"text\":\"x\"} trailing", "{\"text\":\"x\",\"text\":\"y\"}"}) {
            var response = post(json.getBytes(StandardCharsets.UTF_8), false);
            assertThat(response.statusCode()).as(json).isEqualTo(400);
            assertThat(response.body()).doesNotContain("secret", "Exception", "stackTrace");
        }
        assertThat(post(new byte[]{'{', '"', 't', 'e', 'x', 't', '"', ':', '"', (byte) 0xc3, '"', '}'}, false).statusCode()).isEqualTo(400);
        assertThat(post("{\"text\":\"x\"}".getBytes(StandardCharsets.UTF_16LE), false).statusCode()).isEqualTo(400);
    }

    @Test void charsetMustBeUtf8() throws Exception {
        byte[] body = mapper.writeValueAsBytes(java.util.Map.of("text", VALID));
        assertThat(post(body, false, "application/json; charset=UTF-8").statusCode()).isEqualTo(200);
        assertThat(post(body, false, "application/json; charset=ISO-8859-1").statusCode()).isEqualTo(400);
    }

    @Test void malformedChunkReturnsSafeError() throws Exception {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            socket.setSoTimeout(3000);
            socket.getOutputStream().write(("POST /api/analyze HTTP/1.1\r\nHost: localhost\r\n"
                    + "Content-Type: application/json\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
                    + "not-a-chunk-secret\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.shutdownOutput();
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(response).startsWith("HTTP/1.1 400").contains("Bad Request")
                    .doesNotContain("secret", "Exception", "stackTrace");
        }
    }

    @Test void readFailureIsNormalizedByController() throws Exception {
        var request = mock(HttpServletRequest.class);
        when(request.getContentType()).thenReturn("application/json");
        when(request.getInputStream()).thenThrow(new IOException("secret"));
        var response = new ApiController(parser, generator, mapper).analyze(request);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().toString()).isEqualTo("{error=Entrada inválida.}");
    }

    @Test void textAndLineBoundaries() throws Exception {
        String exact = VALID + "x".repeat(LogParser.MAX_BYTES - VALID.length());
        assertThat(text(exact).statusCode()).isEqualTo(200);
        assertThat(text(exact + "x").statusCode()).isEqualTo(413);
        assertThat(text(VALID + "é".repeat(LogParser.MAX_BYTES / 2)).statusCode()).isEqualTo(413);
        String lines = (VALID + "\n").repeat(LogParser.MAX_NON_BLANK_LINES);
        assertThat(text(lines).statusCode()).isEqualTo(200);
        assertThat(text(lines + VALID).statusCode()).isEqualTo(413);
    }

    @Test void envelopeBoundaryAndChunkedReading() throws Exception {
        String prefix = "{\"text\":\"" + VALID + "\"}";
        String exact = prefix + " ".repeat(ApiController.MAX_ENVELOPE_BYTES - prefix.length());
        assertThat(post(exact.getBytes(StandardCharsets.UTF_8), true).statusCode()).isEqualTo(200);
        assertThat(post((exact + " ").getBytes(StandardCharsets.UTF_8), true).statusCode()).isEqualTo(413);
        String escaped = "{\"text\":\"" + VALID + "\\u0078".repeat(LogParser.MAX_BYTES - VALID.length()) + "\"}";
        assertThat(post(escaped.getBytes(StandardCharsets.UTF_8), true).statusCode()).isEqualTo(200);
    }
}
