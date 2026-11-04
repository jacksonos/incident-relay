package dev.incidentreplay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AiHttpClientTest {
    private HttpServer server;
    private URI endpoint;
    private final AiHttpClient client = new AiHttpClient();
    private final Duration timeout = Duration.ofSeconds(3);

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(command -> { Thread thread = new Thread(command); thread.setDaemon(true); thread.start(); });
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/chat");
    }

    @AfterEach void stop() { server.stop(0); }

    private void reply(int status, byte[] bytes) {
        server.createContext("/chat", exchange -> {
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
    }

    private void failure(AiHttpClient.Failure expected, Duration limit) {
        assertThatThrownBy(() -> client.send(endpoint, "test-only", "{}", limit))
                .isInstanceOfSatisfying(AiHttpClient.TransportException.class, e -> {
                    assertThat(e.failure()).isEqualTo(expected);
                    assertThat(e.getMessage()).doesNotContain("test-only", "secret");
                    assertThat(e.getCause()).isNull();
                });
    }

    @Test void sendsHeadersAndBodyAndDecodesUtf8() throws Exception {
        server.createContext("/chat", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer test-only");
            assertThat(exchange.getRequestHeaders().getFirst("Content-Type")).isEqualTo("application/json");
            assertThat(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("{\"text\":\"ñ\"}");
            byte[] data = "respuesta ñ".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, data.length);
            try (var out = exchange.getResponseBody()) { out.write(data); }
        });
        server.start();
        assertThat(client.send(endpoint, "test-only", "{\"text\":\"ñ\"}", timeout)).isEqualTo("respuesta ñ");
    }

    @Test void rejectsServerErrorSafely() {
        reply(500, "secret".getBytes(StandardCharsets.UTF_8));
        failure(AiHttpClient.Failure.PROVIDER, timeout);
    }

    @Test void rejectsRedirectWithoutFollowing() {
        AtomicInteger followed = new AtomicInteger();
        server.createContext("/chat", exchange -> {
            exchange.getResponseHeaders().add("Location", "/target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/target", exchange -> { followed.incrementAndGet(); exchange.close(); });
        server.start();
        failure(AiHttpClient.Failure.PROVIDER, timeout);
        assertThat(followed.get()).isZero();
    }

    @Test void acceptsExactBodyLimit() throws Exception {
        byte[] bytes = new byte[AiHttpClient.MAX_RESPONSE_BYTES];
        Arrays.fill(bytes, (byte) 'a');
        reply(200, bytes);
        assertThat(client.send(endpoint, "test-only", "{}", timeout)).hasSize(bytes.length);
    }

    @Test void rejectsBodyAboveLimit() {
        reply(200, new byte[AiHttpClient.MAX_RESPONSE_BYTES + 1]);
        failure(AiHttpClient.Failure.PROVIDER, timeout);
    }

    @Test void rejectsInvalidUtf8() {
        reply(200, new byte[]{(byte) 0xc3, 0x28});
        failure(AiHttpClient.Failure.PROVIDER, timeout);
    }

    @Test void timeoutIncludesBodyAfterHeaders() {
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/chat", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write('a');
                exchange.getResponseBody().flush();
                release.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            long start = System.nanoTime();
            failure(AiHttpClient.Failure.TIMEOUT, Duration.ofMillis(350));
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
        } finally { release.countDown(); }
    }

    @Test void chunkedOverflowCancelsTheStreamingResponse() throws Exception {
        assertStreamingCancellation(AiHttpClient.Failure.PROVIDER, 8192, 2, Duration.ofSeconds(3));
    }

    @Test void chunkedDripTimeoutCancelsTheStreamingResponse() throws Exception {
        assertStreamingCancellation(AiHttpClient.Failure.TIMEOUT, 1, 20, Duration.ofMillis(350));
    }

    private void assertStreamingCancellation(AiHttpClient.Failure expected, int chunkSize, long periodMs, Duration deadline) throws Exception {
        CountDownLatch disconnected = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<String> transferEncoding = new AtomicReference<>();
        var scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task); thread.setDaemon(true); return thread;
        });
        server.createContext("/chat", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 0);
                transferEncoding.set(exchange.getResponseHeaders().getFirst("Transfer-encoding"));
                var out = exchange.getResponseBody();
                byte[] chunk = new byte[chunkSize];
                Arrays.fill(chunk, (byte) 'a');
                scheduler.scheduleAtFixedRate(() -> {
                    try { out.write(chunk); out.flush(); }
                    catch (IOException e) { disconnected.countDown(); }
                }, 0, periodMs, TimeUnit.MILLISECONDS);
                disconnected.await(4, TimeUnit.SECONDS);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { scheduler.shutdownNow(); exchange.close(); finished.countDown(); }
        });
        server.start();
        try {
            failure(expected, deadline);
            assertThat(disconnected.await(3, TimeUnit.SECONDS)).as("servidor observa cierre del cliente").isTrue();
            assertThat(transferEncoding.get()).isEqualToIgnoringCase("chunked");
        } finally {
            scheduler.shutdownNow();
            server.stop(0);
            finished.await(1, TimeUnit.SECONDS);
        }
    }
}
