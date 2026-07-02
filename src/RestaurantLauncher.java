import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Jednokomendowy launcher: uruchamia RestaurantServer (na losowym porcie), GUI (GuiApp), restockera
 * i N klientów jako osobne procesy.
 *
 * Przykład:
 * java -cp out RestaurantLauncher \
 *   --mode RUSH_HOUR
 */
public class RestaurantLauncher {

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = parseArgs(args);
        Scenario scenario = Scenario.from(opt.getOrDefault("mode", "NORMAL"));
        String mode = scenario.name;

        int clients = iopt(opt, "clients", scenario.clients);
        // rounds<=0 -> pętla (nie kończy się po kilku sekundach)
        int rounds = iopt(opt, "rounds", scenario.rounds);

        int waiters = iopt(opt, "waiters", scenario.waiters);
        String tableCaps = opt.getOrDefault("tableCaps", scenario.tableCaps);

        int ingredients = iopt(opt, "ingredients", scenario.ingredients);
        int ingredientsMax = iopt(opt, "ingredientsMax", scenario.ingredientsMax);

        long restockEveryMs = lopt(opt, "restockEveryMs", scenario.restockEveryMs);
        int restockAmount = iopt(opt, "restockAmount", scenario.restockAmount);
        int washCutlery = iopt(opt, "washCutlery", scenario.washCutlery);
        int washSpoons = iopt(opt, "washSpoons", scenario.washSpoons);

        // 1) start server
        List<String> serverCmd = new ArrayList<>();
        serverCmd.add("java");
        serverCmd.add("-cp");
        serverCmd.add("out");
        serverCmd.add("RestaurantServer");
        serverCmd.add("--tableCaps");
        serverCmd.add(tableCaps);
        serverCmd.add("--waiters");
        serverCmd.add(Integer.toString(waiters));
        serverCmd.add("--ingredients");
        serverCmd.add(Integer.toString(ingredients));
        serverCmd.add("--ingredientsMax");
        serverCmd.add(Integer.toString(ingredientsMax));

        Process server = new ProcessBuilder(serverCmd)
                .redirectErrorStream(true)
                .start();

