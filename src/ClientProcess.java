import java.io.BufferedReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Proces klienta.
 *
 * Cel: symulacja ma trwać długo i być czytelna w GUI.
 * - rounds <= 0  -> pętla nieskończona
 * - partySize <= 0 -> losowy rozmiar grupy (1..4)
 */
public class ClientProcess {

    static void sleepMs(int min, int max) throws InterruptedException {
        int ms = ThreadLocalRandom.current().nextInt(min, max + 1);
        Thread.sleep(ms);
    }

    static String randomMeal() {
        return ThreadLocalRandom.current().nextBoolean() ? "FILET" : "SOUP";
    }

    static int randomPartySize() {
        return ThreadLocalRandom.current().nextInt(1, 5); // 1..4
    }

    // usage: java ClientProcess <port> <id> <rounds> [partySize]
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: java ClientProcess <port> <id> <rounds> [partySize]");
            System.err.println("  rounds<=0  -> nieskończona pętla");
            System.err.println("  partySize<=0 -> losowa grupa (1..4)");
            return;
        }

        int port = Integer.parseInt(args[0]);
        int id = Integer.parseInt(args[1]);
        int rounds = Integer.parseInt(args[2]);

        int partyArg = (args.length >= 4) ? Integer.parseInt(args[3]) : 0;
        int partySize = (partyArg <= 0) ? randomPartySize() : Math.max(1, partyArg);
        if (partySize > 4) partySize = 4;

        try (Socket sock = new Socket("127.0.0.1", port)) {
            sock.setTcpNoDelay(true);
            BufferedReader in = Protocol.in(sock);
            PrintWriter out = Protocol.out(sock);

            // HELLO|id|CLIENT|partySize
            Protocol.send(out, "HELLO", Integer.toString(id), "CLIENT", Integer.toString(partySize));
            in.readLine(); // OK|CLIENT

            int cycle = 0;
            while (rounds <= 0 || cycle < rounds) {
                // Klient wchodzi do restauracji
                Protocol.send(out, "ARRIVE");
                in.readLine(); // ACK|ARRIVE

                // (opcjonalnie) losuj rozmiar grupy co cykl jeśli partyArg<=0
                int group = (partyArg <= 0) ? randomPartySize() : partySize;

                // Czeka na stolik
                while (true) {
                    Protocol.send(out, "REQUEST_TABLE", Integer.toString(group));
                    String resp = in.readLine();
                    if (resp == null) return;
                    String[] p = Protocol.split(resp);
                    if (p.length > 0 && p[0].equals("TABLE")) break;
                    sleepMs(700, 1400);
                }

                // Przegląda menu / czeka chwilę
                sleepMs(1000, 3000);

                // Zamawia danie
                String meal = randomMeal();

                // Czeka na kelnera + zasoby (składniki, potrawa, przybory)
                while (true) {
                    Protocol.send(out, "REQUEST_WAITER", meal);
                    String resp = in.readLine();
                    if (resp == null) return;
                    String[] p = Protocol.split(resp);

                    if (p.length > 0 && p[0].equals("SERVED")) break; // SERVED|waiterId|meal|utensil

                    // WAIT|...
                    sleepMs(600, 1400);
                }

                // Kelner podał – klient zaczyna jeść
                sleepMs(400, 1200);
                Protocol.send(out, "START_EATING");
                in.readLine(); // ACK|EATING

                // Długie jedzenie – żeby było widać procesy
                sleepMs(4000, 10000);

                Protocol.send(out, "DONE_EATING");
                in.readLine(); // ACK|DONE

                // Płatność / zbieranie się
                sleepMs(1200, 2800);

                // Zwolnij stolik (ważne dla wizualizacji zajętości!)
                Protocol.send(out, "LEAVE_TABLE");
                in.readLine(); // ACK|LEAVE_TABLE

                // Przerwa poza restauracją
                sleepMs(2500, 6500);

                cycle++;
            }

            Protocol.send(out, "QUIT");
            in.readLine(); // ACK|QUIT
        }
    }
}
