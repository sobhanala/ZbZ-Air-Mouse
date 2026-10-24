package io.github.zbz.airmouse;

import android.os.Trace;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

final class AirMouseUdpClient implements Closeable {
    private final AirMouseConfig config;
    private final InetAddress hostAddress;
    private final int port;
    private final String sessionToken;
    private final DatagramSocket socket;
    private final AtomicInteger sequence = new AtomicInteger(1);
    private final Map<Integer, JSONObject> ackResponses = new HashMap<>();
    private final Object ackLock = new Object();
    private final Object socketLock = new Object();
    private volatile boolean closed;
    private volatile CursorListener cursorListener;
    private Thread receiverThread;

    AirMouseUdpClient(String host, int port, String sessionToken, AirMouseConfig config) throws IOException {
        this.config = config;
        this.hostAddress = InetAddress.getByName(host);
        this.port = port;
        this.sessionToken = sessionToken;
        this.socket = new DatagramSocket();
    }

    ConnectionResult hello() throws IOException, JSONException {
        Trace.beginSection("airmouse.udp.hello");
        try {
            int seq = nextSeq();
            JSONObject packet = basePacket(seq, AirMouseProtocol.TYPE_HELLO);
            if (sessionToken != null && !sessionToken.isEmpty()) {
                packet.put(AirMouseProtocol.KEY_SESSION_TOKEN, sessionToken);
            }
            synchronized (socketLock) {
                send(packet);
                socket.setSoTimeout(config.helloTimeoutMs);
                JSONObject response = receiveJson();
                if (!response.optBoolean(AirMouseProtocol.KEY_ACK, false)
                        || !AirMouseProtocol.TYPE_HELLO_ACK.equals(response.optString(AirMouseProtocol.KEY_TYPE))
                        || response.optInt(AirMouseProtocol.KEY_SEQ, -1) != seq
                        || !AirMouseProtocol.STATUS_OK.equals(response.optString(AirMouseProtocol.KEY_STATUS))) {
                    throw new IOException("Laptop rejected hello.");
                }
                return new ConnectionResult(
                        response.optInt(AirMouseProtocol.KEY_SCREEN_WIDTH, 0),
                        response.optInt(AirMouseProtocol.KEY_SCREEN_HEIGHT, 0),
                        response.optDouble(AirMouseProtocol.KEY_SERVER_SENSITIVITY, 1.0)
                );
            }
        } finally {
            Trace.endSection();
        }
    }

    void setCursorListener(CursorListener listener) {
        this.cursorListener = listener;
    }

    void startReceiver(ServerDisconnectListener disconnectListener) {
        if (receiverThread != null) {
            return;
        }
        receiverThread = new Thread(() -> receiveLoop(disconnectListener), "airmouse-udp-receiver");
        receiverThread.start();
    }

    void sendMove(float dx, float dy) throws IOException, JSONException {
        Trace.beginSection("airmouse.udp.send_move");
        try {
            JSONObject packet = basePacket(nextSeq(), AirMouseProtocol.TYPE_MOVE);
            packet.put(AirMouseProtocol.KEY_DX, dx);
            packet.put(AirMouseProtocol.KEY_DY, dy);
            synchronized (socketLock) {
                send(packet);
            }
        } finally {
            Trace.endSection();
        }
    }

    void sendClick(String button) throws IOException, JSONException {
        Trace.beginSection("airmouse.udp.send_click_ack");
        try {
            JSONObject packet = basePacket(nextSeq(), AirMouseProtocol.TYPE_CLICK);
            packet.put(AirMouseProtocol.KEY_BUTTON, button);
            sendWithAck(packet);
        } finally {
            Trace.endSection();
        }
    }

    void sendScroll(int amount) throws IOException, JSONException {
        Trace.beginSection("airmouse.udp.send_scroll_ack");
        try {
            JSONObject packet = basePacket(nextSeq(), AirMouseProtocol.TYPE_SCROLL);
            packet.put(AirMouseProtocol.KEY_AMOUNT, amount);
            sendWithAck(packet);
        } finally {
            Trace.endSection();
        }
    }

    void sendDisconnect() {
        try {
            JSONObject packet = basePacket(nextSeq(), AirMouseProtocol.TYPE_DISCONNECT);
            synchronized (socketLock) {
                send(packet);
            }
        } catch (IOException | JSONException ignored) {
        }
    }