        // odczytaj PORT=xxxxx z 1. linii serwera
        BufferedReader srvIn = new BufferedReader(new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8));
        String first = srvIn.readLine();
        if (first == null || !first.startsWith("PORT=")) {
            throw new IllegalStateException("Server nie zwrócił PORT=... (dostałem: " + first + ")");
        }
        int port = Integer.parseInt(first.substring("PORT=".length()).trim());
        System.out.println("[Launcher] Server PORT=" + port);
        System.out.println("[Launcher] Mode=" + mode
                + " clients=" + clients
                + " waiters=" + waiters
                + " tables=" + tableCaps
                + " ingredients=" + ingredients + "/" + ingredientsMax
                + " restockEveryMs=" + restockEveryMs
                + " rounds=" + rounds);

        // przepnij resztę logów serwera na stdout launchera
        Thread srvPump = new Thread(() -> pump("[Server] ", srvIn), "srv-pump");
        srvPump.setDaemon(true);
        srvPump.start();

        // 2) start GUI
        Process gui = new ProcessBuilder("java", "-cp", "out", "GuiApp", Integer.toString(port), mode)
                .inheritIO()
                .start();

        // 3) start restocker
        // Restocker odnawia składniki + potrawy i myje przybory
        Process restProcess;
        if (restockEveryMs > 0) {
            restProcess = new ProcessBuilder(
                    "java", "-cp", "out", "RestockerProcess",
                    Integer.toString(port),
                    Long.toString(restockEveryMs),
                    Integer.toString(restockAmount), // ingAdd
                    Integer.toString(restockAmount), // filetAdd
                    Integer.toString(restockAmount), // soupAdd
                    Integer.toString(washCutlery),
                    Integer.toString(washSpoons)
            ).inheritIO().start();
        } else {
            restProcess = null;
            System.out.println("[Launcher] Restocker OFF");
        }
        final Process rest = restProcess;

        // 4) start clients (z losowym rozmiarem grupy 1..maxCap)
        int maxCap = maxCap(tableCaps);
        Random rng = new Random();

        List<Process> clientProcs = new ArrayList<>();
        for (int i = 1; i <= clients; i++) {
            int grp = 1 + rng.nextInt(Math.max(1, maxCap));
            Process c = new ProcessBuilder(
                    "java", "-cp", "out", "ClientProcess",
                    Integer.toString(port),
                    Integer.toString(i),
                    Integer.toString(rounds),
                    Integer.toString(grp)
            ).inheritIO().start();
            clientProcs.add(c);

            // mały odstęp, żeby nie walić wszystkiego naraz w tej samej ms
            Thread.sleep(60);
        }

        // sprzątanie po Ctrl+C
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            destroyQuietly(rest);
            destroyQuietly(gui);
            for (Process p : clientProcs) destroyQuietly(p);
            destroyQuietly(server);
        }));

        // trzymamy launcher przy życiu
        server.waitFor();
    }

    private static void destroyQuietly(Process p) {
        try { if (p != null) p.destroy(); } catch (Exception ignored) {}
    }

    private static void pump(String prefix, BufferedReader br) {
        try {
            String line;
            while ((line = br.readLine()) != null) {
                System.out.println(prefix + line);
            }
        } catch (Exception ignored) {}
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--")) continue;
            String key = a.substring(2);
            String val = "true";
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                val = args[++i];
            }
            m.put(key, val);
        }
        return m;
    }

    private static int iopt(Map<String, String> opt, String k, int def) {
        try { return Integer.parseInt(opt.getOrDefault(k, Integer.toString(def))); }
        catch (Exception e) { return def; }
    }

    private static long lopt(Map<String, String> opt, String k, long def) {
        try { return Long.parseLong(opt.getOrDefault(k, Long.toString(def))); }
        catch (Exception e) { return def; }
    }

    private static int maxCap(String capsCsv) {
        int mx = 1;
        for (String s : capsCsv.split(",")) {
            try { mx = Math.max(mx, Integer.parseInt(s.trim())); } catch (Exception ignored) {}
        }
        return mx;
    }

    static class Scenario {
        final String name;
        final int clients;
        final int rounds;
        final int waiters;
        final String tableCaps;
        final int ingredients;
        final int ingredientsMax;
        final long restockEveryMs;
        final int restockAmount;
        final int washCutlery;
        final int washSpoons;

        Scenario(String name, int clients, int rounds, int waiters, String tableCaps,
                 int ingredients, int ingredientsMax, long restockEveryMs,
                 int restockAmount, int washCutlery, int washSpoons) {
            this.name = name;
            this.clients = clients;
            this.rounds = rounds;
            this.waiters = waiters;
            this.tableCaps = tableCaps;
            this.ingredients = ingredients;
            this.ingredientsMax = ingredientsMax;
            this.restockEveryMs = restockEveryMs;
            this.restockAmount = restockAmount;
            this.washCutlery = washCutlery;
            this.washSpoons = washSpoons;
        }

        static Scenario from(String modeRaw) {
            String mode = modeRaw == null ? "NORMAL" : modeRaw.trim().toUpperCase(Locale.ROOT);
            return switch (mode) {
                case "RUSH_HOUR", "STRESS", "STRESS_TEST" ->
                        new Scenario("RUSH_HOUR", 35, 0, 4, "2,2,4,4,6,6", 8, 16, 900, 2, 3, 3);
                case "LIMITED_RESOURCES", "LIMITED", "SCARCE" ->
                        new Scenario("LIMITED_RESOURCES", 24, 0, 2, "2,2,4", 3, 8, 2500, 1, 1, 1);
                case "NO_RESTOCK", "WITHOUT_RESTOCK" ->
                        new Scenario("NO_RESTOCK", 18, 0, 3, "2,2,4,4", 4, 8, 0, 0, 0, 0);
                case "SHORT_DEMO", "DEMO" ->
                        new Scenario("SHORT_DEMO", 12, 1, 3, "2,4,4", 10, 10, 1000, 2, 2, 2);
                case "NORMAL", "RESTAURANT" ->
                        new Scenario("NORMAL", 20, 0, 3, "2,2,4,5,5,4", 6, 12, 1500, 1, 2, 2);
                default -> {
                    System.out.println("[Launcher] Unknown mode '" + modeRaw + "', using NORMAL.");
                    yield new Scenario("NORMAL", 20, 0, 3, "2,2,4,5,5,4", 6, 12, 1500, 1, 2, 2);
                }
            };
        }
    }
}
