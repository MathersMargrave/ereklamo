import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.Console;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * E-Reklamo server: login for Tanods + complaint tickets stored in an SQLite database.
 * Needs Java 11 or newer and the SQLite JDBC jar in a folder called "lib".
 *
 * Run the website:      java -cp "lib/*" Main.java
 * Add a Tanod account:  java -cp "lib/*" Main.java addtanod
 * Reset a password:     java -cp "lib/*" Main.java resetpass
 *
 * The database file "ereklamo.db" is created automatically next to this file.
 * Uploaded evidence is saved in the "uploads" folder.
 */
public class Main {

    static final int PORT = 8080;
    static final Path WEB_ROOT = Paths.get(".").toAbsolutePath().normalize();
    static final Path UPLOAD_DIR = WEB_ROOT.resolve("uploads");
    static final String DB_URL = "jdbc:sqlite:" + WEB_ROOT.resolve("ereklamo.db");

    static final long SESSION_MS = 2 * 60 * 60 * 1000L;   // 2 hours
    static final int MAX_FAILS = 5;                        // failed logins allowed ...
    static final long LOCK_MS = 60 * 1000L;                // ... before a 1-minute lockout
    static final int MAX_BODY = 8 * 1024 * 1024;           // biggest request accepted
    static final int MAX_EVIDENCE = 5 * 1024 * 1024;       // biggest evidence file (5 MB)

    static final Set<String> PROTECTED = Set.of("/tanod.html");
    static final Set<String> CATEGORIES = Set.of("Noise", "Garbage", "Illegal Parking");
    static final Set<String> STATUSES = Set.of("Pending", "In Progress", "Resolved");
    static final Set<String> EVIDENCE_EXT = Set.of("jpg", "jpeg", "png", "gif", "webp", "pdf");
    static final Set<String> STATIC_EXT = Set.of("html", "css", "js", "png", "jpg", "jpeg", "gif", "svg", "ico", "webp");

    static final SecureRandom RNG = new SecureRandom();
    static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
    static final Map<String, AtomicInteger> FAILS = new ConcurrentHashMap<>();
    static final Map<String, Long> LOCKED = new ConcurrentHashMap<>();
    static final Map<String, long[]> TICKET_RATE = new HashMap<>();

    static class Tanod {
        final long id;
        final String number, name, position, department, email, photo;
        Tanod(long id, String number, String name, String position, String department, String email, String photo) {
            this.id = id; this.number = number; this.name = name; this.position = position;
            this.department = department; this.email = email; this.photo = photo;
        }
    }

    static class Session {
        final Tanod user;
        final long expires;
        Session(Tanod user, long expires) { this.user = user; this.expires = expires; }
    }

    // =====================================================================
    //  Startup
    // =====================================================================
    public static void main(String[] args) throws Exception {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            System.err.println("ERROR: The SQLite driver was not found.");
            System.err.println("1. Download sqlite-jdbc-3.41.2.2.jar from:");
            System.err.println("   https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.41.2.2/sqlite-jdbc-3.41.2.2.jar");
            System.err.println("2. Put it in a folder named \"lib\" next to Main.java");
            System.err.println("3. Run:  java -cp \"lib/*\" Main.java");
            System.exit(1);
        }

