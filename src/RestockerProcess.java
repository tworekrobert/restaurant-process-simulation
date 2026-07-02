import java.io.BufferedReader;
import java.io.PrintWriter;
import java.net.Socket;

/**
 * Proces odnawiający zasoby (kuchnia + zmywak).
 *
 * usage:
 *   java RestockerProcess <port> <ms> [ingAdd] [filetAdd] [soupAdd] [washCutlery] [washSpoons]
 *
 * - RESTOCK: zasoby zużywalne (odnawialne): składniki / porcje filet / porcje zupa
 * - WASH: zasoby odnawialne w modelu clean/dirty: sztućce i łyżki
 */
public class RestockerProcess {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: java RestockerProcess <port> <ms> [ingAdd] [filetAdd] [soupAdd] [washCutlery] [washSpoons]");
            return;
        }

        int port = Integer.parseInt(args[0]);
        int ms = Integer.parseInt(args[1]);

        int ingAdd = (args.length >= 3) ? Integer.parseInt(args[2]) : 1;
        int filetAdd = (args.length >= 4) ? Integer.parseInt(args[3]) : 1;
        int soupAdd = (args.length >= 5) ? Integer.parseInt(args[4]) : 1;
        int washCutlery = (args.length >= 6) ? Integer.parseInt(args[5]) : 2;
        int washSpoons = (args.length >= 7) ? Integer.parseInt(args[6]) : 2;

        if (ms < 50) ms = 50;

        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setTcpNoDelay(true);
            BufferedReader in = Protocol.in(s);
            PrintWriter out = Protocol.out(s);

            Protocol.send(out, "HELLO", "9000", "RESTOCKER");
            in.readLine(); // OK|RESTOCKER

            while (true) {
                Thread.sleep(ms);

                if (ingAdd > 0) Protocol.send(out, "RESTOCK", "ING", Integer.toString(ingAdd));
                if (filetAdd > 0) Protocol.send(out, "RESTOCK", "FILET", Integer.toString(filetAdd));
                if (soupAdd > 0) Protocol.send(out, "RESTOCK", "SOUP", Integer.toString(soupAdd));

                if (washCutlery > 0) Protocol.send(out, "WASH", "CUTLERY", Integer.toString(washCutlery));
                if (washSpoons > 0) Protocol.send(out, "WASH", "SPOON", Integer.toString(washSpoons));
            }
        }
    }
}
