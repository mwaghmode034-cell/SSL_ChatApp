import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/** Console chat client. Run: java ChatClient [host] [port] */
public class ChatClient {
    /** True if the terminal understands ANSI escape codes (so we can erase the line the user just typed). */
    static boolean ansiSupported() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        boolean terminal = System.console() != null;   // false in IDE consoles or when output is redirected
        return terminal && (!windows || System.getenv("WT_SESSION") != null || System.getenv("TERM") != null);
    }

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 5000;
        try (Scanner console = new Scanner(System.in);
             Socket socket = new Socket(host, port)) {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

            System.out.println(in.readLine());               // welcome line
            while (true) {                                    // authentication loop
                System.out.print("(L)ogin or (R)egister? ");
                String mode = console.nextLine().trim().toLowerCase().startsWith("r") ? "REGISTER" : "LOGIN";
                System.out.print("Username: ");
                String user = console.nextLine().trim();
                System.out.print("Password: ");
                String pass = console.nextLine().trim();
                long tLogin = Prof.start();
                out.println(mode + " " + user + " " + pass);
                String resp = in.readLine();
                Prof.end("login round trip", tLogin);
                if (resp == null) { System.out.println("Server closed connection."); return; }
                System.out.println(resp);
                if (resp.startsWith("OK")) break;
            }

            // Reader thread: prints everything the server sends
            Thread reader = new Thread(() -> {
                try {
                    String line;
                    while ((line = in.readLine()) != null) { long t = Prof.start(); System.out.println(line); Prof.end("print server line", t); }
                } catch (IOException ignored) { }
                System.out.println("Disconnected.");
                System.exit(0);
            });
            reader.setDaemon(true);
            reader.start();

            // Main thread: send user input.
            // Each message must appear only once. If the terminal supports ANSI codes we erase the line the
            // user typed and show the server's timestamped copy; otherwise the server does not echo it back.
            boolean erase = ansiSupported();
            if (!erase) out.println("/echo off");   // terminal cannot erase the typed line: ask the server not to send our own messages back
            while (console.hasNextLine()) {
                String line = console.nextLine();
                if (erase) { System.out.print("\033[1A\033[2K\r"); System.out.flush(); }
                long t = Prof.start();
                out.println(line);
                Prof.end("send line", t);
                if (line.trim().equalsIgnoreCase("/quit")) break;
            }
        }
    }
}
