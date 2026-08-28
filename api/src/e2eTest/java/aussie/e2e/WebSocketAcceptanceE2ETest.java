package aussie.e2e;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import aussie.e2e.support.SuiteContext;

@DisplayName("Packaged WebSocket acceptance")
final class WebSocketAcceptanceE2ETest {

    private static final Duration MESSAGE_TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("preserves data frames and closes the backend on client close")
    void preservesFramesAndClosePropagation() throws Exception {
        final var context = SuiteContext.get();
        final var listener = new RecordingListener();
        final var client = HttpClient.newHttpClient();
        final var endpoint = websocketUri(context.gatewayBaseUri(), "/demo-service/ws/echo");
        final var socket = client.newWebSocketBuilder()
                .header("Origin", "http://localhost:3000")
                .buildAsync(endpoint, listener)
                .join();

        try {
            assertEquals("connected", nextMessage(listener).get("type").asText());

            socket.sendText("hel", false).join();
            socket.sendText("lo", true).join();
            assertEquals("hello", nextMessage(listener).get("original").asText());

            socket.sendBinary(ByteBuffer.wrap(new byte[] {0x41}), false).join();
            socket.sendBinary(ByteBuffer.wrap(new byte[] {0x42}), true).join();
            assertArrayEquals(new byte[] {0x41, 0x42}, nextBinary(listener));

            socket.sendPing(ByteBuffer.wrap(new byte[] {0x01})).join();
            assertTrue(listener.pong.await(MESSAGE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));

            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
            awaitNormalClose(listener);
        } finally {
            socket.abort();
        }
    }

    @Test
    @DisplayName("rejects a browser handshake from an unapproved origin")
    void rejectsUnapprovedOrigin() {
        final var context = SuiteContext.get();
        final var endpoint = websocketUri(context.gatewayBaseUri(), "/demo-service/ws/echo");

        final var failure = assertThrows(CompletionException.class, () -> HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .header("Origin", "https://evil.example")
                .buildAsync(endpoint, new RecordingListener())
                .join());
        final var handshakeFailure = assertInstanceOf(WebSocketHandshakeException.class, failure.getCause());
        assertEquals(403, handshakeFailure.getResponse().statusCode());
    }

    @Test
    @DisplayName("closes oversized logical messages with 1009")
    void rejectsOversizedMessage() throws Exception {
        final var context = SuiteContext.get();
        final var listener = new RecordingListener();
        final var socket = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .header("Origin", "http://localhost:3000")
                .buildAsync(websocketUri(context.gatewayBaseUri(), "/demo-service/ws/echo"), listener)
                .join();

        try {
            assertEquals("connected", nextMessage(listener).get("type").asText());
            socket.sendText("x".repeat(1_048_577), true).join();
            assertTrue(listener.closed.await(MESSAGE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            assertEquals(1009, listener.closeCode);
        } finally {
            socket.abort();
        }
    }

    @Test
    @DisplayName("supports repeated connect and disconnect")
    void supportsRepeatedConnectAndDisconnect() throws Exception {
        final var context = SuiteContext.get();
        final var endpoint = websocketUri(context.gatewayBaseUri(), "/demo-service/ws/echo");
        final var client = HttpClient.newHttpClient();

        for (var attempt = 0; attempt < 3; attempt++) {
            final var listener = new RecordingListener();
            final var socket = client.newWebSocketBuilder()
                    .header("Origin", "http://localhost:3000")
                    .buildAsync(endpoint, listener)
                    .join();
            try {
                assertEquals("connected", nextMessage(listener).get("type").asText());
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
                awaitNormalClose(listener);
            } finally {
                socket.abort();
            }
        }
    }

    @Test
    @DisplayName("rejects an unsupported subprotocol during the HTTP upgrade")
    void rejectsUnsupportedSubprotocol() {
        final var context = SuiteContext.get();
        final var endpoint = websocketUri(context.gatewayBaseUri(), "/demo-service/ws/echo");

        final var failure = assertThrows(CompletionException.class, () -> HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .subprotocols("unsupported.v1")
                .header("Origin", "http://localhost:3000")
                .buildAsync(endpoint, new RecordingListener())
                .join());
        final var handshakeFailure = assertInstanceOf(WebSocketHandshakeException.class, failure.getCause());
        assertEquals(400, handshakeFailure.getResponse().statusCode());
    }

    private static URI websocketUri(URI gateway, String path) {
        final var scheme = "https".equalsIgnoreCase(gateway.getScheme()) ? "wss" : "ws";
        return URI.create(scheme + "://" + gateway.getAuthority() + path);
    }

    private static JsonNode nextMessage(RecordingListener listener) throws Exception {
        final var message = listener.messages.poll(MESSAGE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertNotNull(message, "timed out waiting for WebSocket message");
        return JSON.readTree(message);
    }

    private static byte[] nextBinary(RecordingListener listener) throws Exception {
        final var message = listener.binaryMessages.poll(MESSAGE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertNotNull(message, "timed out waiting for binary WebSocket message");
        return message;
    }

    private static void awaitNormalClose(RecordingListener listener) throws InterruptedException {
        assertTrue(listener.closed.await(MESSAGE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
        assertNull(listener.error, "WebSocket listener failed");
        assertEquals(WebSocket.NORMAL_CLOSURE, listener.closeCode);
    }

    private static final class RecordingListener implements WebSocket.Listener {
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final BlockingQueue<byte[]> binaryMessages = new LinkedBlockingQueue<>();
        private final StringBuilder text = new StringBuilder();
        private final ByteArrayOutputStream binary = new ByteArrayOutputStream();
        private final CountDownLatch pong = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private volatile int closeCode;
        private volatile Throwable error;

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                messages.add(text.toString());
                text.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            final var bytes = new byte[data.remaining()];
            data.get(bytes);
            binary.writeBytes(bytes);
            if (last) {
                binaryMessages.add(binary.toByteArray());
                binary.reset();
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            pong.countDown();
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closeCode = statusCode;
            closed.countDown();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            this.error = error;
            closed.countDown();
        }
    }
}
