package dev.incidentreplay;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class IncidentReplayApplicationTest {
    @Autowired
    private LogParser parser;
    @Autowired
    private PostmortemGenerator generator;

    @Test
    void contextLoads() {
        assertThat(generator.generate(parser.parse("2026-10-15T10:00:00Z INFO app - Ready")))
                .contains("Modo: determinista, sin IA");
    }
}
