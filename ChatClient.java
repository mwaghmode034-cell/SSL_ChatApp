import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/** Console chat client. Run: java ChatClient [host] [port] */
public class ChatClient {
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
                out.println(mode + " " + user + " " + pass);
                String resp = in.readLine();
                if (resp == null) { System.out.println("Server closed connection."); return; }
                System.out.println(resp);
                if (resp.startsWith("OK")) break;
            }

            // Reader thread: prints everything the server sends
            Thread reader = new Thread(() -> {
                try {
                    String line;
                    while ((line = in.readLine()) != null) System.out.println(line);
                } catch (IOException ignored) { }
                System.out.println("Disconnected.");
                System.exit(0);
            });
            reader.setDaemon(true);
            reader.start();

            // Main thread: send user input
            while (console.hasNextLine()) {
                String line = console.nextLine();
                out.println(line);
                if (line.trim().equalsIgnoreCase("/quit")) break;
            }
        }
    }
}
