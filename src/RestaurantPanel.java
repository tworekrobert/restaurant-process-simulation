import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

/**
 * Panel wizualizacji restauracji.
 *
 * Wersja "czytelna":
 * - polskie opisy
 * - dłuższa, płynna wizualizacja (to realizują czasy w ClientProcess)
 * - tabela po prawej: ID -> stolik -> danie -> przybory -> co robi (stan)
 */
public class RestaurantPanel extends JPanel {

    // ===== View models =====

    static class ClientView {
        int id;
        String state;
        int tableId;
        double avgWaitMs;
        int grp;
        String meal;
        String utensil;
    }

    static class SeatOcc {
        int id;
        int grp;
    }

    static class TableView {
        int id;
        int used;
        int cap;
        final List<SeatOcc> occ = new ArrayList<>();
    }

    // ===== UI =====

    private final String modeName;

    private final JLabel lblMode = new JLabel();
    private final JLabel lblIngredients = new JLabel();
    private final JLabel lblMeals = new JLabel();
    private final JLabel lblUtensils1 = new JLabel();
    private final JLabel lblUtensils2 = new JLabel();
    private final JLabel lblWaiters = new JLabel();

    private final DefaultTableModel clientsModel;
    private final JTable clientsTable;

    private final Canvas canvas = new Canvas();

    // ===== Data (updated from SNAPSHOT) =====

    private int ingredients = 0;
    private int ingredientsMax = 0;

    private int filet = 0;
    private int filetMax = 0;

    private int soup = 0;
    private int soupMax = 0;

    private int cutleryClean = 0;
    private int cutleryDirty = 0;
    private int cutleryMax = 0;

    private int spoonClean = 0;
    private int spoonDirty = 0;
    private int spoonMax = 0;

    private int waitersFree = 0;
    private int tablesN = 0;

    private final List<TableView> tables = new ArrayList<>();
    private final Map<Integer, ClientView> clients = new HashMap<>();

    public RestaurantPanel(String modeName) {
        this.modeName = modeName;

        setLayout(new BorderLayout());
        setBackground(Color.WHITE);

        // --- Header (top) ---
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setBackground(Color.WHITE);
        header.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        lblMode.setText("TRYB=" + modeName);
        lblMode.setFont(lblMode.getFont().deriveFont(Font.BOLD, 16f));

        for (JLabel l : new JLabel[]{lblIngredients, lblMeals, lblUtensils1, lblUtensils2, lblWaiters}) {
            l.setFont(l.getFont().deriveFont(13f));
        }

        header.add(lblMode);
        header.add(Box.createVerticalStrut(4));
        header.add(lblIngredients);
        header.add(lblMeals);
        header.add(lblUtensils1);
        header.add(lblUtensils2);
        header.add(lblWaiters);

        add(header, BorderLayout.NORTH);

        // --- Side panel (right) with JTable ---
        String[] cols = new String[]{
                "ID",
                "Stolik",
                "Grupa",
                "Danie",
                "Przybory",
                "Stan",
                "Śr. czek. [ms]"
        };

        clientsModel = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };

        clientsTable = new JTable(clientsModel);
        clientsTable.setFillsViewportHeight(true);
        clientsTable.setRowHeight(22);
        clientsTable.setAutoCreateRowSorter(true);

        JScrollPane scroll = new JScrollPane(clientsTable);
        scroll.setBorder(BorderFactory.createLineBorder(new Color(220, 220, 220)));

