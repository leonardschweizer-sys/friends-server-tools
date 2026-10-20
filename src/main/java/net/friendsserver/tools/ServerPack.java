package net.friendsserver.tools;

import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.resource.ResourcePackStatus;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
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
import java.util.HexFormat;
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
 */
final class ServerPack implements Listener {

    private static final UUID PACK_ID = UUID.nameUUIDFromBytes("friendstools:server-pack".getBytes(StandardCharsets.UTF_8));
    private static final long MAX_BYTES = 100L * 1024 * 1024;

    private final FriendsTools plugin;
    /** Players whose client already got the pack while logging in. */
    private final Set<UUID> sentAtLogin = ConcurrentHashMap.newKeySet();
    private volatile byte[] bytes;
    private volatile String sha1;

    ServerPack(FriendsTools plugin) {
        this.plugin = plugin;
    }

    /** (Re)reads the pack file; if it changed, players who are online get the new one. */
    void load() {
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
            Bukkit.getScheduler().runTaskLater(plugin, () -> Bukkit.getOnlinePlayers().forEach(this::send), 20L);
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
        if (sentAtLogin.remove(player.getUniqueId())) {
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
}
