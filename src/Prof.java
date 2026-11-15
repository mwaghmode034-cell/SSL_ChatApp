import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

/** Tiny profiler (like gprof's flat profile): calls, total and average time per method.
 *  Off by default. Start the server with -Dprofile=true; the table is printed on exit (Ctrl+C). */
final class Prof {
    static final boolean ON = Boolean.getBoolean("profile");
    private static final Map<String, LongAdder[]> STATS = new ConcurrentHashMap<>();

    static long start() { return ON ? System.nanoTime() : 0; }

    static void end(String name, long t0) {
        if (!ON) return;
        long dt = System.nanoTime() - t0;
        LongAdder[] s = STATS.computeIfAbsent(name, k -> new LongAdder[]{new LongAdder(), new LongAdder()});
        s[0].increment();
        s[1].add(dt);
    }

    static {
        if (ON) Runtime.getRuntime().addShutdownHook(new Thread(Prof::print));
    }

    static void print() {
        List<Map.Entry<String, LongAdder[]>> rows = new ArrayList<>(STATS.entrySet());
        rows.sort((a, b) -> Long.compare(b.getValue()[1].sum(), a.getValue()[1].sum()));
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%n%-20s %10s %12s %12s%n", "method", "calls", "total ms", "avg ms"));
        for (Map.Entry<String, LongAdder[]> e : rows) {
            long n = e.getValue()[0].sum(), ns = e.getValue()[1].sum();
            sb.append(String.format(Locale.ROOT, "%-20s %10d %12.1f %12.4f%n", e.getKey(), n, ns / 1e6, ns / 1e6 / n));
        }
        sb.append("(total includes time spent waiting for locks; threads overlap, so totals can exceed the run time)\n");
        System.out.print(sb);
        try { java.nio.file.Files.write(java.nio.file.Paths.get(System.getProperty("profile.out", "profile_" + System.getProperty("sun.java.command", "app").split(" ")[0] + ".txt")), sb.toString().getBytes()); }
        catch (java.io.IOException ignored) { }
    }
}
