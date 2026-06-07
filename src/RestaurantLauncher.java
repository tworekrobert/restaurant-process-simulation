import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Jednokomendowy launcher: uruchamia RestaurantServer (na losowym porcie), GUI (GuiApp), restockera
 * i N klientów jako osobne procesy.
 *
 * Przykład:
 * java -cp out RestaurantLauncher \
 *   --clients 20 --rounds 0 --waiters 3 \
 *   --tableCaps 2,2,4,5,5,4 \
 *   --ingredients 6 --ingredientsMax 12 \
 *   --restockEveryMs 1500 --restockAmount 1 \
 *   --mode RESTAURANT
 */
public class RestaurantLauncher {

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = parseArgs(args);

        int clients = iopt(opt, "clients", 20);
        // rounds<=0 -> pętla (nie kończy się po kilku sekundach)
        int rounds = iopt(opt, "rounds", 0);

        int waiters = iopt(opt, "waiters", 3);
        String tableCaps = opt.getOrDefault("tableCaps", "2,2,4,5,5,4");

        int ingredients = iopt(opt, "ingredients", 6);
        int ingredientsMax = iopt(opt, "ingredientsMax", 12);

        long restockEveryMs = lopt(opt, "restockEveryMs", 1500);
        int restockAmount = iopt(opt, "restockAmount", 1);

        String mode = opt.getOrDefault("mode", "RESTAURANT");

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
        Process rest = new ProcessBuilder(
                "java", "-cp", "out", "RestockerProcess",
                Integer.toString(port),
                Long.toString(restockEveryMs),
                Integer.toString(restockAmount), // ingAdd
                Integer.toString(restockAmount), // filetAdd
                Integer.toString(restockAmount), // soupAdd
                "2",                           // washCutlery
                "2"                            // washSpoons
        ).inheritIO().start();

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
}
