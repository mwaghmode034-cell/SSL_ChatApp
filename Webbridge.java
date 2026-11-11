import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Web front door for ChatServer. Browsers cannot open raw TCP sockets, so this
 * small HTTP server (JDK built-in, no libraries) serves index.html and relays
 * each browser session to ChatServer using the normal text protocol.
 *
 * Run: java WebBridge [webPort=8080] [chatHost=localhost] [chatPort=5000]
 * Keep index.html in the folder you start it from.
 */
public class WebBridge {
    static String chatHost = "localhost";
    static int chatPort = 5000;
    static final String CLOSED = "\u0000closed";
    static final Map<String, Session> sessions = new ConcurrentHashMap<>();

    static class Session {
        final Socket socket;
        final PrintWriter out;
        final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        Session(Socket s, PrintWriter o) { socket = s; out = o; }
    }

    public static void main(String[] a) throws Exception {
        int web = a.length > 0 ? Integer.parseInt(a[0]) : 8080;
        if (a.length > 1) chatHost = a[1];
        if (a.length > 2) chatPort = Integer.parseInt(a[2]);

        HttpServer srv = HttpServer.create(new InetSocketAddress(web), 0);
        srv.setExecutor(Executors.newCachedThreadPool());

        srv.createContext("/", ex -> {
            if (!ex.getRequestURI().getPath().equals("/")) { reply(ex, 404, "Not found"); return; }
            Path p = Paths.get("index.html");
            if (!Files.exists(p)) { reply(ex, 404, "index.html must be in the folder where WebBridge was started"); return; }
            byte[] b = Files.readAllBytes(p);
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream o = ex.getResponseBody()) { o.write(b); }
        });

        // Authenticate against ChatServer; on success return a session id.
        srv.createContext("/api/login", ex -> {
            Map<String, String> f = form(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String mode = "register".equals(f.get("mode")) ? "REGISTER" : "LOGIN";
            String user = f.getOrDefault("user", ""), pass = f.getOrDefault("pass", "");
            if (user.isEmpty() || pass.isEmpty() || user.contains(" ") || pass.contains(" ")) {
                reply(ex, 400, "Enter a username and password without spaces."); return;
            }
            Socket s = null;
            try {
                s = new Socket();
                s.connect(new InetSocketAddress(chatHost, chatPort), 3000);
                s.setSoTimeout(5000);
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
                in.readLine();                                    // welcome line
                out.println(mode + " " + user + " " + pass);
                String resp = in.readLine();
                if (resp == null || !resp.startsWith("OK")) {
                    s.close();
                    reply(ex, 400, resp == null ? "Server closed the connection." : resp.replaceFirst("^ERR ", ""));
                    return;
                }
                s.setSoTimeout(0);
                Session se = new Session(s, out);
                String sid = UUID.randomUUID().toString();
                sessions.put(sid, se);
                Thread t = new Thread(() -> {                     // server -> queue
                    try { String l; while ((l = in.readLine()) != null) se.queue.add(l); }
                    catch (IOException ignored) { }
                    se.queue.add(CLOSED);
                });
                t.setDaemon(true);
                t.start();
                reply(ex, 200, sid);
            } catch (IOException e) {
                try { if (s != null) s.close(); } catch (IOException ignored) { }
                reply(ex, 502, "Cannot reach ChatServer at " + chatHost + ":" + chatPort + ". Is it running?");
            }
        });

        // Server-Sent Events: pushes every server line to the browser.
        srv.createContext("/api/events", ex -> {
            Session se = sessions.get(form(ex.getRequestURI().getRawQuery()).get("sid"));
            if (se == null) { reply(ex, 404, "No such session"); return; }
            ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            ex.sendResponseHeaders(200, 0);
            try (OutputStream o = ex.getResponseBody()) {
                while (true) {
                    String l = se.queue.poll(15, TimeUnit.SECONDS);
                    String chunk = l == null ? ": ping\n\n"
                            : l.equals(CLOSED) ? "event: closed\ndata: bye\n\n"
                            : "data: " + l + "\n\n";
                    o.write(chunk.getBytes(StandardCharsets.UTF_8));
                    o.flush();
                    if (CLOSED.equals(l)) break;
                }
            } catch (IOException | InterruptedException ignored) {
            } finally {
                close(se);
            }
        });

        // Browser -> server: one line of chat text or a command.
        srv.createContext("/api/send", ex -> {
            Session se = sessions.get(form(ex.getRequestURI().getRawQuery()).get("sid"));
            String line = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
                    .replace('\n', ' ').replace('\r', ' ');
            if (se == null) { reply(ex, 404, "No such session"); return; }
            se.out.println(line);
            reply(ex, 200, "ok");
        });

        srv.createContext("/api/logout", ex -> {
            Session se = sessions.get(form(ex.getRequestURI().getRawQuery()).get("sid"));
            if (se != null) close(se);
            reply(ex, 200, "ok");
        });

        srv.start();
        System.out.println("Web chat at http://localhost:" + web + "  (ChatServer expected at " + chatHost + ":" + chatPort + ")");
    }

    static void close(Session se) {
        try { se.out.println("/quit"); se.socket.close(); } catch (Exception ignored) { }
        sessions.values().remove(se);
    }

    static void reply(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(b); }
    }

    static Map<String, String> form(String s) throws UnsupportedEncodingException {
        Map<String, String> m = new HashMap<>();
        if (s != null) for (String kv : s.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) m.put(URLDecoder.decode(kv.substring(0, i), "UTF-8"), URLDecoder.decode(kv.substring(i + 1), "UTF-8"));
        }
        return m;
    }
}
