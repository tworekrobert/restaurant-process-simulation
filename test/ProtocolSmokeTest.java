import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

/**
 * Smoke test for the TCP protocol and the resource allocation rules.
 *
 * It starts a real RestaurantServer process on a dynamic port with a small,
 * fully known configuration and talks to it over sockets, exactly like the
 * client, restocker and GUI processes do. No external libraries are needed.
 *
 * Run with: make test
 */
public class ProtocolSmokeTest {

    // Server configuration used by the test:
    //   tables:  #0 (capacity 2), #1 (capacity 4)
    //   1 waiter, 1 ingredient (max 3), 1 clean cutlery item (max 4)
    private static final String[] SERVER_ARGS = {
            "--tableCaps", "2,4",
            "--waiters", "1",
            "--ingredients", "1", "--ingredientsMax", "3",
            "--filet", "5", "--soup", "5",
            "--cutleryClean", "1", "--cutleryMax", "4",
            "--spoonClean", "5", "--spoonMax", "5"
    };

    // Field positions inside SNAPSHOT|... lines.
    private static final int SNAP_INGREDIENTS = 1;
    private static final int SNAP_INGREDIENTS_MAX = 2;
    private static final int SNAP_CUTLERY_CLEAN = 7;
    private static final int SNAP_CUTLERY_DIRTY = 8;

    private static int checks = 0;
    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        Process server = startServer();
        try {
            int port = readPort(server);
            System.out.println("Server started on port " + port);
            runScenario(port);
        } finally {
            server.destroyForcibly();
        }

