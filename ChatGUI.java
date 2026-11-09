import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import javax.swing.*;
import javax.swing.text.*;

/**
 * Swing GUI client for ChatServer.
 * Compile: javac ChatServer.java ChatClient.java ChatGUI.java
 * Run:     java ChatGUI
 *
 * Uses the same text protocol as ChatClient, so no server changes are needed.
 * Networking is read on a background thread; every Swing update goes through
 * SwingUtilities.invokeLater so the UI never freezes or gets corrupted.
 */
public class ChatGUI extends JFrame {
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private String username;

    private final JTextPane chatPane = new JTextPane();
    private final DefaultListModel<String> userModel = new DefaultListModel<>();
    private final DefaultListModel<String> groupModel = new DefaultListModel<>();
    private final JList<String> userList = new JList<>(userModel);
    private final JList<String> groupList = new JList<>(groupModel);
    private final JTextField input = new JTextField();
    private final JButton sendBtn = new JButton("Send");
    private final JLabel statusLabel = new JLabel("Not connected");
    private javax.swing.Timer refreshTimer;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            ChatGUI gui = new ChatGUI();
            if (gui.login()) gui.start();
            else System.exit(0);
        });
    }

    // ---------------------------------------------------------------- login

    private boolean login() {
        JTextField hostF = new JTextField("localhost", 12);
        JTextField portF = new JTextField("5000", 12);
        JTextField userF = new JTextField(12);
        JPasswordField passF = new JPasswordField(12);

        JPanel fields = new JPanel(new GridLayout(0, 2, 6, 6));
        fields.add(new JLabel("Server:"));   fields.add(hostF);
        fields.add(new JLabel("Port:"));     fields.add(portF);
        fields.add(new JLabel("Username:")); fields.add(userF);
        fields.add(new JLabel("Password:")); fields.add(passF);
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.add(new JLabel("Connect to your chat server to log in or create an account."), BorderLayout.NORTH);
        p.add(fields, BorderLayout.CENTER);

        String[] opts = {"Login", "Register", "Cancel"};
        while (true) {
            int choice = JOptionPane.showOptionDialog(null, p, "Chat - Sign in",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, opts, opts[0]);
            if (choice != 0 && choice != 1) return false;   // Cancel or closed

            String user = userF.getText().trim();
            String pass = new String(passF.getPassword());
            String host = hostF.getText().trim();
            if (host.isEmpty()) {
                error("Enter the server host name or IP address.");
                continue;
            }
            if (!user.matches("[A-Za-z0-9_]{3,16}")) {
                error("Username must be 3-16 letters, digits, or underscores.");
                continue;
            }
            if (pass.isEmpty() || pass.matches(".*\\s.*")) {
                error("Password is required and cannot contain whitespace.");
                continue;
            }
            try {
                int port = Integer.parseInt(portF.getText().trim());
                if (port < 1 || port > 65535) {
                    error("Port must be between 1 and 65535.");
                    continue;
                }
                connect(host, port, choice == 1 ? "REGISTER" : "LOGIN", user, pass);
                return true;
            } catch (NumberFormatException e) {
                error("Enter a valid port number, such as 5000.");
            } catch (UnknownHostException e) {
                error("Could not find server '" + host + "'. Check the host name or IP address.");
                closeQuietly();
            } catch (ConnectException e) {
                error("Could not connect to " + host + ". Check that the server is running and the port is correct.");
                closeQuietly();
            } catch (SocketTimeoutException e) {
                error("The server did not respond in time. Check your connection and try again.");
                closeQuietly();
            } catch (IOException e) {
                error(e.getMessage() == null ? "Could not complete sign-in." : e.getMessage());
                closeQuietly();
            }
        }
    }

    /** Connects and authenticates (blocking, with timeouts). Throws IOException with a user-friendly message. */
    private void connect(String host, int port, String mode, String user, String pass) throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 5000);
        socket.setSoTimeout(5000);
        in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

        if (in.readLine() == null) throw new IOException("The server closed the connection before sign-in.");
        out.println(mode + " " + user + " " + pass);
        String resp = in.readLine();
        if (resp == null) throw new IOException("Server closed the connection.");
        if (!resp.startsWith("OK")) throw new IOException(resp.startsWith("ERR ") ? resp.substring(4) : resp);

        socket.setSoTimeout(0);                          // no timeout once chatting
        username = user;
    }

    // ------------------------------------------------------------ main window

    private void start() {
        setTitle("Chat - " + username);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { shutdown(); }
        });

        chatPane.setEditable(false);
        userList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        groupList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        statusLabel.setText("Connected as " + username + "  |  Online users: 0  |  Groups: 0");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        input.setToolTipText("Type a message to everyone, or use /help for chat commands.");

        // Double-click a user or group to prefill the command in the input box
        userList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && userList.getSelectedValue() != null)
                    prefill("/msg " + userList.getSelectedValue() + " ");
            }
        });
        groupList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && groupList.getSelectedValue() != null)
                    prefill("/g " + groupList.getSelectedValue() + " ");
            }
        });

        JScrollPane us = new JScrollPane(userList);
        us.setBorder(BorderFactory.createTitledBorder("Online (double-click = private)"));
        JScrollPane gs = new JScrollPane(groupList);
        gs.setBorder(BorderFactory.createTitledBorder("Groups (double-click = message)"));
        JPanel lists = new JPanel(new GridLayout(2, 1, 0, 6));
        lists.add(us);
        lists.add(gs);

        JButton newG = new JButton("New group");
        JButton joinG = new JButton("Join");
        JButton leaveG = new JButton("Leave");
        JButton hist = new JButton("History");
        newG.addActionListener(e -> {
            String n = JOptionPane.showInputDialog(this, "New group name:");
            if (n != null && !n.trim().isEmpty()) { out.println("/group create " + n.trim()); requestRefresh(); }
        });
        joinG.addActionListener(e -> {
            String g = pickGroup("Group to join:");
            if (g != null) { out.println("/group join " + g); requestRefresh(); }
        });
        leaveG.addActionListener(e -> {
            String g = pickGroup("Group to leave:");
            if (g != null) { out.println("/group leave " + g); requestRefresh(); }
        });
        hist.addActionListener(e -> out.println("/history 50"));

        JPanel buttons = new JPanel(new GridLayout(2, 2, 4, 4));
        buttons.add(newG);
        buttons.add(joinG);
        buttons.add(leaveG);
        buttons.add(hist);

        JPanel side = new JPanel(new BorderLayout(0, 6));
        side.add(lists, BorderLayout.CENTER);
        side.add(buttons, BorderLayout.SOUTH);
        side.setPreferredSize(new Dimension(230, 0));
        side.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 6));

        JPanel bottom = new JPanel(new BorderLayout(6, 0));
        bottom.add(input, BorderLayout.CENTER);
        bottom.add(sendBtn, BorderLayout.EAST);
        bottom.setBorder(BorderFactory.createEmptyBorder(0, 6, 6, 6));
        JPanel footer = new JPanel(new BorderLayout(0, 4));
        footer.add(new JLabel("Enter sends to everyone. Double-click a user or group to start a private message."), BorderLayout.NORTH);
        footer.add(bottom, BorderLayout.CENTER);

        JScrollPane chatScroll = new JScrollPane(chatPane);
        chatScroll.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        setLayout(new BorderLayout());
        add(statusLabel, BorderLayout.NORTH);
        add(chatScroll, BorderLayout.CENTER);
        add(side, BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);

        sendBtn.addActionListener(e -> sendInput());
        input.addActionListener(e -> sendInput());

        setSize(860, 540);
        setLocationRelativeTo(null);
        setVisible(true);
        input.requestFocusInWindow();

        startReaderThread();
        requestRefresh();
        refreshTimer = new javax.swing.Timer(10000, e -> requestRefresh());
        refreshTimer.start();
    }

    // ------------------------------------------------------------- networking

    private void startReaderThread() {
        Thread t = new Thread(() -> {
            String disconnectMessage = "Disconnected from the server.";
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    final String l = line;
                    SwingUtilities.invokeLater(() -> handleLine(l));
                }
            } catch (IOException e) {
                disconnectMessage = "Connection lost: " + e.getMessage();
            }
            final String message = disconnectMessage;
            SwingUtilities.invokeLater(() -> onDisconnected(message));
        }, "reader");
        t.setDaemon(true);
        t.start();
    }

    private void sendInput() {
        String text = input.getText().trim();
        if (text.isEmpty()) return;
        if (out == null || socket == null || socket.isClosed()) {
            onDisconnected("Not connected to the server.");
            return;
        }
        out.println(text);      // the server echoes our own messages back, so no local echo
        input.setText("");
    }

    /** Asks the server for the user and group lists (replies are intercepted in handleLine). */
    private void requestRefresh() {
        if (out != null) {
            out.println("/users");
            out.println("/group list");
        }
    }

    // -------------------------------------------------------- handling output

    private void handleLine(String line) {
        if (line.startsWith("Online: ")) { updateList(userModel, line.substring(8)); return; }
        if (line.startsWith("Groups: ")) { updateList(groupModel, line.substring(8)); return; }
        if (line.startsWith("* ")) requestRefresh();   // someone joined/left: refresh sidebar
        append(line, colorFor(line));
    }

    private void updateList(DefaultListModel<String> model, String csv) {
        java.util.List<String> items = new java.util.ArrayList<>();
        if (!csv.equals("(none)"))
            for (String s : csv.split(",\\s*")) if (!s.isEmpty()) items.add(s);

        boolean same = items.size() == model.size();   // skip no-op updates so selection isn't lost
        for (int i = 0; same && i < items.size(); i++) same = items.get(i).equals(model.get(i));
        if (same) return;

        model.clear();
        for (String s : items) model.addElement(s);
        statusLabel.setText("Connected as " + username + "  |  Online users: "
                + userModel.size() + "  |  Groups: " + groupModel.size());
    }

    private Color colorFor(String line) {
        if (line.startsWith("ERR") || line.startsWith("No such") || line.startsWith("Usage")
                || line.startsWith("Unknown") || line.startsWith("Invalid") || line.startsWith("Join the group")
                || line.contains("already exists") || line.contains("already logged in")
                || line.startsWith("You are not"))
            return new Color(200, 50, 50);
        if (line.startsWith("* ") || line.startsWith("---")) return Color.GRAY;
        if (line.matches("^\\[[^\\]]+\\] \\(private\\) .*")) return new Color(160, 60, 200);
        if (line.matches("^\\[[^\\]]+\\] \\[[^\\]]+\\] .*")) return new Color(30, 130, 180);  // group message
        return null;   // default text colour (works in light and dark themes)
    }

    private void append(String text, Color c) {
        StyledDocument doc = chatPane.getStyledDocument();
        SimpleAttributeSet attrs = new SimpleAttributeSet();
        if (c != null) StyleConstants.setForeground(attrs, c);
        try {
            doc.insertString(doc.getLength(), text + "\n", attrs);
        } catch (BadLocationException ignored) { }
        chatPane.setCaretPosition(doc.getLength());
    }

    private void onDisconnected(String message) {
        if (refreshTimer != null) refreshTimer.stop();
        append("--- " + message + " ---", new Color(200, 50, 50));
        statusLabel.setText("Disconnected from the server");
        statusLabel.setForeground(new Color(200, 50, 50));
        input.setEnabled(false);
        sendBtn.setEnabled(false);
    }

    // ---------------------------------------------------------------- helpers

    private void prefill(String text) {
        input.setText(text);
        input.requestFocusInWindow();
        input.setCaretPosition(text.length());
    }

    /** Uses the selected group in the sidebar, or asks for a name. */
    private String pickGroup(String prompt) {
        String sel = groupList.getSelectedValue();
        if (sel != null) return sel;
        String n = JOptionPane.showInputDialog(this, prompt);
        return (n == null || n.trim().isEmpty()) ? null : n.trim();
    }

    private void error(String msg) {
        JOptionPane.showMessageDialog(null, msg, "Error", JOptionPane.ERROR_MESSAGE);
    }

    private void closeQuietly() {
        try { if (socket != null) socket.close(); } catch (IOException ignored) { }
    }

    private void shutdown() {
        if (out != null) out.println("/quit");
        closeQuietly();
        dispose();
        System.exit(0);
    }
}