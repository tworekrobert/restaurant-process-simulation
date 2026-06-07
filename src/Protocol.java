import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class Protocol {
    public static BufferedReader in(Socket s) throws IOException {
        return new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
    }

    public static PrintWriter out(Socket s) throws IOException {
        return new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
    }

    public static String[] split(String line) {
        return line.split("\\|", -1);
    }

    public static String join(String... parts) {
        return String.join("|", parts);
    }

    public static void send(PrintWriter out, String... parts) {
        out.println(join(parts));
    }
}
