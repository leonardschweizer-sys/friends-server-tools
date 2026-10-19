package net.friendsserver.tools;

import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * The server's texture pack (plugins/FriendsTools/pack.zip): served by the upload website at /pack.zip and offered
 * to every player when they join, so everyone sees the custom item textures without installing anything.
 */
final class ServerPack implements Listener {

    private static final UUID PACK_ID = UUID.nameUUIDFromBytes("friendstools:server-pack".getBytes(StandardCharsets.UTF_8));
    private static final long MAX_BYTES = 100L * 1024 * 1024;

    private final FriendsTools plugin;
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

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                send(player);
            }
        }, 40L);
    }

    private void send(Player player) {
        String hash = sha1;
        if (hash == null) {
            return;
        }
        String base = plugin.discUpload() == null ? null : plugin.discUpload().publicBaseUrl();
        if (base == null) {
            plugin.getLogger().warning("Resource pack not sent to " + player.getName() + ": the upload website isn't"
                    + " running (it serves the pack). Set disc-upload.port.");
            return;
        }
        String prompt = plugin.getConfig().getString("resource-pack.prompt", "");
        ResourcePackRequest.Builder request = ResourcePackRequest.resourcePackRequest()
                .packs(ResourcePackInfo.resourcePackInfo(PACK_ID, URI.create(base + "/pack.zip?v=" + hash), hash))
                .required(plugin.getConfig().getBoolean("resource-pack.required", false))
                .replace(true);
        if (prompt != null && !prompt.isBlank()) {
            request.prompt(MiniMessage.miniMessage().deserialize(prompt));
        }
        player.sendResourcePacks(request.build());
    }
}
