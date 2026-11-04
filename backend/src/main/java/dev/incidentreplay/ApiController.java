package dev.incidentreplay;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ApiController {
    // Six bytes per escaped ASCII byte, plus room for the JSON envelope.
    static final int MAX_ENVELOPE_BYTES = 6 * LogParser.MAX_BYTES + 1024;
    private final LogParser parser;
    private final PostmortemGenerator generator;
    private final ObjectMapper mapper;

    public ApiController(LogParser parser, PostmortemGenerator generator, ObjectMapper mapper) {
        this.parser = parser;
        this.generator = generator;
        this.mapper = mapper;
    }

    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @PostMapping(value = "/api/analyze", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> analyze(HttpServletRequest request) {
        try {
            var charset = MediaType.parseMediaType(request.getContentType()).getCharset();
            if (charset != null && !StandardCharsets.UTF_8.equals(charset)) {
                return error(400);
            }
            byte[] bytes = request.getInputStream().readNBytes(MAX_ENVELOPE_BYTES + 1);
            if (bytes.length > MAX_ENVELOPE_BYTES) return error(413);
            String json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            JsonNode root;
            try (JsonParser jsonParser = mapper.getFactory().createParser(json)) {
                jsonParser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                root = mapper.readTree(jsonParser);
                if (jsonParser.nextToken() != null) return error(400);
            }
            if (root == null || !root.isObject() || !root.path("text").isTextual()) return error(400);
            Analysis analysis = parser.parse(root.get("text").textValue());
            return ResponseEntity.ok(Map.of("analysis", analysis, "postmortemMarkdown", generator.generate(analysis)));
        } catch (LogParser.LimitExceededException e) {
            return error(413);
        } catch (IOException | IllegalArgumentException e) {
            return error(400);
        }
    }

    private ResponseEntity<Map<String, String>> error(int status) {
        return ResponseEntity.status(status).body(Map.of("error", status == 413 ? "Límite excedido." : "Entrada inválida."));
    }
}
