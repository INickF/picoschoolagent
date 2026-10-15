package com.picoschool.agent;

import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Minimal HTTP/1.1 server: one JSON request per connection, JSON response, then close. */
final class HttpServer {
    interface Handler {
        /** Returns the response body; throw {@link AgentException} for a client error. */
        JSONObject handle(String method, String path, JSONObject body) throws Exception;
    }

    private static final String TAG = "PicoAgentHttp";
    private static final int MAX_HEADER = 16 * 1024;
    private static final int MAX_BODY = 64 * 1024;

    private final int port;
    private final Handler handler;
    private final ExecutorService pool = Executors.newFixedThreadPool(6);
    private volatile ServerSocket server;
    private Thread acceptThread;

    HttpServer(int port, Handler handler) {
        this.port = port;
        this.handler = handler;
    }

    void start() {
        acceptThread = new Thread(this::acceptLoop, "agent-http");
        acceptThread.start();
    }

    void stop() {
        ServerSocket s = server;
        server = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        pool.shutdownNow();
    }

    private void acceptLoop() {
        // Retry binding: right after boot or a crash the port can still be held for a moment.
        while (!pool.isShutdown()) {
            try (ServerSocket s = new ServerSocket()) {
                s.setReuseAddress(true);
                s.bind(new java.net.InetSocketAddress(port));
                server = s;
                Log.i(TAG, "listening on " + port);
                while (!pool.isShutdown()) {
                    Socket client = s.accept();
                    pool.execute(() -> serve(client));
                }
            } catch (IOException e) {
                if (pool.isShutdown()) return;
                Log.w(TAG, "server error, restarting", e);
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private void serve(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(5000);
            InputStream in = s.getInputStream();
            String head = readHead(in);
            if (head == null) return;

            String[] lines = head.split("\r\n");
            String[] requestLine = lines[0].split(" ");
            if (requestLine.length < 2) {
                respond(s.getOutputStream(), 400, error("bad_request"));
                return;
            }
            String method = requestLine[0].toUpperCase(Locale.ROOT);
            String path = requestLine[1];
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);

            int length = 0;
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                if (c > 0 && lines[i].substring(0, c).trim().equalsIgnoreCase("Content-Length")) {
                    length = Integer.parseInt(lines[i].substring(c + 1).trim());
                }
            }
            if (length < 0 || length > MAX_BODY) {
                respond(s.getOutputStream(), 413, error("too_large"));
                return;
            }

            if (method.equals("OPTIONS")) {
                respond(s.getOutputStream(), 204, null);
                return;
            }

            JSONObject body = new JSONObject();
            if (length > 0) {
                byte[] buf = new byte[length];
                int read = 0;
                while (read < length) {
                    int n = in.read(buf, read, length - read);
                    if (n < 0) break;
                    read += n;
                }
                String text = new String(buf, 0, read, StandardCharsets.UTF_8).trim();
                if (!text.isEmpty()) body = new JSONObject(text);
            }

            int status = 200;
            JSONObject result;
            try {
                result = handler.handle(method, path, body);
                if (result == null) {
                    status = 404;
                    result = error("not_found");
                }
            } catch (AgentException e) {
                status = 400;
                result = error(e.getMessage());
            } catch (Exception e) {
                Log.e(TAG, "handler failed: " + path, e);
                status = 500;
                result = error("internal");
                result.put("message", String.valueOf(e.getMessage()));
            }
            respond(s.getOutputStream(), status, result);
        } catch (Exception e) {
            Log.w(TAG, "connection failed", e);
        }
    }

    /** Reads up to and including the blank line that ends the headers. */
    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int last4 = 0;
        while (out.size() < MAX_HEADER) {
            int b = in.read();
            if (b < 0) return null;
            out.write(b);
            last4 = (last4 << 8) | b;
            if (last4 == 0x0D0A0D0A) return new String(out.toByteArray(), StandardCharsets.ISO_8859_1).trim();
        }
        return null;
    }

    private static void respond(OutputStream out, int status, JSONObject body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : body.toString().getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + " " + reason(status) + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                + "Access-Control-Allow-Headers: Content-Type\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(bytes);
        out.flush();
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 404: return "Not Found";
            case 413: return "Payload Too Large";
            default: return "Internal Server Error";
        }
    }

    static JSONObject error(String code) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            o.put("error", code);
        } catch (Exception ignored) {
        }
        return o;
    }
}
