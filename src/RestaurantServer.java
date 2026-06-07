import java.io.BufferedReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Serwer stanu restauracji.
 *
 * Typy zasobów (zgodnie z wymaganiami):
 *  - stałe: STOŁY (mają pojemność i zajęte miejsca)
 *  - przenoszone: KELNERZY (pula wolnych kelnerów)
 *  - odnawialne: składniki, potrawy (filet/zupa) oraz przybory (sztućce/łyżki) w modelu clean/dirty
 */
public class RestaurantServer {

    static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    static final ExecutorService pool = Executors.newCachedThreadPool();
    static final AtomicInteger connId = new AtomicInteger(0);

    public static void main(String[] args) throws Exception {
        // Defaults
        String tableCapsStr = "2,2,4,5,5,4";
        int waiters = 3;

        // renewable: ingredients
        int ingredients = 6;
        int ingredientsMax = 12;

        // renewable: meals (ready portions)
        int filet = 12;
        int filetMax = 12;
        int soup = 12;
        int soupMax = 12;

        // renewable: utensils (clean/dirty)
        int cutleryClean = 8;
        int cutleryDirty = 0;
        int cutleryMax = 16;

        int spoonClean = 8;
        int spoonDirty = 0;
        int spoonMax = 16;

        // Parse flags
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--tableCaps" -> {
                    if (i + 1 < args.length) tableCapsStr = args[++i];
                }
                case "--waiters" -> {
                    if (i + 1 < args.length) waiters = Integer.parseInt(args[++i]);
                }
                case "--ingredients" -> {
                    if (i + 1 < args.length) ingredients = Integer.parseInt(args[++i]);
                }
                case "--ingredientsMax" -> {
                    if (i + 1 < args.length) ingredientsMax = Integer.parseInt(args[++i]);
                }
                case "--filet" -> {
                    if (i + 1 < args.length) filet = Integer.parseInt(args[++i]);
                }
                case "--filetMax" -> {
                    if (i + 1 < args.length) filetMax = Integer.parseInt(args[++i]);
                }
                case "--soup" -> {
                    if (i + 1 < args.length) soup = Integer.parseInt(args[++i]);
                }
                case "--soupMax" -> {
                    if (i + 1 < args.length) soupMax = Integer.parseInt(args[++i]);
                }
                case "--cutleryClean" -> {
                    if (i + 1 < args.length) cutleryClean = Integer.parseInt(args[++i]);
                }
                case "--cutleryMax" -> {
                    if (i + 1 < args.length) cutleryMax = Integer.parseInt(args[++i]);
                }
                case "--spoonClean" -> {
                    if (i + 1 < args.length) spoonClean = Integer.parseInt(args[++i]);
                }
                case "--spoonMax" -> {
                    if (i + 1 < args.length) spoonMax = Integer.parseInt(args[++i]);
                }
                default -> {
                    // ignore unknown
                }
            }
        }

        int[] caps = parseCaps(tableCapsStr);
        State st = new State(
                caps,
                waiters,
                ingredients,
                ingredientsMax,
                filet,
                filetMax,
                soup,
                soupMax,
                cutleryClean,
                cutleryDirty,
                cutleryMax,
                spoonClean,
                spoonDirty,
                spoonMax
        );

        ServerSocket ss = new ServerSocket(0);
        int port = ss.getLocalPort();
        System.out.println("PORT=" + port);
        System.out.flush();

        scheduler.scheduleAtFixedRate(() -> broadcastSnapshot(st), 0L, 200L, TimeUnit.MILLISECONDS);

        while (true) {
            Socket s = ss.accept();
            s.setTcpNoDelay(true);
            pool.submit(() -> handleConn(s, st, connId.incrementAndGet()));
        }
    }

    static int[] parseCaps(String s) {
        String[] p = s.split(",");
        int[] caps = new int[p.length];
        for (int i = 0; i < p.length; i++) caps[i] = Integer.parseInt(p[i].trim());
        return caps;
    }

    static void handleConn(Socket sock, State st, int conn) {
        int clientId = -1;
        try (Socket s = sock) {
            BufferedReader in = Protocol.in(s);
            PrintWriter out = Protocol.out(s);

            String first = in.readLine();
            if (first == null) return;

            String[] hello = Protocol.split(first);

            // GUI subscribe
            if (hello.length >= 2 && hello[0].equals("SUBSCRIBE") && hello[1].equals("GUI")) {
                synchronized (st) {
                    st.guiSubs.add(out);
                }
                Protocol.send(out, "OK", "GUI");
                while (in.readLine() != null) {
                    // keep
                }
                synchronized (st) {
                    st.guiSubs.remove(out);
                }
                return;
            }

            // Normal: HELLO|<id>|<ROLE>|[defaultGroupSize]
            if (hello.length < 2 || !hello[0].equals("HELLO")) {
                Protocol.send(out, "ERR", "Expected HELLO|<id>|<ROLE>");
                return;
            }

            clientId = Integer.parseInt(hello[1]);
            String role = (hello.length >= 3) ? hello[2] : "CLIENT";

            if (role.equals("RESTOCKER")) {
                Protocol.send(out, "OK", "RESTOCKER");
                String line;
                while ((line = in.readLine()) != null) {
                    String[] p = Protocol.split(line);
                    if (p.length >= 3 && p[0].equals("RESTOCK")) {
                        String what = p[1];
                        int add = Integer.parseInt(p[2]);
                        synchronized (st) {
                            switch (what) {
                                case "ING" -> st.ingredients = Math.min(st.ingredientsMax, st.ingredients + add);
                                case "FILET" -> st.filet = Math.min(st.filetMax, st.filet + add);
                                case "SOUP" -> st.soup = Math.min(st.soupMax, st.soup + add);
                            }
                        }
                    } else if (p.length >= 3 && p[0].equals("WASH")) {
                        String what = p[1];
                        int n = Integer.parseInt(p[2]);
                        synchronized (st) {
                            if (what.equals("CUTLERY")) {
                                int moved = Math.min(n, st.cutleryDirty);
                                st.cutleryDirty -= moved;
                                st.cutleryClean = Math.min(st.cutleryMax, st.cutleryClean + moved);
                            } else if (what.equals("SPOON") || what.equals("SPOONS")) {
                                int moved = Math.min(n, st.spoonDirty);
                                st.spoonDirty -= moved;
                                st.spoonClean = Math.min(st.spoonMax, st.spoonClean + moved);
                            }
                        }
                    }
                }
                return;
            }

            int defaultGroupSize = 1;
            if (hello.length >= 4) {
                try { defaultGroupSize = Math.max(1, Integer.parseInt(hello[3])); } catch (Exception ignored) {}
            }

            // client
            synchronized (st) {
                st.clients.putIfAbsent(clientId, new ClientInfo(clientId));
                st.clients.get(clientId).groupSize = defaultGroupSize;
            }
            Protocol.send(out, "OK", "CLIENT");

            String line;
            while ((line = in.readLine()) != null) {
                String[] cmd = Protocol.split(line);
                String c = cmd[0];

                if (c.equals("ARRIVE")) onArrive(st, clientId, out);
                else if (c.equals("REQUEST_TABLE")) {
                    int groupSize = (cmd.length >= 2) ? parseIntSafe(cmd[1], 1) : -1;
                    onRequestTable(st, clientId, groupSize, out);
                }
                else if (c.equals("REQUEST_WAITER")) {
                    String meal = (cmd.length >= 2) ? cmd[1] : "";
                    onRequestWaiter(st, clientId, meal, out);
                }
                else if (c.equals("START_EATING")) onStartEating(st, clientId, out);
                else if (c.equals("DONE_EATING")) onDoneEating(st, clientId, out);
                else if (c.equals("LEAVE_TABLE")) onLeaveTable(st, clientId, out);
                else if (c.equals("QUIT") || c.equals("LEAVE")) {
                    Protocol.send(out, "ACK", "QUIT");
                    break;
                }
                else {
                    Protocol.send(out, "ERR", "Unknown cmd");
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (clientId >= 0) {
                cleanupClient(st, clientId);
            }
        }
    }

    static int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    static void cleanupClient(State st, int id) {
        synchronized (st) {
            ClientInfo c = st.clients.get(id);
            if (c == null) return;
            if (c.tableId >= 0 && c.tableId < st.tables.size()) {
                st.tables.get(c.tableId).removeGroup(id);
            }
            c.tableId = -1;
            c.state = CState.OUTSIDE;
        }
    }

    static void onArrive(State st, int id, PrintWriter out) {
        synchronized (st) {
            ClientInfo c = st.clients.get(id);
            c.state = CState.WAIT_TABLE;
            c.waitStartNs = System.nanoTime();
            c.meal = "";
            c.utensil = "";
        }
        Protocol.send(out, "ACK", "ARRIVE");
    }

    static void onRequestTable(State st, int id, int groupSize, PrintWriter out) {
        synchronized (st) {
            ClientInfo c = st.clients.get(id);

            // already has table
            if (c.tableId >= 0) {
                Protocol.send(out, "TABLE", Integer.toString(c.tableId));
                return;
            }

            if (groupSize <= 0) groupSize = c.groupSize;
            if (groupSize <= 0) groupSize = 1;
            c.groupSize = groupSize;

            Table best = null;
            for (Table t : st.tables) {
                if (t.freeSeats() >= groupSize) {
                    best = t;
                    break;
                }
            }

            if (best == null) {
                c.state = CState.WAIT_TABLE;
                Protocol.send(out, "WAIT", "TABLE");
                return;
            }

            best.addGroup(id, groupSize);
            c.tableId = best.id;
            c.state = CState.SEATED;
            c.waitStartNs = System.nanoTime(); // start waiting for waiter
            Protocol.send(out, "TABLE", Integer.toString(c.tableId));
        }
    }

    static String normalizeMeal(String meal) {
        if (meal == null) return "";
        meal = meal.trim().toUpperCase(Locale.ROOT);
        if (meal.equals("FILET") || meal.equals("STEAK")) return "FILET";
        if (meal.equals("SOUP") || meal.equals("ZUPA")) return "SOUP";
        return "";
    }

    static void onRequestWaiter(State st, int id, String mealRaw, PrintWriter out) {
        synchronized (st) {
            ClientInfo c = st.clients.get(id);
            c.state = CState.WAIT_WAITER;

            if (c.tableId < 0) {
                Protocol.send(out, "WAIT", "NO_TABLE");
                return;
            }
            if (st.freeWaiters.isEmpty()) {
                Protocol.send(out, "WAIT", "WAITER");
                return;
            }
            if (st.ingredients <= 0) {
                Protocol.send(out, "WAIT", "INGREDIENTS");
                return;
            }

            String meal = normalizeMeal(mealRaw);
            if (meal.isEmpty()) {
                meal = ThreadLocalRandom.current().nextBoolean() ? "FILET" : "SOUP";
            }

            String utensil;
            if (meal.equals("FILET")) {
                if (st.filet <= 0) {
                    Protocol.send(out, "WAIT", "FILET");
                    return;
                }
                if (st.cutleryClean <= 0) {
                    Protocol.send(out, "WAIT", "CUTLERY");
                    return;
                }
                utensil = "CUTLERY";
            } else {
                if (st.soup <= 0) {
                    Protocol.send(out, "WAIT", "SOUP");
                    return;
                }
                if (st.spoonClean <= 0) {
                    Protocol.send(out, "WAIT", "SPOON");
                    return;
                }
                utensil = "SPOON";
            }

            int w = st.freeWaiters.removeFirst();

            // consume resources
            st.ingredients--;
            if (meal.equals("FILET")) {
                st.filet--;
                st.cutleryClean--;
            } else {
                st.soup--;
                st.spoonClean--;
            }

            long waitMs = (System.nanoTime() - c.waitStartNs) / 1_000_000L;
            c.totalWaitMs += waitMs;
            c.meals++;
            c.meal = meal;
            c.utensil = utensil;
            c.state = CState.SERVED;

            Protocol.send(out, "SERVED", Integer.toString(w), meal, utensil);

            // waiter becomes free after some service time
            final int waiterId = w;
            scheduler.schedule(() -> {
                synchronized (st) {
                    st.freeWaiters.addLast(waiterId);
                }
            }, 1500L, TimeUnit.MILLISECONDS);
        }
    }

    static void onStartEating(State st, int id, PrintWriter out) {
        synchronized (st) {
            ClientInfo c = st.clients.get(id);
            c.state = CState.EATING;
        }
        Protocol.send(out, "ACK", "EATING");
    }

    static void onDoneEating(State st, int id, PrintWriter out) {
        synchronized (st) {
            ClientInfo c = st.clients.get(id);
            c.state = CState.DONE;

            // return utensils as dirty (renewable)
            if ("CUTLERY".equals(c.utensil)) {
                if (st.cutleryDirty < st.cutleryMax) st.cutleryDirty++;
            } else if ("SPOON".equals(c.utensil)) {
                if (st.spoonDirty < st.spoonMax) st.spoonDirty++;
            }

            c.waitStartNs = System.nanoTime();
        }
        Protocol.send(out, "ACK", "DONE");
    }

    static void onLeaveTable(State st, int id, PrintWriter out) {
        synchronized (st) {
            ClientInfo c = st.clients.get(id);
            if (c.tableId >= 0 && c.tableId < st.tables.size()) {
                st.tables.get(c.tableId).removeGroup(id);
            }
            c.tableId = -1;
            c.state = CState.OUTSIDE;
            c.meal = "";
            c.utensil = "";
        }
        Protocol.send(out, "ACK", "LEAVE_TABLE");
    }

    static void broadcastSnapshot(State st) {
        synchronized (st) {
            StringBuilder sb = new StringBuilder();
            sb.append("SNAPSHOT|");

            sb.append(st.ingredients).append("|").append(st.ingredientsMax).append("|");
            sb.append(st.filet).append("|").append(st.filetMax).append("|");
            sb.append(st.soup).append("|").append(st.soupMax).append("|");

            sb.append(st.cutleryClean).append("|").append(st.cutleryDirty).append("|").append(st.cutleryMax).append("|");
            sb.append(st.spoonClean).append("|").append(st.spoonDirty).append("|").append(st.spoonMax).append("|");

            sb.append(st.freeWaiters.size()).append("|").append(st.tables.size()).append("|");

            // TABLES: id,used,cap,occList
            for (int i = 0; i < st.tables.size(); i++) {
                Table t = st.tables.get(i);
                sb.append(t.id).append(",").append(t.used).append(",").append(t.cap).append(",");
                if (!t.groups.isEmpty()) {
                    List<Integer> occ = new ArrayList<>(t.groups.keySet());
                    Collections.sort(occ);
                    for (int j = 0; j < occ.size(); j++) {
                        int cid = occ.get(j);
                        int grp = t.groups.getOrDefault(cid, 1);
                        sb.append(cid).append(":").append(grp);
                        if (j + 1 < occ.size()) sb.append(".");
                    }
                }
                if (i + 1 < st.tables.size()) sb.append(";");
            }

            sb.append("|");

            // CLIENTS: id,state,tableId,avgWaitMs,grp,meal,utensil
            List<ClientInfo> list = new ArrayList<>(st.clients.values());
            list.sort(Comparator.comparingInt(c -> c.id));
            for (int i = 0; i < list.size(); i++) {
                ClientInfo c = list.get(i);
                sb.append(c.id).append(",")
                        .append(c.state.name()).append(",")
                        .append(c.tableId).append(",")
                        .append(String.format(Locale.US, "%.0f", c.avgWaitMs())).append(",")
                        .append(c.groupSize).append(",")
                        .append(nullToEmpty(c.meal)).append(",")
                        .append(nullToEmpty(c.utensil));
                if (i + 1 < list.size()) sb.append(";");
            }

            String snap = sb.toString();

            Iterator<PrintWriter> it = st.guiSubs.iterator();
            while (it.hasNext()) {
                PrintWriter w = it.next();
                try {
                    w.println(snap);
                    w.flush();
                } catch (Exception e) {
                    it.remove();
                }
            }
        }
    }

    static String nullToEmpty(String s) {
        return (s == null) ? "" : s;
    }

    // ===== DATA =====

    static class State {
        final List<Table> tables = new ArrayList<>();
        final Deque<Integer> freeWaiters = new ArrayDeque<>();

        int ingredients;
        int ingredientsMax;

        int filet;
        int filetMax;

        int soup;
        int soupMax;

        int cutleryClean;
        int cutleryDirty;
        int cutleryMax;

        int spoonClean;
        int spoonDirty;
        int spoonMax;

        final Map<Integer, ClientInfo> clients = new HashMap<>();
        final List<PrintWriter> guiSubs = new ArrayList<>();

        State(int[] tableCaps, int waiters, int ingredients, int ingredientsMax,
              int filet, int filetMax,
              int soup, int soupMax,
              int cutleryClean, int cutleryDirty, int cutleryMax,
              int spoonClean, int spoonDirty, int spoonMax) {

            for (int i = 0; i < tableCaps.length; i++) {
                tables.add(new Table(i, tableCaps[i]));
            }
            for (int i = 0; i < waiters; i++) freeWaiters.addLast(i);

            this.ingredients = ingredients;
            this.ingredientsMax = ingredientsMax;

            this.filet = filet;
            this.filetMax = filetMax;

            this.soup = soup;
            this.soupMax = soupMax;

            this.cutleryClean = cutleryClean;
            this.cutleryDirty = cutleryDirty;
            this.cutleryMax = cutleryMax;

            this.spoonClean = spoonClean;
            this.spoonDirty = spoonDirty;
            this.spoonMax = spoonMax;
        }
    }

    static class ClientInfo {
        final int id;
        CState state = CState.OUTSIDE;
        int tableId = -1;

        long waitStartNs = 0;
        long totalWaitMs = 0;
        int meals = 0;

        int groupSize = 1;

        String meal = "";
        String utensil = "";

        ClientInfo(int id) {
            this.id = id;
        }

        double avgWaitMs() {
            return meals == 0 ? 0.0 : (double) totalWaitMs / (double) meals;
        }
    }

    enum CState {
        OUTSIDE,
        WAIT_TABLE,
        SEATED,
        WAIT_WAITER,
        SERVED,
        EATING,
        DONE
    }

    static class Table {
        final int id;
        final int cap;
        int used = 0;

        // clientId -> groupSize
        final Map<Integer, Integer> groups = new HashMap<>();

        Table(int id, int cap) {
            this.id = id;
            this.cap = cap;
        }

        int freeSeats() {
            return cap - used;
        }

        void addGroup(int clientId, int groupSize) {
            if (groupSize <= 0) groupSize = 1;
            if (groups.containsKey(clientId)) return;
            used += groupSize;
            groups.put(clientId, groupSize);
        }

        void removeGroup(int clientId) {
            Integer g = groups.remove(clientId);
            if (g != null) used = Math.max(0, used - g);
        }
    }
}
