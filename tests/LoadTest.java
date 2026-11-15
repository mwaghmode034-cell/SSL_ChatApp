import java.io.*;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Load generator for profiling. Usage: java -cp bin LoadTest [port] [clients] [messagesPerClient]
 * Registers N clients, then every client sends M public messages at the same time.
 * Each message is delivered to all N clients, so N*M*N lines must arrive in total.
 */
public class LoadTest {
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 5000;
        int n = args.length > 1 ? Integer.parseInt(args[1]) : 40;
        int m = args.length > 2 ? Integer.parseInt(args[2]) : 100;
        String run = Long.toString(System.currentTimeMillis() % 100000);   // unique usernames per run
        AtomicLong received = new AtomicLong();
        PrintWriter[] out = new PrintWriter[n];
        Socket[] socks = new Socket[n];

        long t0 = System.nanoTime();                       // phase 1: register (PBKDF2 hashing)
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int id = i;
            fs.add(pool.submit(() -> {
                try {
                    Socket s = new Socket("localhost", port);
                    BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
                    PrintWriter w = new PrintWriter(s.getOutputStream(), true);
                    in.readLine();
                    w.println("REGISTER u" + run + "_" + id + " pw123");
                    String r = in.readLine();
                    if (!r.startsWith("OK")) throw new IOException(r);
                    socks[id] = s; out[id] = w;
                    Thread t = new Thread(() -> {
                        try { String l; while ((l = in.readLine()) != null) if (l.contains("msg-")) received.incrementAndGet(); }
                        catch (IOException e) { }
                    });
                    t.setDaemon(true); t.start();
                } catch (Exception e) { throw new RuntimeException(e); }
            }));
        }
        for (Future<?> f : fs) f.get();
        double regSec = (System.nanoTime() - t0) / 1e9;

        long t1 = System.nanoTime();                       // phase 2: everyone chats at once
        CountDownLatch go = new CountDownLatch(1);
        for (int i = 0; i < n; i++) {
            final int id = i;
            pool.submit(() -> {
                try { go.await(); } catch (InterruptedException e) { return; }
                for (int k = 0; k < m; k++) out[id].println("msg-" + id + "-" + k);
            });
        }
        go.countDown();
        long expected = (long) n * m * n;
        long deadline = System.currentTimeMillis() + 120_000;
        while (received.get() < expected && System.currentTimeMillis() < deadline) Thread.sleep(20);
        double chatSec = (System.nanoTime() - t1) / 1e9;

        System.out.printf("clients=%d  messages/client=%d  sent=%d  deliveries=%d/%d%n", n, m, n * m, received.get(), expected);
        System.out.printf("register phase : %.2f s  (%.0f ms per user)%n", regSec, regSec * 1000 * 1.0 / n);
        System.out.printf("chat phase     : %.2f s%n", chatSec);
        System.out.printf("throughput     : %.0f messages/s sent, %.0f deliveries/s%n", n * m / chatSec, received.get() / chatSec);
        // one machine-readable line for scripts/profile.sh: clients msgs chat_s msgs_per_s deliveries_per_s register_s all_delivered
        System.out.printf(Locale.ROOT, "RESULT %d %d %.2f %.0f %.0f %.2f %d%n", n, m, chatSec, n * m / chatSec,
                received.get() / chatSec, regSec, received.get() == expected ? 1 : 0);
        for (Socket s : socks) if (s != null) s.close();
        pool.shutdownNow();
        System.exit(received.get() == expected ? 0 : 1);
    }
}
