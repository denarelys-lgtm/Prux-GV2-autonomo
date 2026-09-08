package com.example.detectcamera;

import android.util.Base64;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** Minimal authenticated WebSocket server dedicated to H.264 screen video. */
public final class ScreenWebSocketServer {
    private static final String TAG = "ScreenWebSocket";
    private final int port;
    private final String token = UUID.randomUUID().toString().replace("-", "");
    private final CopyOnWriteArrayList<Client> clients = new CopyOnWriteArrayList<>();
    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile byte[] config;
    private volatile int width;
    private volatile int height;

    public ScreenWebSocketServer(int port) { this.port = port; }
    public String getToken() { return token; }

    public synchronized void start() throws IOException {
        if (running) return;
        serverSocket = new ServerSocket(port);
        serverSocket.setReuseAddress(true);
        running = true;
        acceptThread = new Thread(() -> {
            while (running) {
                try {
                    Socket s = serverSocket.accept();
                    s.setTcpNoDelay(true);
                    s.setKeepAlive(true);
                    new Thread(() -> acceptClient(s), "ScreenWS-Client").start();
                } catch (IOException e) {
                    if (running) Log.w(TAG, "Accept error", e);
                }
            }
        }, "ScreenWS-Accept");
        acceptThread.start();
    }

    public synchronized void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Throwable ignored) {}
        for (Client c : clients) c.close();
        clients.clear();
        config = null;
    }

    public void setVideoConfig(byte[] codecConfig, int w, int h) {
        if (codecConfig == null || codecConfig.length == 0) return;
        config = codecConfig.clone(); width = w; height = h;
        for (Client c : clients) c.sendConfig();
    }

    public void clearVideoConfig() { config = null; width = 0; height = 0; }

    public void publishFrame(byte[] data, boolean keyFrame, long timestampUs) {
        if (!running || data == null || data.length == 0) return;
        for (Client c : clients) c.offerFrame(data, keyFrame, timestampUs);
    }

    private void acceptClient(Socket socket) {
        Client client = null;
        try {
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream(), 64 * 1024);
            BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream(), 64 * 1024);
            String request = readHttpHeaders(in);
            String key = header(request, "Sec-WebSocket-Key");
            String supplied = queryToken(request);
            if (key == null || !token.equals(supplied)) {
                writeHttp(out, "HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                socket.close(); return;
            }
            String accept = Base64.encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key.trim() + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes("ISO-8859-1")), Base64.NO_WRAP);
            writeHttp(out, "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n");
            client = new Client(socket, in, out);
            clients.add(client);
            client.start();
        } catch (Throwable t) {
            try { socket.close(); } catch (Throwable ignored) {}
            if (client != null) clients.remove(client);
        }
    }

    private String readHttpHeaders(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int prev = -1, cur;
        while (b.size() < 16384 && (cur = in.read()) != -1) {
            b.write(cur);
            if (prev == '\r' && cur == '\n') {
                byte[] x = b.toByteArray(); int n = x.length;
                if (n >= 4 && x[n-4] == '\r' && x[n-3] == '\n') break;
            }
            prev = cur;
        }
        return b.toString("ISO-8859-1");
    }

    private static String header(String request, String name) {
        for (String line : request.split("\\r\\n")) {
            int p = line.indexOf(':');
            if (p > 0 && name.equalsIgnoreCase(line.substring(0, p).trim())) return line.substring(p + 1).trim();
        }
        return null;
    }

    private static String queryToken(String request) {
        String first = request.split("\\r\\n", 2)[0];
        int a = first.indexOf(' '), b = first.indexOf(' ', a + 1);
        if (a < 0 || b < 0) return null;
        String path = first.substring(a + 1, b);
        int q = path.indexOf("token=");
        if (q < 0) return null;
        String t = path.substring(q + 6); int amp = t.indexOf('&');
        return amp >= 0 ? t.substring(0, amp) : t;
    }

    private static void writeHttp(OutputStream out, String s) throws IOException { out.write(s.getBytes("ISO-8859-1")); out.flush(); }

    private final class Client {
        final Socket socket; final InputStream in; final OutputStream out;
        final Object lock = new Object();
        volatile boolean alive = true;
        byte[] pending; boolean pendingKey; long pendingTs;
        Thread writer;

        Client(Socket s, InputStream i, OutputStream o) { socket=s; in=i; out=o; }
        void start() {
            writer = new Thread(this::writeLoop, "ScreenWS-Writer"); writer.start();
            new Thread(this::readLoop, "ScreenWS-Reader").start();
            sendConfig();
        }
        void sendConfig() {
            byte[] c = config; if (!alive || c == null) return;
            String json = "{\"type\":\"config\",\"width\":" + width + ",\"height\":" + height +
                    ",\"codec\":\"avc1.42C028\",\"config\":\"" +
                    Base64.encodeToString(c, Base64.NO_WRAP) + "\"}";
            try { sendText(json); } catch (Throwable t) { close(); }
        }
        void offerFrame(byte[] data, boolean key, long ts) {
            synchronized (lock) {
                if (!alive) return;
                pending = data; pendingKey = key; pendingTs = ts;
                lock.notifyAll();
            }
        }
        void writeLoop() {
            try {
                while (alive) {
                    byte[] d; boolean k; long ts;
                    synchronized (lock) {
                        while (alive && pending == null) lock.wait(1000L);
                        if (!alive) break;
                        d = pending; k = pendingKey; ts = pendingTs; pending = null;
                    }
                    byte[] packet = new byte[9 + d.length]; packet[0] = (byte)(k ? 1 : 0);
                    ByteBuffer.wrap(packet, 1, 8).order(ByteOrder.BIG_ENDIAN).putLong(ts);
                    System.arraycopy(d, 0, packet, 9, d.length);
                    writeFrame((byte)0x2, packet);
                }
            } catch (Throwable ignored) { close(); }
        }
        void readLoop() {
            try {
                while (alive) {
                    int b1 = in.read(); if (b1 < 0) break;
                    int b2 = in.read(); if (b2 < 0) break;
                    int opcode = b1 & 0x0F; boolean masked = (b2 & 0x80) != 0;
                    long len = b2 & 0x7F;
                    if (len == 126) len = ((in.read() & 255) << 8) | (in.read() & 255);
                    else if (len == 127) { len=0; for(int i=0;i<8;i++) len=(len<<8)|(in.read()&255); }
                    if (len > 1_048_576) break;
                    byte[] mask = masked ? readFully(4) : null;
                    byte[] payload = readFully((int)len);
                    if (mask != null) for(int i=0;i<payload.length;i++) payload[i]=(byte)(payload[i]^mask[i&3]);
                    if (opcode == 0x8) { sendClose(); break; }
                    if (opcode == 0x9) writeFrame((byte)0xA, payload);
                }
            } catch (Throwable ignored) {} finally { close(); }
        }
        byte[] readFully(int n) throws IOException { byte[] x=new byte[n]; int p=0; while(p<n){int r=in.read(x,p,n-p); if(r<0) throw new IOException(); p+=r;} return x; }
        void sendText(String s) throws IOException { writeFrame((byte)0x1, s.getBytes("UTF-8")); }
        void sendClose() { try { writeFrame((byte)0x8, new byte[0]); } catch(Throwable ignored){} }
        void writeFrame(byte opcode, byte[] payload) throws IOException {
            synchronized(out) {
                out.write(0x80 | opcode); int n=payload.length;
                if(n<126) out.write(n); else if(n<=65535){out.write(126); out.write((n>>>8)&255); out.write(n&255);} else {out.write(127); for(int i=7;i>=0;i--) out.write((n >>> (8*i)) & 255);}
                out.write(payload); out.flush();
            }
        }
        void close() { if(!alive)return; alive=false; clients.remove(this); synchronized(lock){lock.notifyAll();} try{socket.close();}catch(Throwable ignored){} }
    }
}