        try {
            initDb();
        } catch (Throwable t) {
            System.err.println("ERROR: Could not open the database: " + t);
            System.err.println("If the message mentions slf4j, also download slf4j-api-1.7.36.jar into the lib folder.");
            System.exit(1);
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("addtanod")) { addTanodCli(); return; }
        if (args.length > 0 && args[0].equalsIgnoreCase("resetpass")) { resetPassCli(); return; }

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/api/login", safe(Main::handleLogin));
        server.createContext("/api/session", safe(Main::handleSession));
        server.createContext("/api/logout", safe(Main::handleLogout));
        server.createContext("/api/tickets", safe(Main::handleTickets));
        server.createContext("/api/evidence/", safe(Main::handleEvidence));
        server.createContext("/", safe(Main::handleStatic));
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("E-Reklamo running at http://localhost:" + PORT + "/index.html");
        System.out.println("Database: " + WEB_ROOT.resolve("ereklamo.db"));
    }

    // =====================================================================
    //  Database
    // =====================================================================
    static Connection db() throws SQLException {
        Connection c = DriverManager.getConnection(DB_URL);
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA busy_timeout = 5000");
        }
        return c;
    }

    static void initDb() throws Exception {
        int count;
        try (Connection c = db(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS tanods ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "tanod_number TEXT NOT NULL UNIQUE,"
                    + "password_hash TEXT NOT NULL,"
                    + "salt TEXT NOT NULL,"
                    + "name TEXT NOT NULL,"
                    + "position TEXT,"
                    + "department TEXT,"
                    + "email TEXT,"
                    + "photo TEXT)");
            st.execute("CREATE TABLE IF NOT EXISTS tickets ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "name TEXT NOT NULL,"
                    + "contact TEXT NOT NULL,"
                    + "category TEXT NOT NULL,"
                    + "location TEXT NOT NULL,"
                    + "description TEXT NOT NULL,"
                    + "evidence_file TEXT,"
                    + "evidence_name TEXT,"
                    + "status TEXT NOT NULL DEFAULT 'Pending',"
                    + "created_at TEXT NOT NULL DEFAULT (datetime('now','localtime')),"
                    + "handled_by TEXT,"
                    + "handled_at TEXT)");
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM tanods")) {
                rs.next();
                count = rs.getInt(1);
            }
        }
        if (count == 0) {
            // First run: create starter accounts. Change these passwords!
            try (Connection c = db()) {
                insertTanod(c, "Jayvee", "tanod123", "Juan Dela Cruz", "IT Support Worker", "Information Technology", "juan@example.com", null);
                insertTanod(c, "T-002", "tanod456", "Maria Santos", "Barangay Tanod", "Peace and Order", "maria@example.com", null);
                insertTanod(c, "W-001", "bersolito123", "Kagawad Bersolito", "Kagawad", "Peace and Order", "bersolito@example.com", "bersolito.jpg");
            }
            System.out.println("First run: created starter Tanod accounts (Jayvee, T-002, W-001).");
        }
    }

    static void insertTanod(Connection c, String number, String password, String name,
                            String position, String department, String email, String photo) throws Exception {
        byte[] salt = new byte[16];
        RNG.nextBytes(salt);
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO tanods (tanod_number, password_hash, salt, name, position, department, email, photo) "
                        + "VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setString(1, number);
            ps.setString(2, hex(hash(password, salt)));
            ps.setString(3, hex(salt));
            ps.setString(4, name);
            ps.setString(5, position);
            ps.setString(6, department);
            ps.setString(7, email);
            ps.setString(8, photo);
            ps.executeUpdate();
        }
    }

    static byte[] hash(String password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 120_000, 256);
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    }

    // =====================================================================
    //  Command-line tools (add account / reset password)
    // =====================================================================
    static String ask(Scanner sc, String prompt) {
        System.out.print(prompt);
        return sc.hasNextLine() ? sc.nextLine().trim() : "";
    }

    static String askPassword(Scanner sc) {
        Console con = System.console();
        if (con != null) {
            char[] pw = con.readPassword("Password (min 6 characters): ");
            return pw == null ? "" : new String(pw);
        }
        return ask(sc, "Password (min 6 characters): ");
    }

    static void addTanodCli() throws Exception {
        Scanner sc = new Scanner(System.in);
        System.out.println("--- Add a new Tanod account ---");
        String number = ask(sc, "Tanod Number (used to log in): ");
        String name = ask(sc, "Full name: ");
        String position = ask(sc, "Position: ");
        String dept = ask(sc, "Department: ");
        String email = ask(sc, "Email: ");
        String password = askPassword(sc);
        if (number.isEmpty() || name.isEmpty() || password.length() < 6) {
            System.out.println("Cancelled: Tanod number and name are required, and the password needs 6+ characters.");
            return;
        }
        try (Connection c = db()) {
            insertTanod(c, number, password, name, position, dept, email, null);
            System.out.println("Done! Account \"" + number + "\" created.");
        } catch (SQLException e) {
            if (String.valueOf(e.getMessage()).toUpperCase().contains("UNIQUE"))
                System.out.println("That Tanod Number already exists.");
            else throw e;
        }
    }

    static void resetPassCli() throws Exception {
        Scanner sc = new Scanner(System.in);
        System.out.println("--- Reset a Tanod password ---");
        String number = ask(sc, "Tanod Number: ");
        String password = askPassword(sc);
        if (number.isEmpty() || password.length() < 6) {
            System.out.println("Cancelled: the password needs 6+ characters.");
            return;
        }
        byte[] salt = new byte[16];
        RNG.nextBytes(salt);
        try (Connection c = db();
             PreparedStatement ps = c.prepareStatement("UPDATE tanods SET password_hash = ?, salt = ? WHERE tanod_number = ?")) {
            ps.setString(1, hex(hash(password, salt)));
            ps.setString(2, hex(salt));
            ps.setString(3, number);
            System.out.println(ps.executeUpdate() > 0 ? "Password updated." : "No account with that Tanod Number.");
        }
    }

    // =====================================================================
    //  Login / session
    // =====================================================================
    static void handleLogin(HttpExchange ex) throws Exception {
        if (!ex.getRequestMethod().equals("POST")) { send(ex, 405, "{\"error\":\"Method not allowed\"}"); return; }

        String ip = ex.getRemoteAddress().getAddress().getHostAddress();
        Long unlock = LOCKED.get(ip);
        if (unlock != null) {
            if (System.currentTimeMillis() < unlock) {
                send(ex, 429, "{\"error\":\"Too many attempts. Please wait a minute and try again.\"}");
                return;
            }
            LOCKED.remove(ip);
            FAILS.remove(ip);
        }

        Map<String, String> body = parseJson(readBody(ex));
        String number = body.getOrDefault("tanodNumber", "").trim();
        String password = body.getOrDefault("password", "");

        Tanod user = null;
        String storedHash = null;
        byte[] salt = new byte[16];
        try (Connection c = db();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, tanod_number, name, position, department, email, photo, password_hash, salt "
                             + "FROM tanods WHERE tanod_number = ?")) {
            ps.setString(1, number);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    user = new Tanod(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), rs.getString(7));
                    storedHash = rs.getString(8);
                    salt = unhex(rs.getString(9));
                }
            }
        }

        // Always hash (even for unknown numbers) so timing doesn't reveal which numbers exist
        byte[] attempt = hash(password, salt);
        boolean ok = user != null && MessageDigest.isEqual(attempt, unhex(storedHash));

        if (!ok) {
            int count = FAILS.computeIfAbsent(ip, k -> new AtomicInteger()).incrementAndGet();
            if (count >= MAX_FAILS) LOCKED.put(ip, System.currentTimeMillis() + LOCK_MS);
            send(ex, 401, "{\"error\":\"Invalid Tanod Number or Password. Please try again.\"}");
            return;
        }

        FAILS.remove(ip);
        byte[] raw = new byte[32];
        RNG.nextBytes(raw);
        String token = hex(raw);
        SESSIONS.put(token, new Session(user, System.currentTimeMillis() + SESSION_MS));
        ex.getResponseHeaders().add("Set-Cookie",
                "tanodSession=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + (SESSION_MS / 1000));
        send(ex, 200, userJson(user));
    }

    static void handleSession(HttpExchange ex) throws Exception {
        Tanod user = currentUser(ex);
        if (user == null) send(ex, 401, "{\"error\":\"Not logged in\"}");
        else send(ex, 200, userJson(user));
    }

    static void handleLogout(HttpExchange ex) throws Exception {
        String token = cookie(ex, "tanodSession");
        if (token != null) SESSIONS.remove(token);
        ex.getResponseHeaders().add("Set-Cookie", "tanodSession=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
        send(ex, 200, "{\"ok\":true}");
    }

    // =====================================================================
    //  Tickets
    //    POST /api/tickets                 (public)  submit a complaint
    //    GET  /api/tickets?status=Pending  (tanod)   list tickets
    //    POST /api/tickets/{id}/status     (tanod)   change a ticket's status
    // =====================================================================
    static void handleTickets(HttpExchange ex) throws Exception {
        String[] parts = ex.getRequestURI().getPath().split("/");   // "", "api", "tickets", ...
        String method = ex.getRequestMethod();

        if (parts.length == 3) {
            if (method.equals("POST")) createTicket(ex);
            else if (method.equals("GET")) listTickets(ex);
            else send(ex, 405, "{\"error\":\"Method not allowed\"}");
        } else if (parts.length == 5 && parts[4].equals("status") && method.equals("POST")) {
            updateStatus(ex, parts[3]);
        } else {
            send(ex, 404, "{\"error\":\"Not found\"}");
        }
    }

    static void createTicket(HttpExchange ex) throws Exception {
        String ip = ex.getRemoteAddress().getAddress().getHostAddress();
        if (!allowTicket(ip)) {
            send(ex, 429, "{\"error\":\"You are submitting too fast. Please wait a minute and try again.\"}");
            return;
        }

        Map<String, String> j = parseJson(readBody(ex));
        String name = required(j, "name", 100, "Name");
        String contact = required(j, "contact", 11, "Contact number");
        String category = required(j, "category", 50, "Category");
        String location = required(j, "location", 200, "Location");
        String description = required(j, "description", 2000, "Description");

        if (!contact.matches("09\\d{9}")) { bad(ex, "Contact number must be 11 digits and start with 09."); return; }
        if (!CATEGORIES.contains(category)) { bad(ex, "Please choose a valid category."); return; }

        // Optional evidence file (sent as base64 text)
        String evFile = null, evName = null;
        String evData = j.getOrDefault("evidenceData", "");
        if (!evData.isEmpty()) {
            evName = j.getOrDefault("evidenceName", "evidence").trim();
            if (evName.length() > 150) evName = evName.substring(evName.length() - 150);
            String ext = ext(evName);
            if (!EVIDENCE_EXT.contains(ext)) { bad(ex, "Evidence must be an image (jpg, png, gif, webp) or a PDF."); return; }
            byte[] bytes = Base64.getDecoder().decode(evData);
            if (bytes.length > MAX_EVIDENCE) { send(ex, 413, "{\"error\":\"Evidence file must be 5 MB or smaller.\"}"); return; }
            byte[] rnd = new byte[16];
            RNG.nextBytes(rnd);
            evFile = hex(rnd) + "." + ext;
            Files.createDirectories(UPLOAD_DIR);
            Files.write(UPLOAD_DIR.resolve(evFile), bytes);
        }

        long id;
        try (Connection c = db()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO tickets (name, contact, category, location, description, evidence_file, evidence_name) "
                            + "VALUES (?,?,?,?,?,?,?)")) {
                ps.setString(1, name);
                ps.setString(2, contact);
                ps.setString(3, category);
                ps.setString(4, location);
                ps.setString(5, description);
                ps.setString(6, evFile);
                ps.setString(7, evName);
                ps.executeUpdate();
            }
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
                rs.next();
                id = rs.getLong(1);
            }
        }
        send(ex, 201, "{\"ok\":true,\"ticketNo\":\"" + ticketNo(id) + "\"}");
    }

    static void listTickets(HttpExchange ex) throws Exception {
        if (currentUser(ex) == null) { send(ex, 401, "{\"error\":\"Not logged in\"}"); return; }

        String status = queryParam(ex, "status");
        boolean filter = status != null && STATUSES.contains(status);
        String sql = "SELECT id, name, contact, category, location, description, evidence_file, evidence_name, "
                + "status, created_at, handled_by, handled_at FROM tickets"
                + (filter ? " WHERE status = ?" : "")
                + " ORDER BY id " + ("Pending".equals(status) ? "ASC" : "DESC");

        StringBuilder sb = new StringBuilder("[");
        try (Connection c = db(); PreparedStatement ps = c.prepareStatement(sql)) {
            if (filter) ps.setString(1, status);
            try (ResultSet rs = ps.executeQuery()) {
                boolean first = true;
                while (rs.next()) {
                    if (!first) sb.append(',');
                    first = false;
                    long id = rs.getLong(1);
                    sb.append("{\"id\":").append(id)
                      .append(",\"ticketNo\":\"").append(ticketNo(id)).append('"')
                      .append(",\"name\":\"").append(esc(rs.getString(2))).append('"')
                      .append(",\"contact\":\"").append(esc(rs.getString(3))).append('"')
                      .append(",\"category\":\"").append(esc(rs.getString(4))).append('"')
                      .append(",\"location\":\"").append(esc(rs.getString(5))).append('"')
                      .append(",\"description\":\"").append(esc(rs.getString(6))).append('"')
                      .append(",\"evidence\":\"").append(esc(rs.getString(7))).append('"')
                      .append(",\"evidenceName\":\"").append(esc(rs.getString(8))).append('"')
                      .append(",\"status\":\"").append(esc(rs.getString(9))).append('"')
                      .append(",\"createdAt\":\"").append(esc(rs.getString(10))).append('"')
                      .append(",\"handledBy\":\"").append(esc(rs.getString(11))).append('"')
                      .append(",\"handledAt\":\"").append(esc(rs.getString(12))).append('"')
                      .append('}');
                }
            }
        }
        sb.append(']');
        send(ex, 200, sb.toString());
    }

    static void updateStatus(HttpExchange ex, String idText) throws Exception {
        Tanod user = currentUser(ex);
        if (user == null) { send(ex, 401, "{\"error\":\"Not logged in\"}"); return; }

        long id = Long.parseLong(idText);
        String status = parseJson(readBody(ex)).getOrDefault("status", "");
        if (!STATUSES.contains(status)) { bad(ex, "Invalid status."); return; }

        int changed;
        try (Connection c = db();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE tickets SET status = ?, handled_by = ?, handled_at = datetime('now','localtime') WHERE id = ?")) {
            ps.setString(1, status);
            ps.setString(2, user.name);
            ps.setLong(3, id);
            changed = ps.executeUpdate();
        }
        if (changed == 0) send(ex, 404, "{\"error\":\"Ticket not found\"}");
        else send(ex, 200, "{\"ok\":true}");
    }

    // GET /api/evidence/<file>  (tanods only)
    static void handleEvidence(HttpExchange ex) throws Exception {
        if (currentUser(ex) == null) { send(ex, 401, "{\"error\":\"Not logged in\"}"); return; }
        String name = ex.getRequestURI().getPath().substring("/api/evidence/".length());
        if (!name.matches("[a-f0-9]{32}\\.[a-z]{3,4}")) { send(ex, 404, "{\"error\":\"Not found\"}"); return; }
        Path f = UPLOAD_DIR.resolve(name);
        if (!Files.isRegularFile(f)) { send(ex, 404, "{\"error\":\"Not found\"}"); return; }

        byte[] data = Files.readAllBytes(f);
        ex.getResponseHeaders().add("Content-Type", mime(name));
        ex.getResponseHeaders().add("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().add("Cache-Control", "private, max-age=3600");
        ex.sendResponseHeaders(200, data.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(data); }
    }

    // =====================================================================
    //  Static files (index.html, css, images ...)
    // =====================================================================
    static void handleStatic(HttpExchange ex) throws Exception {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";

        if (PROTECTED.contains(path) && currentUser(ex) == null) {
            ex.getResponseHeaders().add("Location", "/tanodlogin.html");
            ex.sendResponseHeaders(302, -1);
            return;
        }

        Path file;
        try {
            file = WEB_ROOT.resolve(path.substring(1)).normalize();
        } catch (java.nio.file.InvalidPathException e) {
            send(ex, 404, "{\"error\":\"Not found\"}");
            return;
        }
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        boolean ok = file.startsWith(WEB_ROOT) && !file.equals(WEB_ROOT) && STATIC_EXT.contains(ext(name));
        if (ok) {
            String first = WEB_ROOT.relativize(file).getName(0).toString();
            ok = !first.equals("uploads") && !first.equals("lib");   // never serve evidence or jars directly
        }
        if (!ok || !Files.isRegularFile(file)) { send(ex, 404, "{\"error\":\"Not found\"}"); return; }

        byte[] data = Files.readAllBytes(file);
        ex.getResponseHeaders().add("Content-Type", mime(name));
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, data.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(data); }
    }

    // =====================================================================
    //  Helpers
    // =====================================================================
    interface Handler { void handle(HttpExchange ex) throws Exception; }

    static HttpHandler safe(Handler h) {
        return ex -> {
            try {
                h.handle(ex);
            } catch (IllegalArgumentException e) {
                try { send(ex, 400, "{\"error\":\"Invalid request.\"}"); } catch (Exception ignored) { }
            } catch (Exception e) {
                e.printStackTrace();
                try { send(ex, 500, "{\"error\":\"Server error. Please try again.\"}"); } catch (Exception ignored) { }
            } finally {
                ex.close();
            }
        };
    }

    static Tanod currentUser(HttpExchange ex) {
        String token = cookie(ex, "tanodSession");
        if (token == null) return null;
        Session s = SESSIONS.get(token);
        if (s == null) return null;
        if (System.currentTimeMillis() > s.expires) { SESSIONS.remove(token); return null; }
        return s.user;
    }

    static String cookie(HttpExchange ex, String name) {
        List<String> headers = ex.getRequestHeaders().get("Cookie");
        if (headers == null) return null;
        for (String h : headers)
            for (String part : h.split(";")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2 && kv[0].equals(name)) return kv[1];
            }
        return null;
    }

    static String queryParam(HttpExchange ex, String key) throws IOException {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return null;
        for (String pair : q.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv[0].equals(key) && kv.length == 2) return URLDecoder.decode(kv[1], "UTF-8");
        }
        return null;
    }

    static synchronized boolean allowTicket(String ip) {
        long now = System.currentTimeMillis();
        long[] w = TICKET_RATE.get(ip);
        if (w == null || now - w[0] > 60_000) { TICKET_RATE.put(ip, new long[]{now, 1}); return true; }
        if (w[1] >= 10) return false;    // max 10 tickets per minute per computer
        w[1]++;
        return true;
    }

    static String readBody(HttpExchange ex) throws IOException {
        byte[] raw = ex.getRequestBody().readNBytes(MAX_BODY + 1);
        if (raw.length > MAX_BODY) throw new IllegalArgumentException("too large");
        return new String(raw, StandardCharsets.UTF_8);
    }

    static String required(Map<String, String> j, String key, int max, String label) {
        String v = j.getOrDefault(key, "").trim();
        if (v.isEmpty() || v.length() > max) throw new IllegalArgumentException(label + " is missing or too long");
        return v;
    }

    static void bad(HttpExchange ex, String message) throws IOException {
        send(ex, 400, "{\"error\":\"" + esc(message) + "\"}");
    }

    static String ticketNo(long id) { return String.format("ER-%06d", id); }

    static String ext(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase();
    }

    static String userJson(Tanod u) {
        return "{\"tanodNumber\":\"" + esc(u.number) + "\",\"name\":\"" + esc(u.name)
                + "\",\"position\":\"" + esc(u.position) + "\",\"department\":\"" + esc(u.department)
                + "\",\"email\":\"" + esc(u.email) + "\",\"photo\":\"" + esc(u.photo) + "\"}";
    }

    static void send(HttpExchange ex, int code, String json) throws IOException {
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, data.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(data); }
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    static String mime(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".html")) return "text/html; charset=utf-8";
        if (n.endsWith(".css")) return "text/css; charset=utf-8";
        if (n.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".ico")) return "image/x-icon";
        if (n.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    static byte[] unhex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return out;
    }

    // ---- Tiny JSON reader for flat objects like {"a":"text","b":"text"} ----
    static Map<String, String> parseJson(String s) {
        Map<String, String> m = new HashMap<>();
        int[] p = {0};
        skipWs(s, p);
        if (p[0] >= s.length() || s.charAt(p[0]) != '{') throw new IllegalArgumentException("bad json");
        p[0]++;
        while (true) {
            skipWs(s, p);
            if (p[0] >= s.length()) throw new IllegalArgumentException("bad json");
            char c = s.charAt(p[0]);
            if (c == '}') break;
            if (c == ',') { p[0]++; continue; }
            String key = readString(s, p);
            skipWs(s, p);
            if (p[0] >= s.length() || s.charAt(p[0]) != ':') throw new IllegalArgumentException("bad json");
            p[0]++;
            skipWs(s, p);
            if (p[0] >= s.length()) throw new IllegalArgumentException("bad json");
            String val;
            if (s.charAt(p[0]) == '"') {
                val = readString(s, p);
            } else {
                int start = p[0];
                while (p[0] < s.length() && ",}".indexOf(s.charAt(p[0])) < 0) p[0]++;
                val = s.substring(start, p[0]).trim();
            }
            m.put(key, val);
        }
        return m;
    }

    static void skipWs(String s, int[] p) {
        while (p[0] < s.length() && Character.isWhitespace(s.charAt(p[0]))) p[0]++;
    }

    static String readString(String s, int[] p) {
        if (p[0] >= s.length() || s.charAt(p[0]) != '"') throw new IllegalArgumentException("bad json");
        p[0]++;
        StringBuilder sb = new StringBuilder();
        while (p[0] < s.length()) {
            char c = s.charAt(p[0]++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                if (p[0] >= s.length()) break;
                char e = s.charAt(p[0]++);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        if (p[0] + 4 > s.length()) throw new IllegalArgumentException("bad json");
                        sb.append((char) Integer.parseInt(s.substring(p[0], p[0] + 4), 16));
                        p[0] += 4;
                        break;
                    default: sb.append(e);
                }
            } else {
                sb.append(c);
            }
        }
        throw new IllegalArgumentException("bad json");
    }
}
