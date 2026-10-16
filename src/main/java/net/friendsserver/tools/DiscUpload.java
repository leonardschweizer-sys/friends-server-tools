package net.friendsserver.tools;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A small upload website for CustomDiscs: /upload gives a player a private link, the page sends an mp3, wav or flac
 * file straight into plugins/CustomDiscs/musicdata, and the player gets a chat button that burns it onto the music
 * disc in their hand (CustomDiscs' "/cd create local"). Files on the server keep working forever, unlike SoundCloud
 * or YouTube discs, which load the song from the internet every time they are played.
 *
 * <p>Runs on its own port (the JDK's built-in web server). Every link is random, only works for 15 minutes and for
 * one upload; everything else gets a 404.</p>
 */
final class DiscUpload implements CommandExecutor {

    private record Ticket(UUID player, String playerName, long expires) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Gson GSON = new Gson();
    private static final String CSP = "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; "
            + "connect-src 'self'; img-src 'self' data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

    private final FriendsTools plugin;
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final HttpsCertificates https;
    private HttpServer server;
    private ExecutorService executor;
    private int port = -1;
    private boolean secure;

    DiscUpload(FriendsTools plugin) {
        this.plugin = plugin;
        this.https = new HttpsCertificates(plugin);
    }

    // ------------------------------------------------------------------ web server lifecycle

    /** Starts (or restarts, after a config reload) the website if a port is set. */
    void start() {
        stop();
        int wanted = plugin.getConfig().getInt("disc-upload.port", 0);
        if (!plugin.getConfig().getBoolean("disc-upload.enabled", true) || wanted <= 0) {
            return;
        }
        // Drop connections that hang (slow or stuck clients) instead of blocking the upload threads forever
        if (System.getProperty("sun.net.httpserver.maxReqTime") == null) {
            System.setProperty("sun.net.httpserver.maxReqTime", "900");
        }
        if (System.getProperty("sun.net.httpserver.maxRspTime") == null) {
            System.setProperty("sun.net.httpserver.maxRspTime", "120");
        }
        port = wanted;
        // https with a DuckDNS name: a saved certificate is used right away, a new one switches over when ready
        https.start(this::upgradeToHttps);
        open(https.ready());
    }

    /** Opens the web server on the configured port, with or without TLS. */
    private void open(boolean withTls) {
        closeServer();
        try {
            HttpServer http;
            if (withTls) {
                HttpsServer tls = HttpsServer.create(new InetSocketAddress(port), 16);
                tls.setHttpsConfigurator(new HttpsConfigurator(https.context()));
                http = tls;
            } else {
                http = HttpServer.create(new InetSocketAddress(port), 16);
            }
            ExecutorService threads = Executors.newFixedThreadPool(3, r -> {
                Thread t = new Thread(r, "FriendsTools disc upload");
                t.setDaemon(true);
                return t;
            });
            http.setExecutor(threads);
            http.createContext("/", this::handle);
            http.start();
            server = http;
            executor = threads;
            secure = withTls;
            String base = baseUrl();
            plugin.getLogger().info("Disc upload website is running on port " + port
                    + (base == null ? ". Set disc-upload.duckdns-domain (or address) so /upload can make links." : ": " + base)
                    + (!withTls && https.configured() ? " (http until the https certificate is ready)" : ""));
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().warning("Disc upload: could not open port " + port + " (" + e
                    + "). Is the port opened for this server and not used by something else?");
        }
    }

    /** Main thread: the first certificate arrived, switch the website to https. */
    private void upgradeToHttps() {
        if (port > 0 && !secure && https.ready()) {
            open(true);
        }
    }

    void stop() {
        closeServer();
        https.stop();
        port = -1;
    }

    private void closeServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        secure = false;
    }

    /** The website's address as players open it, or null if no address is known. */
    private String baseUrl() {
        if (secure) {
            return "https://" + https.domain() + ":" + port;
        }
        String address = plugin.getConfig().getString("disc-upload.address", "");
        if ((address == null || address.isBlank()) && https.configured()) {
            address = https.domain();
        }
        if (address == null || address.isBlank()) {
            address = Bukkit.getIp(); // server-ip from server.properties, often empty or 0.0.0.0
        }
        if (address == null || address.isBlank() || !reachable(address.trim())) {
            return null;
        }
        address = address.trim();
        if (address.startsWith("http://") || address.startsWith("https://")) {
            return address.endsWith("/") ? address.substring(0, address.length() - 1) : address;
        }
        return "http://" + address + ":" + port;
    }

    /** 0.0.0.0, localhost and the like only work on the server itself, never as a link for players. */
    private static boolean reachable(String address) {
        String a = address.toLowerCase(java.util.Locale.ROOT);
        return !(a.equals("0.0.0.0") || a.equals("::") || a.equals("[::]") || a.equals("localhost")
                || a.startsWith("127.") || a.startsWith("http://0.0.0.0") || a.startsWith("http://127.")
                || a.startsWith("http://localhost"));
    }

    private static Path musicData() {
        Plugin discs = Bukkit.getPluginManager().getPlugin("CustomDiscs");
        return discs == null ? null : discs.getDataFolder().toPath().resolve("musicdata");
    }

    private long maxBytes() {
        return Math.max(1, plugin.getConfig().getLong("disc-upload.max-size-mb", 50)) * 1024L * 1024L;
    }

    // ------------------------------------------------------------------ /upload

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String[] args) {
        Messages msg = plugin.messages();
        if (!(sender instanceof Player player)) {
            msg.send(sender, "players-only");
            return true;
        }
        if (musicData() == null) {
            msg.send(player, "upload-no-customdiscs");
            return true;
        }
        String base = server == null ? null : baseUrl();
        if (base == null) {
            msg.send(player, "upload-off");
            return true;
        }

        long now = System.currentTimeMillis();
        tickets.values().removeIf(t -> t.expires() < now || t.player().equals(player.getUniqueId()));
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        long minutes = Math.max(1, plugin.getConfig().getLong("disc-upload.link-minutes", 15));
        tickets.put(token, new Ticket(player.getUniqueId(), player.getName(), now + minutes * 60_000));

        String url = base + "/u/" + token;
        Component button = msg.component("upload-link-button")
                .clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(HoverEvent.showText(Component.text(url)));
        msg.send(player, "upload-link", Placeholder.component("link", button),
                "max", String.valueOf(maxBytes() / 1024 / 1024), "minutes", String.valueOf(minutes));
        return true;
    }

    private Ticket valid(String token) {
        Ticket t = tickets.get(token);
        if (t != null && t.expires() < System.currentTimeMillis()) {
            tickets.remove(token, t);
            return null;
        }
        return t;
    }

    // ------------------------------------------------------------------ web requests (upload threads)

    private void handle(HttpExchange ex) {
        try {
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            ex.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            ex.getResponseHeaders().set("Content-Security-Policy", CSP);
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();

            if (path.equals("/bg.jpg") && method.equals("GET")) {
                background(ex);
                return;
            }
            if (path.equals("/") && method.equals("GET")) {
                html(ex, 200, page("Music discs", "<p>Type <b>/upload</b> in Minecraft to get your upload link.</p>"));
                return;
            }
            if (!path.startsWith("/u/") || path.length() > 64) {
                text(ex, 404, "Not found");
                return;
            }
            String token = path.substring(3);
            if (method.equals("GET")) {
                Ticket t = valid(token);
                if (t == null) {
                    html(ex, 404, page("Link expired", "<p>This link doesn't work anymore. Type <b>/upload</b> in"
                            + " Minecraft to get a new one.</p>"));
                } else {
                    html(ex, 200, uploadPage(t));
                }
            } else if (method.equals("POST")) {
                upload(ex, token);
            } else {
                text(ex, 405, "Method not allowed");
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Disc upload request failed: " + e);
        } finally {
            ex.close();
        }
    }

    private void upload(HttpExchange ex, String token) throws IOException {
        // Take the ticket, so the same link can't upload twice at the same time; it is put back if the upload fails
        Ticket t = valid(token) == null ? null : tickets.remove(token);
        if (t == null) {
            json(ex, 404, false, "This link doesn't work anymore. Type /upload in Minecraft to get a new one.", null);
            return;
        }
        boolean done = false;
        Path tmp = null;
        try {
            Path music = musicData();
            if (music == null) {
                json(ex, 503, false, "CustomDiscs is not installed on the server.", null);
                return;
            }
            long max = maxBytes();
            long length = parseLength(ex.getRequestHeaders().getFirst("Content-Length"));
            if (length <= 0) {
                json(ex, 400, false, "No file was sent.", null);
                return;
            }
            if (length > max) {
                json(ex, 413, false, "The file is too big (max " + max / 1024 / 1024 + " MB).", null);
                return;
            }

            Files.createDirectories(plugin.getDataFolder().toPath());
            tmp = Files.createTempFile(plugin.getDataFolder().toPath(), "upload-", ".part");
            long received = copy(ex.getRequestBody(), tmp, length);
            if (received != length) {
                json(ex, 400, false, "The upload was cut off. Please try again.", null);
                return;
            }
            String extension = detect(tmp);
            if (extension == null) {
                json(ex, 415, false, "That's not an mp3, wav or flac file. Only those work on discs.", null);
                return;
            }

            String discName = cleanName(header(ex, "X-Disc-Name"));
            if (discName.isEmpty()) {
                String original = header(ex, "X-File-Name");
                int dot = original.lastIndexOf('.');
                discName = cleanName(dot > 0 ? original.substring(0, dot) : original);
            }
            if (discName.isEmpty()) {
                discName = "Song";
            }

            Files.createDirectories(music);
            Path target = moveToFreeName(tmp, music, slug(discName), extension);
            tmp = null;
            done = true;
            String fileName = target.getFileName().toString();
            plugin.getLogger().info(t.playerName() + " uploaded the disc song " + fileName + " ("
                    + (length / 1024) + " KB).");
            String finalName = discName;
            Bukkit.getScheduler().runTask(plugin, () -> notifyPlayer(t, fileName, finalName));

            Map<String, String> extra = new LinkedHashMap<>();
            extra.put("file", fileName);
            extra.put("name", discName);
            json(ex, 200, true, "Uploaded!", extra);
        } finally {
            if (tmp != null) {
                Files.deleteIfExists(tmp);
            }
            if (!done && t.expires() > System.currentTimeMillis()) {
                tickets.putIfAbsent(token, t); // let them try again with the same link
            }
        }
    }

    /** Copies at most {@code length} bytes; returns how many arrived. */
    private static long copy(InputStream in, Path file, long length) throws IOException {
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (OutputStream out = Files.newOutputStream(file)) {
            while (total < length) {
                int n = in.read(buffer, 0, (int) Math.min(buffer.length, length - total));
                if (n < 0) {
                    break;
                }
                out.write(buffer, 0, n);
                total += n;
            }
        }
        return total;
    }

    /** The file type from its first bytes (not from the name): mp3, wav or flac, else null. */
    private static String detect(Path file) throws IOException {
        byte[] head = new byte[12];
        int n;
        try (InputStream in = Files.newInputStream(file)) {
            n = in.readNBytes(head, 0, head.length);
        }
        if (n >= 3 && head[0] == 'I' && head[1] == 'D' && head[2] == '3') {
            return "mp3";
        }
        if (n >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xE0) == 0xE0) {
            return "mp3";
        }
        if (n >= 4 && head[0] == 'f' && head[1] == 'L' && head[2] == 'a' && head[3] == 'C') {
            return "flac";
        }
        if (n >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && head[8] == 'W' && head[9] == 'A' && head[10] == 'V' && head[11] == 'E') {
            return "wav";
        }
        return null;
    }

    private static Path moveToFreeName(Path tmp, Path dir, String base, String extension) throws IOException {
        for (int i = 1; i < 1000; i++) {
            Path target = dir.resolve(base + (i == 1 ? "" : "_" + i) + "." + extension);
            if (Files.exists(target)) {
                continue;
            }
            try {
                return Files.move(tmp, target);
            } catch (FileAlreadyExistsException ignored) {
                // taken in the meantime, try the next name
            }
        }
        throw new IOException("no free file name for " + base);
    }

    /** File name part: "Never Gonna Give You Up!" -> "never_gonna_give_you_up". */
    static String slug(String name) {
        String s = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (s.length() > 40) {
            s = s.substring(0, 40).replaceAll("_+$", "");
        }
        return s.isEmpty() ? "song" : s;
    }

    /** Disc name as typed, without characters that would break the /cd command. */
    static String cleanName(String name) {
        if (name == null) {
            return "";
        }
        String s = name.replaceAll("[\\p{Cntrl}\"\\\\]", "").replaceAll("\\s+", " ").trim();
        return s.length() > 48 ? s.substring(0, 48).trim() : s;
    }

    private static String header(HttpExchange ex, String name) {
        String value = ex.getRequestHeaders().getFirst(name);
        if (value == null) {
            return "";
        }
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static long parseLength(String value) {
        try {
            return value == null ? -1 : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void notifyPlayer(Ticket t, String fileName, String discName) {
        Player player = Bukkit.getPlayer(t.player());
        if (player == null) {
            return;
        }
        String command = "/cd create local \"" + fileName + "\" \"" + discName + "\"";
        Component button = plugin.messages().component("upload-create-button")
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(command)));
        plugin.messages().send(player, "upload-done", Placeholder.component("create", button),
                "name", discName, "command", command);
    }

    // ------------------------------------------------------------------ responses

    private static void send(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void text(HttpExchange ex, int status, String body) throws IOException {
        send(ex, status, "text/plain; charset=utf-8", body);
    }

    private static void html(HttpExchange ex, int status, String body) throws IOException {
        send(ex, status, "text/html; charset=utf-8", body);
    }

    private static void json(HttpExchange ex, int status, boolean ok, String message, Map<String, String> extra)
            throws IOException {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", ok);
        data.put("message", message);
        if (extra != null) {
            data.putAll(extra);
        }
        send(ex, status, "application/json; charset=utf-8", GSON.toJson(data));
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /** Page background: plugins/FriendsTools/web/background.jpg if it exists, otherwise the one in the jar. */
    private void background(HttpExchange ex) throws IOException {
        byte[] image = null;
        Path custom = plugin.getDataFolder().toPath().resolve("web").resolve("background.jpg");
        if (Files.isRegularFile(custom) && Files.size(custom) < 10L * 1024 * 1024) {
            image = Files.readAllBytes(custom);
        } else {
            try (InputStream in = DiscUpload.class.getResourceAsStream("/web/background.jpg")) {
                if (in != null) {
                    image = in.readAllBytes();
                }
            }
        }
        if (image == null) {
            text(ex, 404, "Not found");
            return;
        }
        ex.getResponseHeaders().set("Cache-Control", "public, max-age=86400");
        ex.getResponseHeaders().set("Content-Type", "image/jpeg");
        ex.sendResponseHeaders(200, image.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(image);
        }
    }

    // ------------------------------------------------------------------ pages

    private static final String STYLE = """
            *{box-sizing:border-box}
            html{min-height:100%;background:#e89a3c url(/bg.jpg) center/cover no-repeat fixed}
            body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;padding:16px;\
            color:#000;font:16px/1.4 ui-monospace,Menlo,Consolas,"Courier New",monospace}
            main{width:100%;max-width:460px;background:#fff;border:3px solid #000;box-shadow:8px 8px 0 #000}
            h1{margin:0;padding:10px 14px;background:#000;color:#ffe600;font-size:18px;text-transform:uppercase;\
            letter-spacing:.06em}
            .in{padding:16px 14px}
            p{margin:0 0 12px}
            label{display:block;margin:16px 0 4px;font-size:13px;font-weight:700;text-transform:uppercase}
            .drop{display:block;margin:0;padding:22px 12px;border:3px dashed #000;background:#eee;text-align:center;\
            cursor:pointer}
            .drop.over,.drop:hover{background:#ffe600}
            .drop strong{display:block;margin-top:4px;word-break:break-all}
            input[type=file]{display:none}
            input[type=text]{width:100%;padding:9px 8px;border:3px solid #000;border-radius:0;background:#fff;\
            color:#000;font:inherit}
            input[type=text]:focus,button:focus-visible{outline:3px solid #ffe600;outline-offset:0}
            button{width:100%;margin-top:18px;padding:12px;border:3px solid #000;border-radius:0;background:#8bd346;\
            color:#000;font-family:inherit;font-size:16px;font-weight:700;text-transform:uppercase;cursor:pointer;\
            box-shadow:4px 4px 0 #000}
            button:active{transform:translate(4px,4px);box-shadow:none}
            button:disabled{background:#bbb;color:#555;box-shadow:none;transform:none;cursor:default}
            .bar{height:22px;margin-top:16px;border:3px solid #000;background:#fff;display:none}
            .bar div{height:100%;width:0;background:repeating-linear-gradient(90deg,#000 0 12px,#8bd346 12px 16px)}
            #status{margin:14px 0 0;padding:8px;border:3px solid #000;font-weight:700}
            #status:empty{display:none}
            #status.bad{background:#ff6b6b}
            #status.good{background:#b9f27c}
            """;

    private static String page(String title, String body) {
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + escape(title) + "</title><style>" + STYLE + "</style></head><body><main>"
                + "<h1>" + escape(title) + "</h1><div class=\"in\">" + body + "</div></main></body></html>";
    }

    private String uploadPage(Ticket t) {
        long max = maxBytes();
        String body = """
                <p>For <b>%s</b>. An mp3, wav or flac file, up to %d MB.</p>
                <label class="drop" id="drop" for="file">
                  <span id="pick">[ Choose a song or drop it here ]</span>
                  <strong id="chosen"></strong>
                </label>
                <input type="file" id="file" accept=".mp3,.wav,.flac,audio/mpeg,audio/wav,audio/flac">
                <label for="name">Disc name</label>
                <input type="text" id="name" maxlength="48" placeholder="Shown on the disc">
                <button id="go" disabled>Upload</button>
                <div class="bar" id="bar"><div id="fill"></div></div>
                <p id="status" role="status"></p>
                <script>
                const max=%d,file=document.getElementById('file'),drop=document.getElementById('drop'),
                  nameIn=document.getElementById('name'),go=document.getElementById('go'),
                  bar=document.getElementById('bar'),fill=document.getElementById('fill'),
                  status=document.getElementById('status');
                let chosen=null;
                function say(text,cls){status.textContent=text;status.className=cls||'';}
                function choose(f){
                  if(!f)return;
                  if(f.size>max){say('That file is too big (max %d MB).','bad');return;}
                  chosen=f;document.getElementById('chosen').textContent=f.name;
                  document.getElementById('pick').textContent='Song:';
                  if(!nameIn.value)nameIn.value=f.name.replace(/\\.[^.]+$/,'').slice(0,48);
                  go.disabled=false;say('');
                }
                file.addEventListener('change',()=>choose(file.files[0]));
                ['dragover','dragenter'].forEach(e=>drop.addEventListener(e,ev=>{ev.preventDefault();drop.classList.add('over');}));
                ['dragleave','drop'].forEach(e=>drop.addEventListener(e,()=>drop.classList.remove('over')));
                drop.addEventListener('drop',ev=>{ev.preventDefault();choose(ev.dataTransfer.files[0]);});
                go.addEventListener('click',()=>{
                  if(!chosen)return;
                  go.disabled=true;bar.style.display='block';fill.style.width='0';say('Uploading...');
                  const x=new XMLHttpRequest();
                  x.open('POST',location.pathname);
                  x.setRequestHeader('X-Disc-Name',encodeURIComponent(nameIn.value.trim()));
                  x.setRequestHeader('X-File-Name',encodeURIComponent(chosen.name));
                  x.upload.onprogress=e=>{if(e.lengthComputable)fill.style.width=(e.loaded/e.total*100)+'%%';};
                  x.onload=()=>{
                    let r={};try{r=JSON.parse(x.responseText);}catch(e){}
                    if(x.status===200&&r.ok){
                      fill.style.width='100%%';
                      say('DONE. Back in Minecraft: hold a music disc and click the green message in chat.','good');
                      go.textContent='Uploaded';
                    }else{
                      say(r.message||'Upload failed. Try again.','bad');go.disabled=false;
                    }
                  };
                  x.onerror=()=>{say('Upload failed. Check your connection and try again.','bad');go.disabled=false;};
                  x.send(chosen);
                });
                </script>
                """.formatted(escape(t.playerName()), max / 1024 / 1024, max, max / 1024 / 1024);
        return page("Upload a music disc", body);
    }
}
