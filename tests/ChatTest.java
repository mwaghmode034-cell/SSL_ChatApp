import java.io.*;
import java.net.Socket;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Functional tests for ChatServer. Usage: java -cp bin ChatTest [port] [main|persist] */
public class ChatTest {
    static int port = 5000, pass = 0, fail = 0;

    static class C {   // minimal test client: a socket plus a thread collecting server lines
        PrintWriter out;
        BlockingQueue<String> q = new LinkedBlockingQueue<>();
        C() throws Exception {
            Socket s = new Socket("localhost", port);
            out = new PrintWriter(s.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
            Thread t = new Thread(() -> { try { String l; while ((l = in.readLine()) != null) q.add(l); } catch (IOException e) { } });
            t.setDaemon(true); t.start();
        }
        void send(String x) { out.println(x); }
        /** Wait up to ms for a line containing needle (null on timeout). */
        String get(String needle, long ms) throws Exception {
            long end = System.currentTimeMillis() + ms;
            for (long left; (left = end - System.currentTimeMillis()) > 0; ) {
                String l = q.poll(left, TimeUnit.MILLISECONDS);
                if (l != null && l.contains(needle)) return l;
            }
            return null;
        }
        String auth(String cmd) throws Exception {   // first OK/ERR reply to a LOGIN/REGISTER line
            send(cmd);
            for (String l; (l = q.poll(15, TimeUnit.SECONDS)) != null; ) if (l.startsWith("OK") || l.startsWith("ERR")) return l;
            return "TIMEOUT";
        }
        String hist() throws Exception {   // everything /history returns
            send("/history 100");
            StringBuilder sb = new StringBuilder();
            for (String l; (l = q.poll(3, TimeUnit.SECONDS)) != null; ) { sb.append(l).append('\n'); if (l.contains("end of history")) break; }
            return sb.toString();
        }
    }

    static void ok(String name, boolean cond) { if (cond) pass++; else fail++; System.out.println((cond ? "  PASS  " : "  FAIL  ") + name); }

    public static void main(String[] args) throws Exception {
        if (args.length > 0) port = Integer.parseInt(args[0]);
        if (args.length > 1 && args[1].equals("persist")) persist(); else functional();
        System.out.println("Result: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void functional() throws Exception {
        C a = new C(), b = new C(), c = new C();
        ok("register + auto login", a.auth("REGISTER alice pw123").startsWith("OK"));
        ok("duplicate username rejected", b.auth("REGISTER alice x1234").startsWith("ERR"));
        ok("wrong password rejected", b.auth("LOGIN alice wrong").startsWith("ERR"));
        ok("second user registers", b.auth("REGISTER bob pw123").startsWith("OK"));
        c.auth("REGISTER carol pw123");

        a.send("hello all");
        String l = b.get("alice: hello all", 3000);
        ok("public message has timestamp", l != null && l.matches("^\\[\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\] .*"));

        a.send("/msg bob secret1");
        ok("private message delivered", b.get("secret1", 3000) != null);
        ok("private message hidden from others", !c.hist().contains("secret1"));

        a.send("/group create Study Group");
        ok("group with spaces created", a.get("created", 3000) != null);
        b.send("/g Study Group too-early");
        ok("non-member cannot post", b.get("Join the group first", 3000) != null);
        b.send("/group join Study Group");
        b.send("/g Study Group hello team");
        ok("group message reaches member", a.get("[Study Group] bob: hello team", 3000) != null);
        ok("group message hidden from non-member", !c.hist().contains("hello team"));

        AtomicInteger wins = new AtomicInteger();   // 8 threads race to register one name
        Thread[] ts = new Thread[8];
        for (int i = 0; i < ts.length; i++) {
            ts[i] = new Thread(() -> { try { if (new C().auth("REGISTER racer pw123").startsWith("OK")) wins.incrementAndGet(); } catch (Exception e) { } });
            ts[i].start();
        }
        for (Thread t : ts) t.join();
        ok("concurrent duplicate register: one winner", wins.get() == 1);
    }

    static void persist() throws Exception {   // run after the server was restarted
        C a = new C();
        ok("user survives restart", a.auth("LOGIN alice pw123").startsWith("OK"));
        String h = a.hist();
        ok("history survives restart", h.contains("alice: hello all") && h.contains("hello team"));
        a.send("/group members Study Group");
        ok("group survives restart", a.get("Members of Study Group", 3000) != null);
    }
}
