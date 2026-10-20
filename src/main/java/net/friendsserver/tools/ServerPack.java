package net.friendsserver.tools;

import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.resource.ResourcePackStatus;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.io.File;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The server's texture pack (plugins/FriendsTools/pack.zip): served by the upload website at /pack.zip and sent to
 * every player while they log in (in the loading screen, before they see the world), so it never reloads in front of
 * them. Players who said no once get asked again when the pack is required; accepting once makes their client load it
 * without asking from then on.
 *
 * <p>Players with slow PCs can install pack.zip themselves (/pack shows how) and run /pack local: then it isn't sent
 * to them at all, so nothing loads when they join. When pack.zip changes they get the new one from the server once and
 * a link to update their own copy.</p>
 */
final class ServerPack implements Listener, TabExecutor {

    private static final UUID PACK_ID = UUID.nameUUIDFromBytes("friendstools:server-pack".getBytes(StandardCharsets.UTF_8));
    private static final long MAX_BYTES = 100L * 1024 * 1024;

    private final FriendsTools plugin;
    /** Players whose client already got the pack while logging in. */
    private final Set<UUID> sentAtLogin = ConcurrentHashMap.newKeySet();
    /** Players who installed the pack themselves: uuid -> pack hash they have. */
    private final Map<UUID, String> local = new ConcurrentHashMap<>();
    /** Local players whose copy is old: told on join. */
    private final Set<UUID> outdated = ConcurrentHashMap.newKeySet();
    private volatile byte[] bytes;
    private volatile String sha1;

    ServerPack(FriendsTools plugin) {
        this.plugin = plugin;
    }

