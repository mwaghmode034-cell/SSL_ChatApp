import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

/** Multi-client chat server. Run: java ChatServer [port] */
public class ChatServer {
    static final Logger LOG = Logger.getLogger("ChatServer");
    static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    static final Path USERS_FILE = Paths.get("users.txt");
    static final Path HISTORY_FILE = Paths.get("history.log");

    // Shared state (thread-safe collections)
    private final Map<String, String> users = new ConcurrentHashMap<>();          // name -> password hash
    private final Map<String, ClientHandler> online = new ConcurrentHashMap<>();  // name -> handler
    private final Map<String, Set<String>> groups = new ConcurrentHashMap<>();    // group -> members
    private final List<Message> history = Collections.synchronizedList(new ArrayList<>());
    private final ExecutorService pool = Executors.newCachedThreadPool();

    /** One stored chat message. type = ALL | PRIVATE | GROUP */
    static class Message {
        final String time, type, from, to, text;
        Message(String time, String type, String from, String to, String text) {
            this.time = time; this.type = type; this.from = from; this.to = to; this.text = text;
        }
        String format() {
            switch (type) {
                case "PRIVATE": return "[" + time + "] (private) " + from + " -> " + to + ": " + text;
                case "GROUP":   return "[" + time + "] [" + to + "] " + from + ": " + text;
                default:        return "[" + time + "] " + from + ": " + text;
            }
        }
        String serialize() { return String.join("\t", time, type, from, to, text); }
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 5000;
        setupLogging();
        new ChatServer().start(port);
    }

    static void setupLogging() throws IOException {
        LOG.setUseParentHandlers(false);
        FileHandler fh = new FileHandler("server.log", true);
        fh.setFormatter(new SimpleFormatter() {
            @Override public String format(LogRecord r) {
                return "[" + LocalDateTime.now().format(TS) + "] " + r.getLevel() + ": " + r.getMessage() + "\n";
            }
        });
        ConsoleHandler ch = new ConsoleHandler();
        ch.setFormatter(fh.getFormatter());
        LOG.addHandler(fh);
        LOG.addHandler(ch);
    }

    void start(int port) throws IOException {
        loadUsers();
        loadHistory();
        try (ServerSocket ss = new ServerSocket(port)) {
            LOG.info("Server started on port " + port);
            while (true) {
                Socket s = ss.accept();
                LOG.info("Connection from " + s.getRemoteSocketAddress());
                pool.execute(new ClientHandler(s));
            }
        }
    }

    // ---------- persistence ----------
    void loadUsers() {
        try {
            if (!Files.exists(USERS_FILE)) return;
            for (String l : Files.readAllLines(USERS_FILE, StandardCharsets.UTF_8)) {
                String[] p = l.split(":", 2);
                if (p.length == 2) users.put(p[0], p[1]);
            }
            LOG.info("Loaded " + users.size() + " users");
        } catch (IOException e) { LOG.warning("Could not load users: " + e.getMessage()); }
    }

