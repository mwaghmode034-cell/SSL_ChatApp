# Profiling (simple, like gprof's flat profile)

`src/Prof.java` measures how long every important server method takes: number of calls, total time and average time.
It is switched off normally and costs nothing; `-Dprofile=true` turns it on.

## Run (one command)
```bash
scripts/profile.sh              # 20 clients x 500 messages
scripts/profile.sh 50 1000      # heavier load
```
Result is printed and saved in `docs/profiling/profile.txt`.

## What is measured
* **Server** (`profile.sh` does this): `register`, `login`, `hashPassword`, `saveUser`, `saveGroups`, `storeMessage`, `send`, `publicMessage`, `privateMessage`, `groupMessage`, `sendHistory`, and one row per command: `cmd /msg`, `cmd /g`, `cmd /users`, `cmd /history`, `cmd /group create | join | leave | members | list | mine`.
* **Console client:** `login round trip`, `send line`, `print server line`.
* **GUI:** `connect`, `sendInput`, `handleLine`, `updateList`, `updateAllGroups`, `appendMessage`, `parseMessage`, `setTarget`, `requestRefresh`, `openHistory`, `showHistoryMessages`.

## Profile the client or the GUI
Add `-Dprofile=true`, use the program normally, then quit it (type `/quit`, or close the GUI window). The table is printed and saved as `profile_ChatClient.txt` / `profile_ChatGUI.txt` in the folder you started it from.
```
java -Dprofile=true -cp bin ChatClient localhost 5000
java -Dprofile=true -cp bin ChatGUI
```

## Windows (without bash)
```
javac -d bin @sources.txt
java -Dprofile=true -cp bin ChatServer 5000      (terminal 1)
java -cp bin LoadTest 5000 20 500                (terminal 2)
java -cp bin ProfActions 5000                    (terminal 2, group commands etc.)
```
Then press Ctrl+C in terminal 1: the table is printed and saved as `profile.txt`.

## Reading the table
```
method                    calls     total ms       avg ms
```
* **calls** - how often the method ran. **total ms** - all time spent in it. **avg ms** - total / calls.
* Sorted by total time: the first rows are the bottlenecks.
* Time includes waiting for locks. Methods call each other (`handleCommand` contains `publicMessage`, which contains `send`), so rows overlap, and threads run in parallel, so totals can exceed the real run time.
* `authenticate` also contains the time the server waits for the user to type, so ignore it.
* `hashPassword` / `verifyPassword` are slow on purpose (password security).

## For the report
Paste the table, name the slowest method(s), and say what you would improve.
