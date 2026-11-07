# Multi-client Chat (initial prototype)

Requires Java 8+.

## Features
- Register and log in with a username and password.
- Chat publicly with users connected to the server.
- Send private messages, including to offline users (they can view them in history).
- Create and manage chat groups, and send messages to group members.
- View online users, list groups, and retrieve recent message history.
- Persist user records and chat history across server restarts.

## Build & run
    javac *.java
    java ChatServer          # terminal 1 (port 5000)
    java ChatClient          # terminal 2, 3, ... (optionally: java ChatClient <host> <port>)

## Commands (in the client)
    <text>                         message everyone
    /msg <user> <text>             private message
    /g <group> <text>              group message
    /group create|join|leave|members <name>
    /group list
    /users   /history [n]   /help   /quit

## Files created at runtime
    users.txt    registered users (SHA-256 password hashes)
    history.log  all messages (reloaded on restart)
    server.log   server event log

## Git
The included `.gitignore` excludes compiled Java classes, build output, and local
user, chat-history, and server-log files. Keep those runtime files out of commits;
`users.txt` contains password hashes and `history.log` may contain private messages.

