import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import javax.swing.*;

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
    private static final Color PAGE = new Color(240, 242, 245);
    private static final Color CHAT_BACKGROUND = new Color(239, 234, 226);
    private static final Color SURFACE = Color.WHITE;
    private static final Color TEXT = new Color(31, 41, 55);
    private static final Color MUTED = new Color(107, 114, 128);
    private static final Color BORDER = new Color(220, 224, 228);
    private static final Color HEADER = new Color(7, 94, 84);
    private static final Color ACCENT = new Color(37, 166, 112);
    private static final Color OUTGOING_BUBBLE = new Color(217, 253, 211);
    private static final Color ERROR_BUBBLE = new Color(255, 235, 235);

    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private String username;

    private final JPanel chatMessages = new JPanel();
    private JScrollPane chatScroll;
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

        JPanel fields = new JPanel(new GridLayout(0, 2, 12, 12));
        fields.setOpaque(false);
        addLoginField(fields, "Server", hostF);
        addLoginField(fields, "Port", portF);
        addLoginField(fields, "Username", userF);
        addLoginField(fields, "Password", passF);

        JLabel title = new JLabel("Welcome to SSL Chat");
        title.setFont(new Font("Segoe UI", Font.BOLD, 20));
        title.setForeground(TEXT);
        JLabel subtitle = new JLabel("Sign in to your server, or register a new account.");
        subtitle.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        subtitle.setForeground(MUTED);
        JPanel intro = new JPanel();
        intro.setOpaque(false);
        intro.setLayout(new BoxLayout(intro, BoxLayout.Y_AXIS));
        intro.add(title);
        intro.add(Box.createVerticalStrut(5));
        intro.add(subtitle);

        JPanel p = new JPanel(new BorderLayout(0, 20));
        p.setBackground(PAGE);
        p.setBorder(BorderFactory.createEmptyBorder(20, 22, 20, 22));
        p.add(intro, BorderLayout.NORTH);
        p.add(fields, BorderLayout.CENTER);

        String[] opts = {"Login", "Register", "Cancel"};
        while (true) {
            int choice = JOptionPane.showOptionDialog(null, p, "SSL Chat | Sign in",
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

        chatMessages.setLayout(new BoxLayout(chatMessages, BoxLayout.Y_AXIS));
        chatMessages.setBackground(CHAT_BACKGROUND);
        chatMessages.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        userList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        groupList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        styleList(userList);
        styleList(groupList);
        statusLabel.setText("Connected as " + username + "  |  Online users: 0  |  Groups: 0");
        statusLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        statusLabel.setForeground(Color.WHITE);
        statusLabel.setOpaque(false);
        statusLabel.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        input.setToolTipText("Type a message to everyone, or use /help for chat commands.");
        input.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        input.setForeground(TEXT);
        input.setBackground(SURFACE);
        input.setMargin(new Insets(9, 11, 9, 11));
        input.setBorder(BorderFactory.createLineBorder(BORDER));
        styleButton(sendBtn, true);

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

        JPanel lists = new JPanel(new GridLayout(2, 1, 0, 10));
        lists.setOpaque(false);
        lists.add(listSection("ONLINE PEOPLE", userList));
        lists.add(listSection("YOUR GROUPS", groupList));

        JButton newG = new JButton("New group");
        JButton joinG = new JButton("Join");
        JButton leaveG = new JButton("Leave");
        JButton hist = new JButton("History");
        styleButton(newG, false);
        styleButton(joinG, false);
        styleButton(leaveG, false);
        styleButton(hist, false);
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

        JPanel side = new JPanel(new BorderLayout(0, 12));
        side.setBackground(PAGE);
        side.add(lists, BorderLayout.CENTER);
        side.add(buttons, BorderLayout.SOUTH);
        side.setPreferredSize(new Dimension(280, 0));
        side.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 0));

        JPanel bottom = new JPanel(new BorderLayout(6, 0));
        bottom.setOpaque(false);
        bottom.add(input, BorderLayout.CENTER);
        bottom.add(sendBtn, BorderLayout.EAST);
        JLabel hint = new JLabel("Enter sends | /help for commands | Double-click a person or group to message");
        hint.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        hint.setForeground(MUTED);
        JPanel footer = new JPanel(new BorderLayout(0, 8));
        footer.setBackground(PAGE);
        footer.add(hint, BorderLayout.NORTH);
        footer.add(bottom, BorderLayout.CENTER);
        footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER),
                BorderFactory.createEmptyBorder(10, 14, 12, 14)));

        chatScroll = new JScrollPane(chatMessages);
        chatScroll.setBorder(BorderFactory.createEmptyBorder());
        chatScroll.getViewport().setBackground(CHAT_BACKGROUND);
        chatScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        JLabel appName = new JLabel("SSL Chat");
        appName.setFont(new Font("Segoe UI", Font.BOLD, 18));
        appName.setForeground(Color.WHITE);
        JLabel appSubtitle = new JLabel("A place to chat");
        appSubtitle.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        appSubtitle.setForeground(new Color(220, 240, 235));
        JPanel brand = new JPanel();
        brand.setOpaque(false);
        brand.setLayout(new BoxLayout(brand, BoxLayout.Y_AXIS));
        brand.add(appName);
        brand.add(Box.createVerticalStrut(2));
        brand.add(appSubtitle);
        JPanel header = new JPanel(new BorderLayout(16, 0));
        header.setBackground(HEADER);
        header.add(brand, BorderLayout.WEST);
        header.add(statusLabel, BorderLayout.EAST);
        header.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));

        JLabel conversationName = new JLabel("Everyone");
        conversationName.setFont(new Font("Segoe UI", Font.BOLD, 14));
        conversationName.setForeground(TEXT);
        JLabel conversationDetail = new JLabel("Public conversation");
        conversationDetail.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        conversationDetail.setForeground(MUTED);
        JPanel conversationTitle = new JPanel();
        conversationTitle.setOpaque(false);
        conversationTitle.setLayout(new BoxLayout(conversationTitle, BoxLayout.Y_AXIS));
        conversationTitle.add(conversationName);
        conversationTitle.add(Box.createVerticalStrut(2));
        conversationTitle.add(conversationDetail);
        JPanel conversationHeader = new JPanel(new BorderLayout());
        conversationHeader.setBackground(SURFACE);
        conversationHeader.add(conversationTitle, BorderLayout.WEST);
        conversationHeader.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER),
                BorderFactory.createEmptyBorder(10, 16, 10, 16)));
        JPanel conversation = new JPanel(new BorderLayout());
        conversation.setBackground(CHAT_BACKGROUND);
        conversation.add(conversationHeader, BorderLayout.NORTH);
        conversation.add(chatScroll, BorderLayout.CENTER);
        conversation.add(footer, BorderLayout.SOUTH);

        JPanel content = new JPanel(new BorderLayout());
        content.setBackground(PAGE);
        content.add(side, BorderLayout.WEST);
        content.add(conversation, BorderLayout.CENTER);

        setLayout(new BorderLayout());
        getContentPane().setBackground(PAGE);
        add(header, BorderLayout.NORTH);
        add(content, BorderLayout.CENTER);

        sendBtn.addActionListener(e -> sendInput());
        input.addActionListener(e -> sendInput());

        setMinimumSize(new Dimension(780, 500));
        setSize(980, 650);
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
        if (isErrorLine(line))
            return new Color(185, 55, 55);
        if (line.startsWith("* ") || line.startsWith("---")) return MUTED;
        if (line.matches("^\\[[^\\]]+\\] \\(private\\) .*")) return new Color(126, 58, 173);
        if (line.matches("^\\[[^\\]]+\\] \\[[^\\]]+\\] .*")) return new Color(37, 99, 166);
        return TEXT;
    }

    private void append(String text, Color c) {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        if (text.startsWith("* ") || text.startsWith("---")) {
            JLabel notice = new JLabel(text, SwingConstants.CENTER);
            notice.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            notice.setForeground(c == null ? MUTED : c);
            row.add(notice, BorderLayout.CENTER);
        } else {
            boolean outgoing = isOwnMessage(text);
            JTextArea bubble = new JTextArea(text);
            bubble.setEditable(false);
            bubble.setFocusable(false);
            bubble.setLineWrap(true);
            bubble.setWrapStyleWord(true);
            bubble.setFont(new Font("Segoe UI", Font.PLAIN, 13));
            bubble.setForeground(isErrorLine(text) ? new Color(185, 55, 55)
                    : (c == null ? TEXT : c));
            bubble.setBackground(isErrorLine(text) ? ERROR_BUBBLE
                    : (outgoing ? OUTGOING_BUBBLE : SURFACE));

            FontMetrics metrics = bubble.getFontMetrics(bubble.getFont());
            int desiredWidth = Math.min(390, Math.max(150, metrics.stringWidth(text) + 24));
            int columnWidth = Math.max(1, metrics.charWidth('m'));
            int columns = Math.max(12, Math.min(42,
                    (desiredWidth - 24) / columnWidth));
            bubble.setColumns(columns);
            bubble.setRows(wrappedLineCount(text, metrics, columns * columnWidth));
            bubble.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
            row.add(bubble, outgoing ? BorderLayout.EAST : BorderLayout.WEST);
        }

        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        chatMessages.add(row);
        chatMessages.add(Box.createVerticalStrut(2));
        chatMessages.revalidate();
        chatMessages.repaint();
        SwingUtilities.invokeLater(() ->
                chatScroll.getVerticalScrollBar().setValue(chatScroll.getVerticalScrollBar().getMaximum()));
    }

    private boolean isErrorLine(String line) {
        return line.startsWith("ERR") || line.startsWith("No such") || line.startsWith("Usage")
                || line.startsWith("Unknown") || line.startsWith("Invalid") || line.startsWith("Join the group")
                || line.contains("already exists") || line.contains("already logged in")
                || line.startsWith("You are not");
    }

    private boolean isOwnMessage(String line) {
        if (!line.startsWith("[")) return false;
        return line.contains("] " + username + ": ")
                || line.contains("] (private) " + username + " -> ")
                || line.contains(" -> " + username + ": ");
    }

    private int wrappedLineCount(String text, FontMetrics metrics, int width) {
        int lines = 1;
        int lineWidth = 0;
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (character == '\n') {
                lines++;
                lineWidth = 0;
                continue;
            }
            int characterWidth = metrics.charWidth(character);
            if (lineWidth > 0 && lineWidth + characterWidth > width) {
                lines++;
                lineWidth = 0;
            }
            lineWidth += characterWidth;
        }
        return lines;
    }

    private void addLoginField(JPanel fields, String label, JTextField field) {
        JLabel caption = new JLabel(label);
        caption.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        caption.setForeground(TEXT);
        field.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        field.setMargin(new Insets(7, 9, 7, 9));
        field.setBorder(BorderFactory.createLineBorder(BORDER));
        fields.add(caption);
        fields.add(field);
    }

    private void styleList(JList<String> list) {
        list.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        list.setBackground(SURFACE);
        list.setForeground(TEXT);
        list.setSelectionBackground(OUTGOING_BUBBLE);
        list.setSelectionForeground(TEXT);
        list.setFixedCellHeight(30);
        list.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
    }

    private JPanel listSection(String title, JList<String> list) {
        JLabel heading = new JLabel(title);
        heading.setFont(new Font("Segoe UI", Font.BOLD, 11));
        heading.setForeground(MUTED);
        heading.setBorder(BorderFactory.createEmptyBorder(0, 2, 8, 2));
        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createLineBorder(BORDER));
        scroll.getViewport().setBackground(SURFACE);
        JPanel section = new JPanel(new BorderLayout(0, 4));
        section.setOpaque(false);
        section.add(heading, BorderLayout.NORTH);
        section.add(scroll, BorderLayout.CENTER);
        return section;
    }

    private void styleButton(JButton button, boolean primary) {
        button.setFont(new Font("Segoe UI", Font.BOLD, 13));
        button.setFocusPainted(false);
        button.setOpaque(true);
        button.setBackground(primary ? ACCENT : SURFACE);
        button.setForeground(primary ? Color.WHITE : TEXT);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(primary ? ACCENT : BORDER),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    private void onDisconnected(String message) {
        if (refreshTimer != null) refreshTimer.stop();
        append("--- " + message + " ---", new Color(200, 50, 50));
        statusLabel.setText("Disconnected from the server");
        statusLabel.setForeground(new Color(255, 205, 205));
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