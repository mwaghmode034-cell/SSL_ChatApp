import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Multi-client chat server. Run: java ChatServer [port] */
public class ChatServer {
    static final Logger LOG = Logger.getLogger("ChatServer");
    static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    static final Path USERS_FILE = Paths.get("users.txt");
    static final Path HISTORY_FILE = Paths.get("history.log");
    static final Path GROUPS_FILE = Paths.get("groups.txt");
    private static final int PASSWORD_ITERATIONS = 210_000;
    private static final int PASSWORD_SALT_BYTES = 16;
    private static final int PASSWORD_HASH_BYTES = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

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
        loadGroups();
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

    void loadGroups() {
        try {
            if (!Files.exists(GROUPS_FILE)) return;
            for (String line : Files.readAllLines(GROUPS_FILE, StandardCharsets.UTF_8)) {
                String[] parts = line.split(":", 2);
                if (parts.length != 2 || !parts[0].matches("[A-Za-z0-9_]{1,20}")) {
                    LOG.warning("Skipping malformed group record");
                    continue;
                }
                Set<String> members = groups.computeIfAbsent(parts[0], k -> ConcurrentHashMap.newKeySet());
                if (!parts[1].isEmpty()) {
                    if (parts[1].matches("[A-Za-z0-9_]{3,16}") && users.containsKey(parts[1])) {
                        members.add(parts[1]);
                    } else {
                        LOG.warning("Skipping invalid or unknown member in group " + parts[0]);
                    }
                }
            }
            LOG.info("Loaded " + groups.size() + " groups");
        } catch (IOException e) {
            LOG.warning("Could not load groups: " + e.getMessage());
        }
    }