        System.out.println();
        System.out.println(checks + " checks, " + failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void runScenario(int port) throws Exception {
        try (Conn gui = new Conn(port);
             Conn c1 = new Conn(port);
             Conn c2 = new Conn(port);
             Conn c3 = new Conn(port);
             Conn c4 = new Conn(port);
             Conn restocker = new Conn(port)) {

            section("Handshake");
            try (Conn bogus = new Conn(port)) {
                String reply = bogus.call("BOGUS");
                expect("first line must be HELLO", reply != null && reply.startsWith("ERR"), reply);
            }
            expectEq("GUI subscribes", "OK|GUI", gui.call("SUBSCRIBE", "GUI"));
            expectEq("client 1 says hello", "OK|CLIENT", c1.call("HELLO", "1", "CLIENT", "2"));
            expectEq("client 2 says hello", "OK|CLIENT", c2.call("HELLO", "2", "CLIENT", "2"));
            expectEq("client 3 says hello", "OK|CLIENT", c3.call("HELLO", "3", "CLIENT", "4"));
            expectEq("client 4 says hello", "OK|CLIENT", c4.call("HELLO", "4", "CLIENT", "4"));
            expectEq("restocker says hello", "OK|RESTOCKER", restocker.call("HELLO", "99", "RESTOCKER"));
            expectEq("unknown command is rejected", "ERR|Unknown cmd", c1.call("DANCE"));

            section("Table allocation");
            expectEq("client 1 arrives", "ACK|ARRIVE", c1.call("ARRIVE"));
            expectEq("client 1 (group of 2) gets table 0", "TABLE|0", c1.call("REQUEST_TABLE", "2"));
            expectEq("asking again returns the same table", "TABLE|0", c1.call("REQUEST_TABLE", "2"));
            c2.call("ARRIVE");
            expectEq("client 2 (group of 2) gets table 1, table 0 is full", "TABLE|1", c2.call("REQUEST_TABLE", "2"));
            c3.call("ARRIVE");
            expectEq("client 3 (group of 4) has to wait", "WAIT|TABLE", c3.call("REQUEST_TABLE", "4"));
            expectEq("no waiter without a table", "WAIT|NO_TABLE", c3.call("REQUEST_WAITER", "FILET"));
            expectEq("client 2 leaves the table", "ACK|LEAVE_TABLE", c2.call("LEAVE_TABLE"));
            expectEq("client 3 gets the freed table 1", "TABLE|1", c3.call("REQUEST_TABLE", "4"));

            section("Service and shared resources");
            expectEq("client 1 is served filet with cutlery", "SERVED|0|FILET|CUTLERY", c1.call("REQUEST_WAITER", "FILET"));
            expectEq("the only waiter is busy", "WAIT|WAITER", c3.call("REQUEST_WAITER", "SOUP"));
            expectEq("ingredients run out once the waiter is free",
                    "WAIT|INGREDIENTS", pollWhile(c3, "WAIT|WAITER", "REQUEST_WAITER", "SOUP"));

            section("Restocker");
            restocker.send("RESTOCK", "ING", "100");
            String[] snap = awaitSnapshot(gui, s -> Integer.parseInt(s[SNAP_INGREDIENTS]) > 0);
            expectEq("restocking is capped at the maximum",
                    snap[SNAP_INGREDIENTS_MAX], snap[SNAP_INGREDIENTS]);
            expectEq("no clean cutlery left for filet",
                    "WAIT|CUTLERY", pollWhile(c3, "WAIT|INGREDIENTS", "REQUEST_WAITER", "FILET"));

            section("Cutlery cycle (clean -> dirty -> washed)");
            expectEq("client 1 starts eating", "ACK|EATING", c1.call("START_EATING"));
            expectEq("client 1 finishes eating", "ACK|DONE", c1.call("DONE_EATING"));
            snap = awaitSnapshot(gui, s -> Integer.parseInt(s[SNAP_CUTLERY_DIRTY]) == 1);
            expectEq("used cutlery becomes dirty", "0/1",
                    snap[SNAP_CUTLERY_CLEAN] + "/" + snap[SNAP_CUTLERY_DIRTY]);
            restocker.send("WASH", "CUTLERY", "1");
            snap = awaitSnapshot(gui, s -> Integer.parseInt(s[SNAP_CUTLERY_CLEAN]) == 1);
            expectEq("washing makes cutlery clean again", "1/0",
                    snap[SNAP_CUTLERY_CLEAN] + "/" + snap[SNAP_CUTLERY_DIRTY]);
            expectEq("client 3 can now be served filet",
                    "SERVED|0|FILET|CUTLERY", pollWhile(c3, "WAIT", "REQUEST_WAITER", "FILET"));

            section("Disconnect cleanup");
            c4.call("ARRIVE");
            expectEq("table 1 is taken by client 3", "WAIT|TABLE", c4.call("REQUEST_TABLE", "4"));
            c3.close();
            expectEq("table is released when a client disconnects",
                    "TABLE|1", pollWhile(c4, "WAIT", "REQUEST_TABLE", "4"));
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void section(String title) {
        System.out.println();
        System.out.println("== " + title);
    }

    private static void expect(String name, boolean ok, String detail) {
        checks++;
        if (ok) {
            System.out.println("  PASS  " + name);
        } else {
            failures++;
            System.out.println("  FAIL  " + name + "  (got: " + detail + ")");
        }
    }

    private static void expectEq(String name, String expected, String actual) {
        checks++;
        if (expected.equals(actual)) {
            System.out.println("  PASS  " + name);
        } else {
            failures++;
            System.out.println("  FAIL  " + name + "  (expected: " + expected + ", got: " + actual + ")");
        }
    }

    /** Repeats a command until the reply no longer starts with {@code whilePrefix}. */
    private static String pollWhile(Conn c, String whilePrefix, String... command) throws Exception {
        long deadline = System.currentTimeMillis() + 8000;
        String reply = c.call(command);
        while (reply != null && reply.startsWith(whilePrefix) && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
            reply = c.call(command);
        }
        return reply;
    }

    /** Reads GUI snapshots until one satisfies the condition (or about 8 seconds pass). */
    private static String[] awaitSnapshot(Conn gui, Predicate<String[]> condition) throws IOException {
        String[] last = null;
        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline) {
            String line = gui.readLine();
            if (line == null) break;
            if (!line.startsWith("SNAPSHOT|")) continue;
            last = Protocol.split(line);
            if (condition.test(last)) return last;
        }
        if (last == null) throw new IOException("No snapshot received from the server");
        return last;
    }

    private static Process startServer() throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(System.getProperty("java.home") + "/bin/java");
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add("RestaurantServer");
        cmd.addAll(Arrays.asList(SERVER_ARGS));
        return new ProcessBuilder(cmd)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    private static int readPort(Process server) throws IOException {
        BufferedReader out = new BufferedReader(
                new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8));
        String line;
        while ((line = out.readLine()) != null) {
            if (line.startsWith("PORT=")) {
                return Integer.parseInt(line.substring("PORT=".length()).trim());
            }
        }
        throw new IOException("Server exited without printing its port");
    }

    /** One client connection speaking the line-based protocol. */
    private static final class Conn implements AutoCloseable {
        private final Socket socket;
        private final BufferedReader in;
        private final PrintWriter out;

        Conn(int port) throws IOException {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(5000);
            in = Protocol.in(socket);
            out = Protocol.out(socket);
        }

        void send(String... parts) {
            Protocol.send(out, parts);
        }

        String readLine() throws IOException {
            return in.readLine();
        }

        String call(String... parts) throws IOException {
            send(parts);
            return in.readLine();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