        JPanel side = new JPanel(new BorderLayout(0, 8));
        side.setBackground(Color.WHITE);
        side.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JLabel title = new JLabel("Lista klientów (procesów)");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 14f));

        JLabel hint = new JLabel("ID → stolik → danie → przybory → stan (na żywo)");
        hint.setFont(hint.getFont().deriveFont(Font.PLAIN, 11f));
        hint.setForeground(Color.DARK_GRAY);

        JPanel sideTop = new JPanel();
        sideTop.setLayout(new BoxLayout(sideTop, BoxLayout.Y_AXIS));
        sideTop.setBackground(Color.WHITE);
        sideTop.add(title);
        sideTop.add(hint);

        side.add(sideTop, BorderLayout.NORTH);
        side.add(scroll, BorderLayout.CENTER);
        side.setPreferredSize(new Dimension(420, 600));

        add(side, BorderLayout.EAST);

        // --- Canvas (center) ---
        canvas.setBackground(Color.WHITE);
        add(canvas, BorderLayout.CENTER);

        // Odświeżanie rysunku (EDT)
        new javax.swing.Timer(120, e -> canvas.repaint()).start();

        // Rozmiar okna
        setPreferredSize(new Dimension(1500, 800));

        // init labels
        refreshHeaderLabels();
    }

    public void applySnapshot(String line) {
        String[] p = Protocol.split(line);
        if (p.length == 0 || !"SNAPSHOT".equals(p[0])) return;

        // Nowy format (z potrawami i przyborami)
        if (p.length >= 17) {
            try {
                ingredients = Integer.parseInt(p[1]);
                ingredientsMax = Integer.parseInt(p[2]);

                filet = Integer.parseInt(p[3]);
                filetMax = Integer.parseInt(p[4]);

                soup = Integer.parseInt(p[5]);
                soupMax = Integer.parseInt(p[6]);

                cutleryClean = Integer.parseInt(p[7]);
                cutleryDirty = Integer.parseInt(p[8]);
                cutleryMax = Integer.parseInt(p[9]);

                spoonClean = Integer.parseInt(p[10]);
                spoonDirty = Integer.parseInt(p[11]);
                spoonMax = Integer.parseInt(p[12]);

                waitersFree = Integer.parseInt(p[13]);
                tablesN = Integer.parseInt(p[14]);

                parseTables(p[15]);
                parseClients(p[16]);

                refreshHeaderLabels();
                refreshClientsTable();
            } catch (Exception ignored) {
            }
            return;
        }

        // Stary format (fallback)
        if (p.length >= 6) {
            try {
                ingredients = Integer.parseInt(p[1]);
                ingredientsMax = Integer.parseInt(p[2]);
                waitersFree = Integer.parseInt(p[3]);
                tablesN = Integer.parseInt(p[4]);
                parseTables(p[5]);
                if (p.length >= 7) parseClients(p[6]);

                refreshHeaderLabels();
                refreshClientsTable();
            } catch (Exception ignored) {
            }
        }
    }

    private void refreshHeaderLabels() {
        lblIngredients.setText("Składniki: " + ingredients + "/" + ingredientsMax);
        lblMeals.setText("Filet: " + filet + "/" + filetMax + "    Zupa: " + soup + "/" + soupMax);
        lblUtensils1.setText("Sztućce czyste/brudne/max: " + cutleryClean + "/" + cutleryDirty + "/" + cutleryMax);
        lblUtensils2.setText("Łyżki czyste/brudne/max: " + spoonClean + "/" + spoonDirty + "/" + spoonMax);
        lblWaiters.setText("Wolni kelnerzy: " + waitersFree);
    }

    private void parseTables(String raw) {
        tables.clear();
        if (raw == null || raw.isEmpty()) return;

        String[] tt = raw.split(";");
        for (String t : tt) {
            String[] a = t.split(",", -1);
            if (a.length < 3) continue;

            TableView tv = new TableView();
            tv.id = parseIntSafe(a[0], -1);
            tv.used = parseIntSafe(a[1], 0);
            tv.cap = parseIntSafe(a[2], 0);

            // occList: "7:2.11:1" rozdzielane kropką
            if (a.length >= 4 && !a[3].isEmpty()) {
                String[] occ = a[3].split("\\.");
                for (String o : occ) {
                    if (o.isEmpty()) continue;
                    String[] b = o.split(":");
                    SeatOcc so = new SeatOcc();
                    so.id = parseIntSafe(b[0], -1);
                    so.grp = (b.length >= 2) ? parseIntSafe(b[1], 1) : 1;
                    if (so.id >= 0) tv.occ.add(so);
                }
            }

            tables.add(tv);
        }

        tables.sort(Comparator.comparingInt(x -> x.id));
    }

    private void parseClients(String raw) {
        clients.clear();
        if (raw == null || raw.isEmpty()) return;

        String[] cc = raw.split(";");
        for (String c : cc) {
            String[] a = c.split(",", -1);
            if (a.length < 4) continue;

            ClientView cv = new ClientView();
            cv.id = parseIntSafe(a[0], -1);
            cv.state = (a.length >= 2) ? a[1] : "";
            cv.tableId = (a.length >= 3) ? parseIntSafe(a[2], -1) : -1;
            cv.avgWaitMs = (a.length >= 4) ? parseDoubleSafe(a[3], 0.0) : 0.0;
            cv.grp = (a.length >= 5) ? parseIntSafe(a[4], 1) : 1;
            cv.meal = (a.length >= 6) ? a[5] : "";
            cv.utensil = (a.length >= 7) ? a[6] : "";

            if (cv.id >= 0) clients.put(cv.id, cv);
        }
    }

    private void refreshClientsTable() {
        // czytelna kolejność po ID
        List<ClientView> list = new ArrayList<>(clients.values());
        list.sort(Comparator.comparingInt(c -> c.id));

        clientsModel.setRowCount(0);
        for (ClientView c : list) {
            String stolik = (c.tableId >= 0) ? ("T" + c.tableId) : "-";
            String danie = mealPl(c.meal);
            String przybory = utensilPl(c.utensil);
            String stan = statePl(c.state);

            clientsModel.addRow(new Object[]{
                    c.id,
                    stolik,
                    c.grp,
                    danie,
                    przybory,
                    stan,
                    String.format(Locale.US, "%.0f", c.avgWaitMs)
            });
        }
    }

    private static int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private static double parseDoubleSafe(String s, double def) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return def; }
    }

    private static String mealPl(String meal) {
        if (meal == null) return "-";
        return switch (meal.trim().toUpperCase(Locale.ROOT)) {
            case "FILET" -> "Filet";
            case "SOUP" -> "Zupa";
            default -> (meal.isEmpty() ? "-" : meal);
        };
    }

    private static String utensilPl(String u) {
        if (u == null) return "-";
        return switch (u.trim().toUpperCase(Locale.ROOT)) {
            case "CUTLERY" -> "Sztućce";
            case "SPOON", "SPOONS" -> "Łyżka";
            default -> (u.isEmpty() ? "-" : u);
        };
    }

    private static String statePl(String state) {
        if (state == null) return "?";
        return switch (state) {
            case "OUTSIDE" -> "Poza lokalem";
            case "WAIT_TABLE" -> "Czeka na stolik";
            case "SEATED" -> "Siedzi (czeka)";
            case "WAIT_WAITER" -> "Czeka na kelnera/zasoby";
            case "SERVED" -> "Obsłużony (zaraz je)";
            case "EATING" -> "Je";
            case "DONE" -> "Skończył / wychodzi";
            default -> state;
        };
    }

    private static Color colorFor(String state) {
        return switch (state) {
            case "WAIT_TABLE" -> new Color(255, 190, 120);
            case "SEATED" -> new Color(200, 220, 255);
            case "WAIT_WAITER" -> new Color(255, 210, 150);
            case "SERVED" -> new Color(255, 235, 150);
            case "EATING" -> new Color(160, 230, 160);
            case "DONE" -> new Color(210, 210, 210);
            case "OUTSIDE" -> new Color(235, 235, 235);
            default -> new Color(200, 220, 255);
        };
    }

    // ===== Canvas drawing =====

    private class Canvas extends JComponent {
        Canvas() {
            setOpaque(true);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();

            int cx = w / 2;
            int cy = h / 2 + 20;

            // duży "stół" w środku
            g.setColor(new Color(235, 235, 235));
            g.fillOval(cx - 260, cy - 220, 520, 440);

            drawLegend(g);
            drawTables(g, cx, cy);
            drawClients(g, cx, cy);

            g.dispose();
        }

        private void drawLegend(Graphics2D g) {
            int x = 14, y = 14, w = 300, h = 170;
            g.setColor(new Color(245, 245, 245));
            g.fillRoundRect(x, y, w, h, 12, 12);
            g.setColor(new Color(210, 210, 210));
            g.drawRoundRect(x, y, w, h, 12, 12);

            int r = y + 24;
            g.setFont(g.getFont().deriveFont(Font.BOLD, 12f));
            g.setColor(Color.DARK_GRAY);
            g.drawString("Legenda stanów", x + 10, r);
            r += 18;

            g.setFont(g.getFont().deriveFont(Font.PLAIN, 12f));
            r = drawLegendRow(g, x, r, "WAIT_TABLE", "Czeka na stolik");
            r = drawLegendRow(g, x, r, "WAIT_WAITER", "Czeka na kelnera/zasoby");
            r = drawLegendRow(g, x, r, "EATING", "Je");
            r = drawLegendRow(g, x, r, "DONE", "Skończył / wychodzi");
            r = drawLegendRow(g, x, r, "OUTSIDE", "Poza lokalem");

            g.setFont(g.getFont().deriveFont(Font.PLAIN, 11f));
            g.setColor(Color.DARK_GRAY);
            g.drawString("Stolik: T# zajęte/pojemność + miejsca (ID w kółkach)", x + 10, y + h - 12);
        }

        private int drawLegendRow(Graphics2D g, int x, int r, String state, String label) {
            g.setColor(colorFor(state));
            g.fillRect(x + 10, r - 10, 14, 14);
            g.setColor(Color.DARK_GRAY);
            g.drawString(label, x + 32, r + 2);
            return r + 20;
        }

        private void drawTables(Graphics2D g, int cx, int cy) {
            int r = 180;
            for (int i = 0; i < tablesN; i++) {
                double a = -Math.PI / 2 + i * (2 * Math.PI / Math.max(1, tablesN));
                int tx = (int) (cx + r * Math.cos(a));
                int ty = (int) (cy + r * Math.sin(a));

                TableView tv = (i < tables.size()) ? tables.get(i) : null;
                if (tv != null && tv.id != i) {
                    // jeśli z jakiegoś powodu lista nie jest w kolejności, spróbuj znaleźć po id
                    for (TableView t : tables) {
                        if (t.id == i) { tv = t; break; }
                    }
                }

                int used = (tv == null) ? 0 : tv.used;
                int cap = (tv == null) ? 0 : tv.cap;

                g.setColor(Color.WHITE);
                g.fillRoundRect(tx - 70, ty - 26, 140, 52, 14, 14);
                g.setColor(Color.GRAY);
                g.drawRoundRect(tx - 70, ty - 26, 140, 52, 14, 14);

                g.setColor(Color.DARK_GRAY);
                g.setFont(g.getFont().deriveFont(Font.BOLD, 12f));

                String label;
                if (cap <= 0) label = "T" + i + ": ?";
                else if (used <= 0) label = "T" + i + ": wolny (0/" + cap + ")";
                else label = "T" + i + ": zajęte " + used + "/" + cap;

                g.drawString(label, tx - 62, ty - 6);

                // miejsca
                if (cap > 0) {
                    List<Integer> seats = new ArrayList<>();
                    if (tv != null) {
                        for (SeatOcc so : tv.occ) {
                            int gSeats = Math.max(1, so.grp);
                            for (int k = 0; k < gSeats && seats.size() < cap; k++) {
                                seats.add(so.id);
                            }
                        }
                    }
                    while (seats.size() < cap) seats.add(-1);

                    int rSeat = 9;
                    int spacing = 5;
                    int totalW = cap * (2 * rSeat) + Math.max(0, cap - 1) * spacing;
                    int sx0 = tx - totalW / 2;
                    int sy = ty + 14;

                    g.setFont(g.getFont().deriveFont(Font.PLAIN, 9f));
                    for (int s = 0; s < cap; s++) {
                        int cxSeat = sx0 + s * (2 * rSeat + spacing) + rSeat;
                        int idSeat = seats.get(s);

                        if (idSeat < 0) g.setColor(new Color(230, 230, 230));
                        else g.setColor(new Color(200, 220, 255));
                        g.fillOval(cxSeat - rSeat, sy - rSeat, 2 * rSeat, 2 * rSeat);

                        g.setColor(Color.GRAY);
                        g.drawOval(cxSeat - rSeat, sy - rSeat, 2 * rSeat, 2 * rSeat);

                        if (idSeat >= 0) {
                            String txt = Integer.toString(idSeat);
                            int tw = g.getFontMetrics().stringWidth(txt);
                            g.setColor(Color.DARK_GRAY);
                            g.drawString(txt, cxSeat - tw / 2, sy + 3);
                        }
                    }
                }
            }
        }

        private void drawClients(Graphics2D g, int cx, int cy) {
            int rr = 305;

            List<ClientView> list = new ArrayList<>(clients.values());
            list.sort(Comparator.comparingInt(c -> c.id));

            for (int k = 0; k < list.size(); k++) {
                ClientView c = list.get(k);
                double a = -Math.PI / 2 + k * (2 * Math.PI / Math.max(1, list.size()));
                int px = (int) (cx + rr * Math.cos(a));
                int py = (int) (cy + rr * Math.sin(a));

                g.setColor(Color.BLACK);
                g.fillOval(px - 18, py - 18, 36, 36);

                g.setColor(colorFor(c.state));
                g.fillOval(px - 16, py - 16, 32, 32);

                String mealTxt = mealPl(c.meal);
                if ("-".equals(mealTxt)) mealTxt = "";

                g.setColor(Color.DARK_GRAY);
                g.setFont(g.getFont().deriveFont(Font.BOLD, 11f));
                String top = "P" + c.id + (mealTxt.isEmpty() ? "" : (" " + mealTxt));
                g.drawString(top, px - 30, py + 34);

                g.setFont(g.getFont().deriveFont(Font.PLAIN, 10f));
                String st = statePl(c.state);
                g.drawString(st, px - 44, py + 48);

                String t = (c.tableId >= 0) ? ("stolik: T" + c.tableId) : "stolik: -";
                g.drawString(t, px - 44, py + 62);
            }
        }
    }
}
