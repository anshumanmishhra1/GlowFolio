package glowfolio;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.HttpCookie;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executors;
import java.time.LocalDate;

public class MainServer {
    // In-memory stores
    private static Map<String, User> users = new HashMap<>();
    private static Map<String, String> sessions = new HashMap<>(); // sessionId -> email
    private static Map<String, List<Project>> projects = new HashMap<>(); // email -> list

    public static void main(String[] args) throws Exception {
        // seed demo user
        User demo = new User("Asha Dev", "asha@example.com", "demo123");
        users.put(demo.getEmail(), demo);
        projects.put(demo.getEmail(), new ArrayList<>());
        projects.get(demo.getEmail()).add(new Project("Neon Notes", "A colorful note-taking demo", "Java, CSS, HTML", demo.getName()));
        projects.get(demo.getEmail()).add(new Project("Glide UI", "CSS-first UI with glass + neon", "HTML, CSS", demo.getName()));

        int port = 8080;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", MainServer::handleRoot);
        server.createContext("/static/", MainServer::handleStatic);
        server.createContext("/register", MainServer::handleRegister);
        server.createContext("/login", MainServer::handleLogin);
        server.createContext("/dashboard", MainServer::handleDashboard);
        server.createContext("/projects/new", MainServer::handleNewProjectForm);
        server.createContext("/projects", MainServer::handleCreateProject);
        server.createContext("/logout", MainServer::handleLogout);

        server.setExecutor(Executors.newFixedThreadPool(8));
        System.out.println("Server started at http://localhost:" + port);
        server.start();
    }

    // Helpers
    private static void redirect(HttpExchange ex, String location) throws IOException {
        ex.getResponseHeaders().add("Location", location);
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }

    private static Optional<String> getSessionEmail(HttpExchange ex) {
        List<String> cookies = ex.getRequestHeaders().get("Cookie");
        if (cookies == null) return Optional.empty();
        for (String header : cookies) {
            for (HttpCookie c : HttpCookie.parse(header)) {
                if (c.getName().equals("SESSIONID")) {
                    String sid = c.getValue();
                    return Optional.ofNullable(sessions.get(sid));
                }
            }
        }
        return Optional.empty();
    }

    private static void setSession(HttpExchange ex, String email) {
        String sid = UUID.randomUUID().toString();
        sessions.put(sid, email);
        HttpCookie cookie = new HttpCookie("SESSIONID", sid);
        cookie.setPath("/");
        ex.getResponseHeaders().add("Set-Cookie", cookie.toString());
    }

    private static Map<String, String> parseForm(InputStream is, int len) throws IOException {
        byte[] data = is.readNBytes(len);
        String body = new String(data, "UTF-8");
        Map<String,String> map = new HashMap<>();
        for (String pair : body.split("&")) {
            if (pair.isBlank()) continue;
            String[] kv = pair.split("=",2);
            String k = java.net.URLDecoder.decode(kv[0],"UTF-8");
            String v = kv.length>1?java.net.URLDecoder.decode(kv[1],"UTF-8"):"";
            map.put(k,v);
        }
        return map;
    }

