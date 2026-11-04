package dev.incidentreplay;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class AiHttpClient {
    static final int MAX_RESPONSE_BYTES = 128 * 1024;
    enum Failure { PROVIDER, TIMEOUT }

    static final class TransportException extends Exception {
        private final Failure failure;

        TransportException(Failure failure) {
            super(failure == Failure.TIMEOUT ? "Tiempo de espera agotado." : "Error del proveedor.");
            this.failure = failure;
        }

        Failure failure() { return failure; }
    }

    private final HttpClient client;

    AiHttpClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    AiHttpClient(HttpClient client) {
        this.client = client;
    }

    String send(URI endpoint, String apiKey, String json, Duration timeout) throws TransportException {
        LimitedBody subscriber = new LimitedBody();
        CompletableFuture<HttpResponse<byte[]>> future = null;
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build();
            future = client.sendAsync(request, info -> subscriber);
            HttpResponse<byte[]> response = future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            if (response.statusCode() != 200) throw new TransportException(Failure.PROVIDER);
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(response.body())).toString();
        } catch (TimeoutException e) {
            throw new TransportException(Failure.TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransportException(Failure.PROVIDER);
        } catch (ExecutionException e) {
            throw new TransportException(e.getCause() instanceof java.net.http.HttpTimeoutException
                    ? Failure.TIMEOUT : Failure.PROVIDER);
        } catch (RuntimeException | java.nio.charset.CharacterCodingException e) {
            throw new TransportException(Failure.PROVIDER);
        } finally {
            subscriber.cancel();
            if (future != null && !future.isDone()) future.cancel(true);
        }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        private boolean cancelled;

        @Override public CompletionStage<byte[]> getBody() { return body; }

        @Override public synchronized void onSubscribe(Flow.Subscription subscription) {
            if (cancelled || this.subscription != null) subscription.cancel();
            else {
                this.subscription = subscription;
                subscription.request(1);
            }
        }

        @Override public synchronized void onNext(List<ByteBuffer> buffers) {
            if (cancelled) return;
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_RESPONSE_BYTES - bytes.size()) {
                    cancel();
                    body.completeExceptionally(new IllegalStateException());
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        @Override public synchronized void onError(Throwable error) { body.completeExceptionally(error); }
        @Override public synchronized void onComplete() { body.complete(bytes.toByteArray()); }

        synchronized void cancel() {
            cancelled = true;
            if (subscription != null) subscription.cancel();
        }
    }
}