    private void sendWithAck(JSONObject packet) throws IOException, JSONException {
        Trace.beginSection("airmouse.udp.wait_for_ack");
        try {
            int seq = packet.getInt(AirMouseProtocol.KEY_SEQ);
            for (int attempt = 0; attempt < config.ackAttempts; attempt++) {
                synchronized (socketLock) {
                    send(packet);
                }
                if (waitForAck(seq)) {
                    return;
                }
            }
            throw new SocketTimeoutException("No ACK from laptop.");
        } finally {
            Trace.endSection();
        }
    }

    private JSONObject basePacket(int seq, String type) throws JSONException {
        JSONObject packet = new JSONObject();
        packet.put(AirMouseProtocol.KEY_SEQ, seq);
        packet.put(AirMouseProtocol.KEY_TYPE, type);
        packet.put(AirMouseProtocol.KEY_TIMESTAMP, System.currentTimeMillis());
        return packet;
    }

    private int nextSeq() {
        return sequence.getAndIncrement();
    }

    private void send(JSONObject packet) throws IOException {
        Trace.beginSection("airmouse.udp.socket_send");
        try {
            byte[] bytes = packet.toString().getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(bytes, bytes.length, hostAddress, port));
        } finally {
            Trace.endSection();
        }
    }

    private JSONObject receiveJson() throws IOException, JSONException {
        Trace.beginSection("airmouse.udp.receive");
        try {
            byte[] buffer = new byte[config.udpBufferSize];
            DatagramPacket response = new DatagramPacket(buffer, buffer.length);
            socket.receive(response);
            String json = new String(response.getData(), response.getOffset(), response.getLength(), StandardCharsets.UTF_8);
            return new JSONObject(json);
        } finally {
            Trace.endSection();
        }
    }

    private void receiveLoop(ServerDisconnectListener disconnectListener) {
        try {
            socket.setSoTimeout(config.receiverTimeoutMs);
            while (!closed) {
                try {
                    JSONObject packet = receiveJson();
                    handleIncomingPacket(packet, disconnectListener);
                } catch (SocketTimeoutException ignored) {
                } catch (JSONException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    private void handleIncomingPacket(JSONObject packet, ServerDisconnectListener disconnectListener) {
        if (packet.optBoolean(AirMouseProtocol.KEY_ACK, false)) {
            int seq = packet.optInt(AirMouseProtocol.KEY_SEQ, -1);
            synchronized (ackLock) {
                ackResponses.put(seq, packet);
                ackLock.notifyAll();
            }
            return;
        }
        String type = packet.optString(AirMouseProtocol.KEY_TYPE);
        if (AirMouseProtocol.TYPE_CURSOR.equals(type)) {
            CursorListener listener = cursorListener;
            if (listener != null) {
                listener.onCursor(
                        packet.optInt(AirMouseProtocol.KEY_X, 0),
                        packet.optInt(AirMouseProtocol.KEY_Y, 0)
                );
            }
            return;
        }
        if (AirMouseProtocol.TYPE_DISCONNECT.equals(type)) {
            disconnectListener.onServerDisconnect();
        }
    }

    private boolean waitForAck(int seq) throws SocketTimeoutException {
        long deadline = System.currentTimeMillis() + config.ackTimeoutMs;
        synchronized (ackLock) {
            while (System.currentTimeMillis() < deadline) {
                JSONObject response = ackResponses.remove(seq);
                if (response != null
                        && AirMouseProtocol.STATUS_OK.equals(response.optString(AirMouseProtocol.KEY_STATUS))) {
                    return true;
                }
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    break;
                }
                try {
                    ackLock.wait(remaining);
                } catch (InterruptedException exc) {
                    Thread.currentThread().interrupt();
                    throw new SocketTimeoutException("Interrupted while waiting for ACK.");
                }
            }
        }
        return false;
    }

    @Override
    public void close() {
        closed = true;
        socket.close();
    }

    interface ServerDisconnectListener {
        void onServerDisconnect();
    }

    interface CursorListener {
        void onCursor(int x, int y);
    }

    static final class ConnectionResult {
        final int screenWidth;
        final int screenHeight;
        final double serverSensitivity;

        ConnectionResult(int screenWidth, int screenHeight, double serverSensitivity) {
            this.screenWidth = screenWidth;
            this.screenHeight = screenHeight;
            this.serverSensitivity = serverSensitivity;
        }
    }
}