    // Static file handler (css, images)
    private static void handleStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath().replaceFirst("/static/", "");
        Path file = Paths.get("resources/static", path);
        if (!Files.exists(file)) {
            ex.sendResponseHeaders(404, -1);
            return;
        }
        String mime = Files.probeContentType(file);
        byte[] bytes = Files.readAllBytes(file);
        ex.getResponseHeaders().add("Content-Type", mime==null?"application/octet-stream":mime);
        ex.sendResponseHeaders(200, bytes.length);
        OutputStream os = ex.getResponseBody();
        os.write(bytes);
        os.close();
    }

    // Root -> index.html
    private static void handleRoot(HttpExchange ex) throws IOException {
        if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
            String html = readTemplate("index.html");
            sendHtml(ex, html);
        } else {
            ex.sendResponseHeaders(405, -1);
        }
    }

    private static void handleRegister(HttpExchange ex) throws IOException {
        if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendHtml(ex, readTemplate("register.html"));
            return;
        }
        if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
            int len = Integer.parseInt(Optional.ofNullable(ex.getRequestHeaders().getFirst("Content-length")).orElse("0"));
            Map<String,String> form = parseForm(ex.getRequestBody(), len);
            String name = form.getOrDefault("name","").trim();
            String email = form.getOrDefault("email","").trim().toLowerCase();
            String password = form.getOrDefault("password","").trim();
            String error = null;
            if (name.isEmpty()||email.isEmpty()||password.isEmpty()) error = "All fields required.";
            if (users.containsKey(email)) error = "Email already registered.";
            if (error!=null) {
                String page = readTemplate("register.html").replace("<!--ERROR-->", "<div class=\"error\">"+escape(error)+"</div>");
                sendHtml(ex, page);
                return;
            }
            User u = new User(name,email,password);
            users.put(email,u);
            projects.put(email, new ArrayList<>());
            setSession(ex, email);
            redirect(ex, "/dashboard");
            return;
        }
        ex.sendResponseHeaders(405, -1);
    }

    private static void handleLogin(HttpExchange ex) throws IOException {
        if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendHtml(ex, readTemplate("login.html"));
            return;
        }
        if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
            int len = Integer.parseInt(Optional.ofNullable(ex.getRequestHeaders().getFirst("Content-length")).orElse("0"));
            Map<String,String> form = parseForm(ex.getRequestBody(), len);
            String email = form.getOrDefault("email","").trim().toLowerCase();
            String password = form.getOrDefault("password","").trim();
            if (users.containsKey(email) && users.get(email).getPassword().equals(password)) {
                setSession(ex, email);
                redirect(ex, "/dashboard");
            } else {
                String page = readTemplate("login.html").replace("<!--ERROR-->", "<div class=\"error\">Invalid credentials</div>");
                sendHtml(ex, page);
            }
            return;
        }
        ex.sendResponseHeaders(405, -1);
    }

    private static void handleDashboard(HttpExchange ex) throws IOException {
        Optional<String> se = getSessionEmail(ex);
        if (se.isEmpty()) {
            redirect(ex, "/login");
            return;
        }
        String email = se.get();
        User user = users.get(email);
        List<Project> list = projects.getOrDefault(email, new ArrayList<>());
        String tpl = readTemplate("dashboard.html");
        String projectHtml = "";
        for (Project p : list) {
            projectHtml += "<div class=\"card project-card\">"
                         + "<h3>"+escape(p.getTitle())+"</h3>"
                         + "<p class=\"meta\">"+escape(p.getTechStack())+"</p>"
                         + "<p>"+escape(p.getDescription())+"</p>"
                         + "<div class=\"project-footer\"><span>"+escape(p.getCreatedAt().toString())+"</span></div>"
                         + "</div>";
        }
        if (projectHtml.isEmpty()) {
            projectHtml = "<div class=\"empty\">No projects yet. Click <a href=\"/projects/new\">New Project</a> to add one.</div>";
        }
        tpl = tpl.replace("{{userName}}", escape(user.getName()));
        tpl = tpl.replace("{{projects}}", projectHtml);
        sendHtml(ex, tpl);
    }

    private static void handleNewProjectForm(HttpExchange ex) throws IOException {
        Optional<String> se = getSessionEmail(ex);
        if (se.isEmpty()) { redirect(ex, "/login"); return; }
        sendHtml(ex, readTemplate("project_form.html"));
    }

    private static void handleCreateProject(HttpExchange ex) throws IOException {
        Optional<String> se = getSessionEmail(ex);
        if (se.isEmpty()) { redirect(ex, "/login"); return; }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { ex.sendResponseHeaders(405, -1); return; }
        int len = Integer.parseInt(Optional.ofNullable(ex.getRequestHeaders().getFirst("Content-length")).orElse("0"));
        Map<String,String> form = parseForm(ex.getRequestBody(), len);
        String title = form.getOrDefault("title","").trim();
        String tech = form.getOrDefault("techStack","").trim();
        String desc = form.getOrDefault("description","").trim();
        String email = se.get();
        User u = users.get(email);
        Project p = new Project(title, desc, tech, u.getName());
        projects.get(email).add(0, p);
        redirect(ex, "/dashboard");
    }

    private static void handleLogout(HttpExchange ex) throws IOException {
        List<String> cookies = ex.getRequestHeaders().get("Cookie");
        if (cookies!=null) {
            for (String header: cookies) {
                for (HttpCookie c: HttpCookie.parse(header)) {
                    if (c.getName().equals("SESSIONID")) {
                        sessions.remove(c.getValue());
                    }
                }
            }
        }
        HttpCookie cookie = new HttpCookie("SESSIONID", "");
        cookie.setMaxAge(0);
        cookie.setPath("/");
        ex.getResponseHeaders().add("Set-Cookie", cookie.toString());
        redirect(ex, "/");
    }

    // Utilities
    private static void sendHtml(HttpExchange ex, String html) throws IOException {
        byte[] bytes = html.getBytes("UTF-8");
        ex.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
        ex.sendResponseHeaders(200, bytes.length);
        OutputStream os = ex.getResponseBody();
        os.write(bytes);
        os.close();
    }

    private static String readTemplate(String name) throws IOException {
        Path p = Paths.get("resources","templates", name);
        return Files.readString(p);
    }

    private static String escape(String s) {
        return s==null?"":s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");
    }

    // Models
    static class User {
        private String name, email, password;
        public User(String name,String email,String password){this.name=name;this.email=email;this.password=password;}
        public String getName(){return name;} public String getEmail(){return email;} public String getPassword(){return password;}
    }
    static class Project {
        private String title, description, techStack, owner;
        private LocalDate createdAt = LocalDate.now();
        public Project(String t,String d,String tech,String owner){this.title=t;this.description=d;this.techStack=tech;this.owner=owner;}
        public String getTitle(){return title;} public String getDescription(){return description;} public String getTechStack(){return techStack;}
        public LocalDate getCreatedAt(){return createdAt;}
    }
}