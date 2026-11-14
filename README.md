# Multi-client Chat Application

A client-server chat application in Java with a console client and a Swing GUI client.
Requires Java 8+ (a JDK, so that `javac` is available) and a bash shell (Linux/macOS/Git Bash).

## Features
- Register and log in with a username and password (salted PBKDF2-HMAC-SHA256 hashes).
- A connection is closed after five failed login attempts.
- Public chat, private messages (also to offline users, readable later via history).
- Chat groups: create, join, leave, list members; names may contain spaces (up to 20 letters, digits, underscores, spaces).
- Every message has a timestamp; `/history` shows recent messages the user is allowed to see.
- The GUI shows sender and time details, wraps long messages, and opens history in a separate scrollable window.
- Users, history, groups and memberships survive server restarts.
- Server event log (`server.log`).
- Optional Swing GUI client.

## Course topics covered
Java threads (one thread per client via `ExecutorService`), synchronization (`synchronized`,
thread-safe `ConcurrentHashMap`/`synchronizedList`), Collections, sockets/networking, file I/O,
GUI (Swing), Git, Bash automation.

## Project structure
    ChatServer.java   multi-threaded server
    ChatClient.java   console client
    ChatGUI.java      Swing GUI client
    tests/ChatTest.java   functional tests
    scripts/run.sh    build / run helper
    scripts/test.sh   automated test run
    data/             runtime files (created by the server, not committed)

## Build & run
    chmod +x scripts/*.sh        # once (Linux/macOS)
    scripts/run.sh build         # compile everything into bin/
    scripts/run.sh server        # terminal 1 (default port 5000, or: server 6000)
    scripts/run.sh client        # terminal 2, 3, ... (or: client <host> <port>)
    scripts/run.sh gui           # GUI client
    scripts/run.sh clean         # delete bin/ and data/

Without the scripts: `javac -d bin *.java tests/ChatTest.java`, then
`java -cp bin ChatServer [port]`, `java -cp bin ChatClient [host] [port]`, `java -cp bin ChatGUI`.

## Commands (in the client)
    <text>                         message everyone
    /msg <user> <text>             private message
    /g <group name> <text>         group message
    /group create|join|leave|members <name>
    /group list
    /users   /history [n]   /help   /quit

## Testing
    scripts/test.sh              # optional argument: port (default 5999)

The script builds the project, starts a fresh server in a temporary folder, runs
`tests/ChatTest.java`, restarts the server and checks persistence, then verifies that the
runtime files exist and passwords are not stored in clear text. It exits with code 0 only
if everything passes. The tests cover: registration/login errors, public messages with
timestamps, private-message privacy, groups (including names with spaces and non-member
blocking), concurrent registration of the same name, and persistence after restart.

## Files created at runtime (in `data/`)
    users.txt    registered users (salted PBKDF2 password hashes)
    history.log  all messages (reloaded on restart)
    groups.txt   groups and memberships (reloaded on restart)
    server.log   server event log

Older accounts with SHA-256 password hashes are upgraded to PBKDF2 the next time
the user logs in successfully.

## Git
`.gitignore` excludes compiled classes, `bin/`, `data/` and local logs. Keep runtime files
out of commits: `users.txt` contains password hashes and `history.log` may contain private messages.
Make sure `.gitignore` contains the line `/data/`.

## AI usage declaration
(Team: fill in which parts were AI-assisted and who can explain them.)
