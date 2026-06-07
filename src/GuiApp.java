import javax.swing.*;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.net.Socket;

public class GuiApp {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: java GuiApp <port> <modeName>");
            return;
        }
        int port = Integer.parseInt(args[0]);
        String modeName = args[1];

        RestaurantPanel panel = new RestaurantPanel(modeName);

        // UI tworzymy na EDT
        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("Runner — " + modeName);
            f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            f.setContentPane(panel);
            f.pack();
            f.setLocationRelativeTo(null);
            f.setVisible(true);
        });

        // Czytanie SNAPSHOT w wątku sieciowym; aktualizacja GUI przez EDT
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setTcpNoDelay(true);
            BufferedReader in = Protocol.in(s);
            PrintWriter out = Protocol.out(s);

            Protocol.send(out, "SUBSCRIBE", "GUI");
            in.readLine();

            String line;
            while ((line = in.readLine()) != null) {
                final String snap = line;
                SwingUtilities.invokeLater(() -> panel.applySnapshot(snap));
            }
        }
    }
}