    synchronized void saveGroups() throws IOException {
        List<String> lines = new ArrayList<>();
        for (String group : new TreeSet<>(groups.keySet())) {
            Set<String> members = groups.get(group);
            if (members == null || members.isEmpty()) {
                lines.add(group + ":");
            } else {
                for (String member : new TreeSet<>(members)) lines.add(group + ":" + member);
            }
        }

        Path tempFile = GROUPS_FILE.resolveSibling(GROUPS_FILE.getFileName() + ".tmp");
        try {
            Files.write(tempFile, lines, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(tempFile, GROUPS_FILE, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile, GROUPS_FILE, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
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

    static String hashPassword(String password) {
        byte[] salt = new byte[PASSWORD_SALT_BYTES];
        SECURE_RANDOM.nextBytes(salt);
        byte[] hash = derivePasswordHash(password.toCharArray(), salt, PASSWORD_ITERATIONS);
        return "pbkdf2$" + PASSWORD_ITERATIONS + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(hash);
    }

    static boolean verifyPassword(String password, String stored) {
        if (stored.startsWith("pbkdf2$")) {
            String[] parts = stored.split("\\$", -1);
            if (parts.length != 4) return false;
            try {
                int iterations = Integer.parseInt(parts[1]);
                if (iterations < 100_000 || iterations > 1_000_000) return false;
                byte[] salt = Base64.getDecoder().decode(parts[2]);
                byte[] expected = Base64.getDecoder().decode(parts[3]);
                if (salt.length != PASSWORD_SALT_BYTES || expected.length != PASSWORD_HASH_BYTES) return false;
                byte[] actual = derivePasswordHash(password.toCharArray(), salt, iterations);
                return MessageDigest.isEqual(expected, actual);
            } catch (IllegalArgumentException e) {
                return false;
            }
        }

        if (!stored.matches("[0-9a-fA-F]{64}")) return false;
        try {
            byte[] actual = MessageDigest.getInstance("SHA-256")
                    .digest(password.getBytes(StandardCharsets.UTF_8));
            byte[] expected = new byte[actual.length];
            for (int i = 0; i < expected.length; i++) {
                expected[i] = (byte) Integer.parseInt(stored.substring(i * 2, i * 2 + 2), 16);
            }
            return MessageDigest.isEqual(expected, actual);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Password verification is unavailable", e);
        }
    }

    static boolean isLegacyPasswordHash(String stored) {
        return stored.matches("[0-9a-fA-F]{64}");
    }

    private static byte[] derivePasswordHash(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, PASSWORD_HASH_BYTES * 8);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 password hashing is unavailable", e);
        } finally {
            spec.clearPassword();
            Arrays.fill(password, '\0');
        }
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
                boolean justRegistered = false;
                if (cmd.equals("REGISTER")) {
                    if (!name.matches("[A-Za-z0-9_]{3,16}")) { send("ERR Username must be 3-16 letters/digits/_"); continue; }
                    if (users.putIfAbsent(name, hashPassword(pw)) != null) { send("ERR Username already taken"); continue; }
                    saveUser(name, users.get(name));
                    LOG.info("Registered new user: " + name);
                    justRegistered = true;
                    cmd = "LOGIN";
                }
                if (cmd.equals("LOGIN")) {
                    String h = users.get(name);
                    if (h == null || (!justRegistered && !verifyPassword(pw, h))) {
                        LOG.warning("Failed login for '" + name + "'");
                        send("ERR Invalid username or password");
                        continue;
                    }
                    if (isLegacyPasswordHash(h)) {
                        String upgraded = hashPassword(pw);
                        if (users.replace(name, h, upgraded)) saveUser(name, upgraded);
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
                    handleGroupMessage(line); break;
                case "/group": handleGroup(line); break;
                case "/history": sendHistory(p.length > 1 ? p[1] : "20"); break;
                default: send("Unknown command. Type /help");
            }
            return true;
        }

        private void handleGroup(String line) {
            String[] p = line.trim().split("\\s+", 3);
            if (p.length < 2) { send("Usage: /group create|join|leave|list|members <name>"); return; }
            String sub = p[1].toLowerCase();
            if (sub.equals("list")) {
                send("Groups: " + (groups.isEmpty() ? "(none)" : String.join(", ", new TreeSet<>(groups.keySet()))));
                return;
            }
            if (p.length < 3) { send("Usage: /group " + sub + " <name>"); return; }
            String g = p[2].trim().replaceAll("\\s+", " ");
            switch (sub) {
                case "create":
                    if (g.length() > 20 || !g.matches("[A-Za-z0-9_]+(?: [A-Za-z0-9_]+)*")) {
                        send("Invalid group name. Use up to 20 letters, digits, underscores, and spaces.");
                        return;
                    }
                    Set<String> newMembers = ConcurrentHashMap.newKeySet();
                    newMembers.add(username);
                    if (groups.putIfAbsent(g, newMembers) != null) { send("Group already exists"); return; }
                    try {
                        saveGroups();
                    } catch (IOException e) {
                        groups.remove(g, newMembers);
                        LOG.warning("Could not save groups: " + e.getMessage());
                        send("ERR Could not save the group. Check server storage permissions.");
                        return;
                    }
                    LOG.info(username + " created group " + g);
                    send("Group '" + g + "' created and joined."); break;
                case "join": {
                    Set<String> m = groups.get(g);
                    if (m == null) { send("No such group"); return; }
                    boolean added = m.add(username);
                    if (added) {
                        try {
                            saveGroups();
                        } catch (IOException e) {
                            m.remove(username);
                            LOG.warning("Could not save groups: " + e.getMessage());
                            send("ERR Could not save group membership. Check server storage permissions.");
                            return;
                        }
                    }
                    LOG.info(username + " joined group " + g);
                    sendToGroup(g, "* " + username + " joined group " + g); break;
                }
                case "leave": {
                    Set<String> m = groups.get(g);
                    if (m == null || !m.remove(username)) { send("You are not in that group"); return; }
                    try {
                        saveGroups();
                    } catch (IOException e) {
                        m.add(username);
                        LOG.warning("Could not save groups: " + e.getMessage());
                        send("ERR Could not save group membership. Check server storage permissions.");
                        return;
                    }
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

        private void handleGroupMessage(String line) {
            String args = line.substring(2).trim();
            String group = groups.keySet().stream()
                    .filter(name -> args.startsWith(name + " "))
                    .max(Comparator.comparingInt(String::length))
                    .orElse(null);
            if (group != null) {
                String text = args.substring(group.length()).trim();
                if (text.isEmpty()) { send("Usage: /g <group> <text>"); return; }
                groupMessage(group, text);
                return;
            }

            String[] parts = args.split("\\s+", 2);
            if (parts.length < 2) { send("Usage: /g <group> <text>"); return; }
            groupMessage(parts[0], parts[1]);
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