    synchronized void saveUser(String name, String hash) {
        try {
            Files.write(USERS_FILE, (name + ":" + hash + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) { LOG.warning("Could not save user: " + e.getMessage()); }
    }

    void loadHistory() {
        try {
            if (!Files.exists(HISTORY_FILE)) return;
            for (String l : Files.readAllLines(HISTORY_FILE, StandardCharsets.UTF_8)) {
                String[] p = l.split("\t", 5);
                if (p.length == 5) history.add(new Message(p[0], p[1], p[2], p[3], p[4]));
            }
            LOG.info("Loaded " + history.size() + " history messages");
        } catch (IOException e) { LOG.warning("Could not load history: " + e.getMessage()); }
    }

    synchronized void storeMessage(Message m) {
        history.add(m);
        try {
            Files.write(HISTORY_FILE, (m.serialize() + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) { LOG.warning("Could not save history: " + e.getMessage()); }
    }

    static String hash(String pw) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(pw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    static String now() { return LocalDateTime.now().format(TS); }

    // ---------- per-client thread ----------
    class ClientHandler implements Runnable {
        private final Socket socket;
        private BufferedReader in;
        private PrintWriter out;
        private String username;

        ClientHandler(Socket s) { this.socket = s; }

        synchronized void send(String msg) { if (out != null) out.println(msg); }

        @Override public void run() {
            try {
                in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                if (!authenticate()) return;
                send("Type /help for commands.");
                broadcastSystem(username + " joined the chat.");
                String line;
                while ((line = in.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    if (line.startsWith("/")) { if (!handleCommand(line)) break; }
                    else publicMessage(line);
                }
            } catch (IOException e) {
                LOG.info("Connection error (" + (username == null ? "unauthenticated" : username) + "): " + e.getMessage());
            } finally {
                cleanup();
            }
        }

        private boolean authenticate() throws IOException {
            send("Welcome! Use: LOGIN <user> <pass>  or  REGISTER <user> <pass>");
            String line;
            while ((line = in.readLine()) != null) {
                String[] p = line.trim().split("\\s+");
                if (p.length != 3) { send("ERR Usage: LOGIN|REGISTER <user> <pass>"); continue; }
                String cmd = p[0].toUpperCase(), name = p[1], pw = p[2];
                if (cmd.equals("REGISTER")) {
                    if (!name.matches("[A-Za-z0-9_]{3,16}")) { send("ERR Username must be 3-16 letters/digits/_"); continue; }
                    if (users.putIfAbsent(name, hash(pw)) != null) { send("ERR Username already taken"); continue; }
                    saveUser(name, users.get(name));
                    LOG.info("Registered new user: " + name);
                    cmd = "LOGIN";
                }
                if (cmd.equals("LOGIN")) {
                    String h = users.get(name);
                    if (h == null || !h.equals(hash(pw))) {
                        LOG.warning("Failed login for '" + name + "'");
                        send("ERR Invalid username or password");
                        continue;
                    }
                    if (online.putIfAbsent(name, this) != null) { send("ERR Already logged in elsewhere"); continue; }
                    username = name;
                    LOG.info("User logged in: " + name);
                    send("OK Logged in as " + name);
                    return true;
                }
                send("ERR Unknown command");
            }
            return false;
        }

        /** @return false if the client wants to disconnect */
        private boolean handleCommand(String line) {
            String[] p = line.split("\\s+", 3);
            switch (p[0].toLowerCase()) {
                case "/help": sendHelp(); break;
                case "/quit": send("Goodbye!"); return false;
                case "/users": send("Online: " + String.join(", ", new TreeSet<>(online.keySet()))); break;
                case "/msg":
                    if (p.length < 3) { send("Usage: /msg <user> <text>"); break; }
                    privateMessage(p[1], p[2]); break;
                case "/g":
                    if (p.length < 3) { send("Usage: /g <group> <text>"); break; }
                    groupMessage(p[1], p[2]); break;
                case "/group": handleGroup(line); break;
                case "/history": sendHistory(p.length > 1 ? p[1] : "20"); break;
                default: send("Unknown command. Type /help");
            }
            return true;
        }

        private void handleGroup(String line) {
            String[] p = line.split("\\s+");
            if (p.length < 2) { send("Usage: /group create|join|leave|list|members <name>"); return; }
            String sub = p[1].toLowerCase();
            if (sub.equals("list")) {
                send("Groups: " + (groups.isEmpty() ? "(none)" : String.join(", ", new TreeSet<>(groups.keySet()))));
                return;
            }
            if (p.length < 3) { send("Usage: /group " + sub + " <name>"); return; }
            String g = p[2];
            switch (sub) {
                case "create":
                    if (!g.matches("[A-Za-z0-9_]{1,20}")) { send("Invalid group name"); return; }
                    if (groups.putIfAbsent(g, ConcurrentHashMap.newKeySet()) != null) { send("Group already exists"); return; }
                    groups.get(g).add(username);
                    LOG.info(username + " created group " + g);
                    send("Group '" + g + "' created and joined."); break;
                case "join": {
                    Set<String> m = groups.get(g);
                    if (m == null) { send("No such group"); return; }
                    m.add(username);
                    LOG.info(username + " joined group " + g);
                    sendToGroup(g, "* " + username + " joined group " + g); break;
                }
                case "leave": {
                    Set<String> m = groups.get(g);
                    if (m == null || !m.remove(username)) { send("You are not in that group"); return; }
                    LOG.info(username + " left group " + g);
                    send("You left '" + g + "'.");
                    sendToGroup(g, "* " + username + " left group " + g); break;
                }
                case "members": {
                    Set<String> m = groups.get(g);
                    send(m == null ? "No such group" : "Members of " + g + ": " + String.join(", ", new TreeSet<>(m))); break;
                }
                default: send("Unknown group command");
            }
        }

        private void publicMessage(String text) {
            Message m = new Message(now(), "ALL", username, "ALL", clean(text));
            storeMessage(m);
            LOG.info("PUBLIC " + username + ": " + m.text);
            for (ClientHandler h : online.values()) h.send(m.format());
        }

        private void privateMessage(String to, String text) {
            if (!users.containsKey(to)) { send("No such user: " + to); return; }
            Message m = new Message(now(), "PRIVATE", username, to, clean(text));
            storeMessage(m);
            LOG.info("PRIVATE " + username + " -> " + to);
            send(m.format());
            ClientHandler h = online.get(to);
            if (h != null && h != this) h.send(m.format());
            else if (h == null) send("(" + to + " is offline; they can see it via /history)");
        }

        private void groupMessage(String g, String text) {
            Set<String> members = groups.get(g);
            if (members == null) { send("No such group"); return; }
            if (!members.contains(username)) { send("Join the group first: /group join " + g); return; }
            Message m = new Message(now(), "GROUP", username, g, clean(text));
            storeMessage(m);
            LOG.info("GROUP[" + g + "] " + username);
            for (String u : members) { ClientHandler h = online.get(u); if (h != null) h.send(m.format()); }
        }

        private void sendToGroup(String g, String text) {
            Set<String> members = groups.get(g);
            if (members == null) return;
            for (String u : members) { ClientHandler h = online.get(u); if (h != null) h.send(text); }
        }

        private void sendHistory(String arg) {
            int n;
            try { n = Math.max(1, Math.min(200, Integer.parseInt(arg))); }
            catch (NumberFormatException e) { n = 20; }
            List<String> visible = new ArrayList<>();
            synchronized (history) {   // required when iterating a synchronizedList
                for (Message m : history) {
                    boolean ok = m.type.equals("ALL")
                            || (m.type.equals("PRIVATE") && (m.from.equals(username) || m.to.equals(username)))
                            || (m.type.equals("GROUP") && groups.getOrDefault(m.to, Collections.emptySet()).contains(username));
                    if (ok) visible.add(m.format());
                }
            }
            send("--- last " + Math.min(n, visible.size()) + " messages ---");
            for (String s : visible.subList(Math.max(0, visible.size() - n), visible.size())) send(s);
            send("--- end of history ---");
        }

        private void sendHelp() {
            send("Commands:\n"
               + "  <text>                      message everyone\n"
               + "  /msg <user> <text>          private message\n"
               + "  /g <group> <text>           group message\n"
               + "  /group create|join|leave|members <name>\n"
               + "  /group list                 list groups\n"
               + "  /users                      who is online\n"
               + "  /history [n]                last n messages (default 20)\n"
               + "  /quit                       disconnect");
        }

        private void broadcastSystem(String text) {
            for (ClientHandler h : online.values()) if (h != this) h.send("* " + text);
        }

        private String clean(String s) { return s.replace('\t', ' ').replace('\n', ' '); }

        private void cleanup() {
            if (username != null && online.remove(username, this)) {
                LOG.info("User disconnected: " + username);
                broadcastSystem(username + " left the chat.");
            }
            try { socket.close(); } catch (IOException ignored) { }
        }
    }
}
