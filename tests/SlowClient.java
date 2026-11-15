import java.io.*;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;

/** One client stops reading. Can the others still chat? Usage: java -cp bin SlowClient [port] */
public class SlowClient {
    static Socket connect(int port, String user, BufferedReader[] inOut) throws Exception {
        Socket s = new Socket("localhost", port);
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
        PrintWriter w = new PrintWriter(s.getOutputStream(), true);
        in.readLine(); w.println("REGISTER " + user + " pw123"); in.readLine();
        inOut[0] = in; return s;
    }
    public static void main(String[] a) throws Exception {
        int port = a.length > 0 ? Integer.parseInt(a[0]) : 5000, n = 20000;
        String run = Long.toString(System.currentTimeMillis() % 100000), pad = "x".repeat(500);
        BufferedReader[] h = new BufferedReader[1];
        Socket stalled = connect(port, "stall" + run, h);              // registered, then never reads again
        Socket recv = connect(port, "recv" + run, h);
        BufferedReader rin = h[0];
        AtomicInteger got = new AtomicInteger();
        Thread t = new Thread(() -> { try { String l; while ((l = rin.readLine()) != null) if (l.contains("payload")) got.incrementAndGet(); } catch (IOException e) { } });
        t.setDaemon(true); t.start();
        Socket send = connect(port, "send" + run, h);
        PrintWriter sw = new PrintWriter(send.getOutputStream(), true);
        BufferedReader sin = h[0];                                      // the sender reads its echoes like a normal client
        Thread sd = new Thread(() -> { try { while (sin.readLine() != null) { } } catch (IOException e) { } });
        sd.setDaemon(true); sd.start();
        long t0 = System.currentTimeMillis();
        Thread st = new Thread(() -> { for (int i = 0; i < n; i++) sw.println("payload " + i + " " + pad); });
        st.setDaemon(true); st.start();
        while (got.get() < n && System.currentTimeMillis() - t0 < 20000) Thread.sleep(50);
        System.out.println("receiver got " + got.get() + " of " + n + " messages in " + (System.currentTimeMillis() - t0) + " ms"
                + (got.get() < n ? "  <-- BLOCKED by the stalled client" : ""));
        System.exit(0);
    }
}
