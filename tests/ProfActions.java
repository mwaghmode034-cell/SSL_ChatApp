import java.io.*;
import java.net.Socket;

/** Uses every kind of command a few times so the profiler has something to measure. Usage: java ProfActions [port] */
public class ProfActions {
    public static void main(String[] a) throws Exception {
        int port = a.length > 0 ? Integer.parseInt(a[0]) : 5000;
        try (Socket s1 = new Socket("localhost", port); Socket s2 = new Socket("localhost", port)) {
            PrintWriter o1 = w(s1), o2 = w(s2);
            BufferedReader i1 = r(s1), i2 = r(s2);
            i1.readLine(); i2.readLine();
            o1.println("REGISTER alice pass123"); o2.println("REGISTER bob pass123");
            i1.readLine(); i2.readLine();
            try (Socket s3 = new Socket("localhost", port)) {           // login attempts for an existing user
                PrintWriter o3 = w(s3); BufferedReader i3 = r(s3);
                i3.readLine();
                o3.println("LOGIN alice wrong"); i3.readLine();
                o3.println("LOGIN alice pass123"); i3.readLine();
            }
            for (int k = 0; k < 20; k++) {
                String g = "grp" + k;
                o1.println("/group create " + g); o2.println("/group join " + g);
                o1.println("/g " + g + " hello"); o1.println("/msg bob hi " + k);
                o2.println("/group members " + g); o2.println("/users");
                o2.println("/group list"); o2.println("/group mine"); o2.println("/history 20");
                o2.println("/group leave " + g); o1.println("hello everyone");
            }
            o1.println("/quit"); o2.println("/quit");
            while (i1.readLine() != null) { }
            while (i2.readLine() != null) { }
        }
    }
    static PrintWriter w(Socket s) throws IOException { return new PrintWriter(new OutputStreamWriter(s.getOutputStream(), "UTF-8"), true); }
    static BufferedReader r(Socket s) throws IOException { return new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8")); }
}
