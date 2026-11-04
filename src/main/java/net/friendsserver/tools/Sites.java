package net.friendsserver.tools;

import com.sun.net.httpserver.HttpExchange;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Extra websites on the same web server as /upload (disc-upload.port): the files in plugins/FriendsTools/sites/NAME/
 * are served at ADDRESS/NAME/, and with sites.NAME.domain also straight on their own name (e.g.
 * calromea.duckdns.org, which then also goes on the https certificate). /NAME in Minecraft sends the link, for the
 * commands listed in plugin.yml (calromea).
 */
final class Sites implements CommandExecutor {

    private record Site(String name, Path root, String host, String title, SiteBoard board) {
    }

    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"), Map.entry("htm", "text/html; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"), Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"), Map.entry("json", "application/json"),
            Map.entry("txt", "text/plain; charset=utf-8"), Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"), Map.entry("webp", "image/webp"), Map.entry("ico", "image/x-icon"),
            Map.entry("woff", "font/woff"), Map.entry("woff2", "font/woff2"), Map.entry("mp3", "audio/mpeg"),
            Map.entry("mp4", "video/mp4"), Map.entry("glb", "model/gltf-binary"), Map.entry("pdf", "application/pdf"));

    private final FriendsTools plugin;
    private volatile Map<String, Site> sites = Map.of();

    Sites(FriendsTools plugin) {
        this.plugin = plugin;
    }

    /** Names in sites.*.domain that are DuckDNS names (no dot, or ending in .duckdns.org), for the certificate. */
    static List<String> duckDnsNames(FileConfiguration cfg) {
        List<String> out = new ArrayList<>();
        ConfigurationSection section = section(cfg);
        if (section == null) {
            return out;
        }
        for (String name : section.getKeys(false)) {
            String domain = section.getString(name + ".domain", "");
            if (domain == null || domain.isBlank()) {
                continue;
            }
            String d = domain.trim().toLowerCase(Locale.ROOT);
            if (!d.contains(".") || d.endsWith(".duckdns.org")) {
                String sub = HttpsCertificates.subdomain(d);
                if (!sub.isEmpty()) {
                    out.add(sub);
                }
            }
        }
        return out;
    }

    private static ConfigurationSection section(FileConfiguration cfg) {
        // an older config.yml without the section uses the plugin's
        if (cfg.contains("sites", true)) {
            return cfg.getConfigurationSection("sites");
        }
        return cfg.getDefaults() == null ? null : cfg.getDefaults().getConfigurationSection("sites");
    }

    void load() {
        Map<String, Site> map = new LinkedHashMap<>();
        ConfigurationSection section = section(plugin.getConfig());
        Path base = plugin.getDataFolder().toPath().resolve("sites");
        if (section != null) {
            for (String name : section.getKeys(false)) {
                String key = name.toLowerCase(Locale.ROOT);
                if (!key.matches("[a-z0-9_-]{1,32}")) {
                    plugin.getLogger().warning("sites: \"" + name + "\" is not a usable name (a-z, 0-9, - and _).");
                    continue;
                }
                Path root = base.resolve(key).toAbsolutePath().normalize();
                try {
                    Files.createDirectories(root);
                } catch (IOException e) {
                    plugin.getLogger().warning("sites: could not create " + root + ": " + e);
                }
                String domain = section.getString(name + ".domain", "");
                String host = null;
                if (domain != null && !domain.isBlank()) {
                    String d = domain.trim().toLowerCase(Locale.ROOT).replaceFirst("^https?://", "");
                    d = d.replaceAll("[/:].*$", "");
                    host = d.contains(".") ? d : d + ".duckdns.org";
                }
                String title = section.getString(name + ".title", name);
                // comments + build marks for pages that use them (stored next to the site folder, not inside it)
                SiteBoard board = section.getBoolean(name + ".board", false)
                        ? new SiteBoard(plugin, base.resolve(key + "-board.json")) : null;
                map.put(key, new Site(key, root, host, title, board));
                if (!Files.exists(root.resolve("index.html"))) {
                    plugin.getLogger().warning("sites: put the website for " + key + " into " + root
                            + " (index.html first).");
                }
            }
        }
        sites = map;
    }

    // ------------------------------------------------------------------ web (web server threads)

    /** Serves the request if it belongs to a site. */
    boolean serve(HttpExchange ex) throws IOException {
        Map<String, Site> all = sites;
        if (all.isEmpty()) {
            return false;
        }
        String path = ex.getRequestURI().getPath();
        String hostHeader = ex.getRequestHeaders().getFirst("Host");
        String host = hostHeader == null ? "" : hostHeader.toLowerCase(Locale.ROOT).replaceFirst(":\\d+$", "");
        Site site = null;
        String rest = null;
        for (Site s : all.values()) {
            if (s.host() != null && s.host().equals(host)) {
                site = s;
                rest = path;
                break;
            }
        }
        if (site == null) {
            for (Site s : all.values()) {
                String prefix = "/" + s.name();
                if (path.equals(prefix)) {
                    ex.getResponseHeaders().set("Location", prefix + "/");
                    ex.sendResponseHeaders(301, -1);
                    return true;
                }
                if (path.startsWith(prefix + "/")) {
                    site = s;
                    rest = path.substring(prefix.length());
                    break;
                }
            }
        }
        if (site == null) {
            return false;
        }
        String method = ex.getRequestMethod();
        // the site's own pages load fonts and scripts from elsewhere: not the upload website's strict rules
        ex.getResponseHeaders().remove("Content-Security-Policy");
        if (rest.startsWith("/api/")) {
            if (site.board() == null) {
                send(ex, 404, "text/plain; charset=utf-8", "Not found".getBytes(), method);
            } else {
                site.board().handle(ex, rest.substring(5));
            }
            return true;
        }
        if (!method.equals("GET") && !method.equals("HEAD")) {
            send(ex, 405, "text/plain; charset=utf-8", "Method not allowed".getBytes(), method);
            return true;
        }
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        if (rest.isEmpty() || rest.endsWith("/")) {
            rest = rest + "index.html";
        }
        Path file = site.root().resolve(rest.substring(1)).normalize();
        boolean hidden = rest.contains("/.");
        if (!file.startsWith(site.root()) || hidden || !Files.isRegularFile(file)) {
            send(ex, 404, "text/plain; charset=utf-8", "Not found".getBytes(), method);
            return true;
        }
        String name = file.getFileName().toString();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        send(ex, 200, TYPES.getOrDefault(ext, "application/octet-stream"), Files.readAllBytes(file), method);
        return true;
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body, String method) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        if (method.equals("HEAD")) {
            ex.sendResponseHeaders(status, -1);
            return;
        }
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    // ------------------------------------------------------------------ /calromea (command name = site name)

    /** The site's address: its own name if it has one, else ADDRESS/NAME/. Null while the web server is off. */
    String link(String name) {
        Site site = sites.get(name);
        String base = plugin.discUpload() == null ? null : plugin.discUpload().publicBaseUrl();
        if (site == null || base == null) {
            return null;
        }
        if (site.host() == null) {
            return base + "/" + site.name() + "/";
        }
        URI uri = URI.create(base);
        return uri.getScheme() + "://" + site.host() + (uri.getPort() > 0 ? ":" + uri.getPort() : "") + "/";
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        Site site = sites.get(name);
        String url = link(name);
        if (site == null || url == null) {
            plugin.messages().send(sender, "site-offline");
            return true;
        }
        Component button = Component.text("[" + site.title() + "]", NamedTextColor.AQUA)
                .hoverEvent(HoverEvent.showText(Component.text(url, NamedTextColor.GRAY)))
                .clickEvent(ClickEvent.openUrl(url));
        plugin.messages().send(sender, "site-link", Placeholder.component("link", button));
        return true;
    }
}
