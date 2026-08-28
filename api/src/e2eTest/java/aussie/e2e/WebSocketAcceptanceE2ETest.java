package aussie.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
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

            socket.sendBinary(ByteBuffer.wrap(new byte[] {0x41, 0x42}), true).join();
            assertEquals("AB", nextMessage(listener).get("original").asText());

            socket.sendPing(ByteBuffer.wrap(new byte[] {0x01})).join();
            assertTrue(listener.pong.await(MESSAGE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));

            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
            awaitNormalClose(listener);
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

    private static URI websocketUri(URI gateway, String path) {
        final var scheme = "https".equalsIgnoreCase(gateway.getScheme()) ? "wss" : "ws";
        return URI.create(scheme + "://" + gateway.getAuthority() + path);
    }

    private static JsonNode nextMessage(RecordingListener listener) throws Exception {
        final var message = listener.messages.poll(MESSAGE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertNotNull(message, "timed out waiting for WebSocket message");
        return JSON.readTree(message);
    }

    private static void awaitNormalClose(RecordingListener listener) throws InterruptedException {
        assertTrue(listener.closed.await(MESSAGE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
        assertNull(listener.error, "WebSocket listener failed");
        assertEquals(WebSocket.NORMAL_CLOSURE, listener.closeCode);
    }

    private static final class RecordingListener implements WebSocket.Listener {
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final StringBuilder text = new StringBuilder();
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