    /** (Re)reads the pack file; if it changed, players who are online get the new one. */
    void load() {
        loadLocal();
        String oldHash = sha1;
        bytes = null;
        sha1 = null;
        if (!plugin.getConfig().getBoolean("resource-pack.enabled", true)) {
            return;
        }
        String name = plugin.getConfig().getString("resource-pack.file", "pack.zip");
        Path file = plugin.getDataFolder().toPath().resolve(name == null || name.isBlank() ? "pack.zip" : name);
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            if (Files.size(file) > MAX_BYTES) {
                plugin.getLogger().warning("Resource pack " + file.getFileName() + " is bigger than 100 MB, not sent.");
                return;
            }
            byte[] data = Files.readAllBytes(file);
            bytes = data;
            sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
            plugin.getLogger().info("Resource pack " + file.getFileName() + " loaded (" + data.length / 1024
                    + " KB). Players get it when they join.");
        } catch (IOException | NoSuchAlgorithmException e) {
            plugin.getLogger().warning("Could not read the resource pack " + file + ": " + e);
            return;
        }
        if (oldHash != null && !oldHash.equals(sha1)) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    send(p);
                    if (local.containsKey(p.getUniqueId())) {
                        tellOutdated(p);
                    }
                }
            }, 20L);
        }
    }

    byte[] bytes() {
        return bytes;
    }

    /** While logging in: send the pack and wait until the client has loaded it (or said no). */
    @EventHandler
    public void onConfigure(AsyncPlayerConnectionConfigureEvent event) {
        PlayerConfigurationConnection connection = event.getConnection();
        ResourcePackRequest.Builder request = request();
        if (request == null) {
            return;
        }
        UUID id = connection.getProfile().getId();
        if (id != null && local.containsKey(id)) {
            if (sha1.equals(local.get(id))) {
                sentAtLogin.add(id); // has it installed: nothing to load
                return;
            }
            outdated.add(id); // their copy is old: send the new one this time
        }
        CompletableFuture<ResourcePackStatus> done = new CompletableFuture<>();
        request.callback((packId, status, audience) -> {
            if (!status.intermediate()) {
                done.complete(status);
            }
        });
        connection.getAudience().sendResourcePacks(request.build());
        ResourcePackStatus status = null;
        int wait = Math.max(10, plugin.getConfig().getInt("resource-pack.wait-seconds", 90));
        try {
            for (int i = 0; i < wait && status == null && connection.isConnected(); i++) {
                try {
                    status = done.get(1, TimeUnit.SECONDS);
                } catch (TimeoutException ignored) {
                    // still downloading/loading
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (Exception e) {
            return;
        }
        if (status == null) {
            return; // left, or slow download: let them in, the pack keeps loading
        }
        if (id != null) {
            sentAtLogin.add(id);
        }
        if (status == ResourcePackStatus.DECLINED && plugin.getConfig().getBoolean("resource-pack.required", true)) {
            connection.disconnect(plugin.messages().component("pack-required-kick"));
        } else if (status != ResourcePackStatus.SUCCESSFULLY_LOADED) {
            plugin.getLogger().info("Resource pack for " + (connection.getProfile().getName()) + ": " + status);
        }
    }

    /** Fallback: a player who didn't get it while logging in (e.g. the website wasn't up yet) gets it now. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (outdated.remove(player.getUniqueId())) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> tellOutdated(player), 60L);
        }
        if (sentAtLogin.remove(player.getUniqueId())) {
            return;
        }
        String mine = local.get(player.getUniqueId());
        if (mine != null && mine.equals(sha1)) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                send(player);
            }
        }, 40L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sentAtLogin.remove(event.getPlayer().getUniqueId());
    }

    private void send(Player player) {
        ResourcePackRequest.Builder request = request();
        if (request == null) {
            if (sha1 != null) {
                plugin.getLogger().warning("Resource pack not sent to " + player.getName() + ": the upload website isn't"
                        + " running (it serves the pack). Set disc-upload.port.");
            }
            return;
        }
        player.sendResourcePacks(request.build());
    }

    /** The pack request, or null when there is no pack or no website to download it from. */
    private ResourcePackRequest.Builder request() {
        String hash = sha1;
        if (hash == null) {
            return null;
        }
        String base = plugin.discUpload() == null ? null : plugin.discUpload().publicBaseUrl();
        if (base == null) {
            return null;
        }
        String prompt = plugin.getConfig().getString("resource-pack.prompt", "");
        ResourcePackRequest.Builder request = ResourcePackRequest.resourcePackRequest()
                .packs(ResourcePackInfo.resourcePackInfo(PACK_ID, URI.create(base + "/pack.zip?v=" + hash), hash))
                .required(plugin.getConfig().getBoolean("resource-pack.required", true))
                .replace(true);
        if (prompt != null && !prompt.isBlank()) {
            request.prompt(MiniMessage.miniMessage().deserialize(prompt));
        }
        return request;
    }

    // ---- /pack, /pack local, /pack server

    private String link() {
        String base = plugin.discUpload() == null ? null : plugin.discUpload().publicBaseUrl();
        return base == null || sha1 == null ? null : base + "/pack.zip?v=" + sha1;
    }

    private Component linkButton() {
        String url = link();
        return url == null ? plugin.messages().component("pack-no-link")
                : plugin.messages().component("pack-link-button").clickEvent(ClickEvent.openUrl(url));
    }

    private void tellOutdated(Player p) {
        if (p.isOnline()) {
            plugin.messages().send(p, "pack-outdated", Placeholder.component("link", linkButton()));
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            plugin.messages().send(sender, "players-only");
            return true;
        }
        if (sha1 == null) {
            plugin.messages().send(p, "pack-none");
            return true;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(java.util.Locale.ROOT);
        switch (sub) {
            case "local" -> {
                local.put(p.getUniqueId(), sha1);
                saveLocal();
                plugin.messages().send(p, "pack-local-on");
            }
            case "server" -> {
                local.remove(p.getUniqueId());
                saveLocal();
                plugin.messages().send(p, "pack-local-off");
            }
            default -> plugin.messages().send(p, local.containsKey(p.getUniqueId()) ? "pack-info-local" : "pack-info",
                    Placeholder.component("link", linkButton()));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return args.length == 1 ? List.of("local", "server").stream().filter(o -> o.startsWith(args[0].toLowerCase(java.util.Locale.ROOT))).toList()
                : List.of();
    }

    private File localFile() {
        return new File(plugin.getDataFolder(), "pack-local.yml");
    }

    private void loadLocal() {
        local.clear();
        File f = localFile();
        if (!f.exists()) {
            return;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
        for (String key : yml.getKeys(false)) {
            try {
                local.put(UUID.fromString(key), yml.getString(key, ""));
            } catch (IllegalArgumentException ignored) {
                // skip broken lines
            }
        }
    }

    private void saveLocal() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.options().setHeader(List.of("Players who installed pack.zip themselves (/pack local): uuid -> pack version they have"));
        local.forEach((id, hash) -> yml.set(id.toString(), hash));
        try {
            yml.save(localFile());
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save pack-local.yml: " + e);
        }
    }
}
