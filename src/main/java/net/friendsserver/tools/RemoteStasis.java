package net.friendsserver.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Fishing-rod pearl stasis that keeps working on Paper.
 *
 * <p>Vanilla trick: cast a rod onto a wooden or weighted pressure plate next to a stasis chamber, walk away,
 * use the rod from anywhere: the bobber is pulled off the plate and the chamber fires. On Paper the bobber
 * is removed as soon as you go through a portal, change world or log out, so the chamber fires too early.</p>
 *
 * <p>Fix: once a player's bobber rests on such a plate, an invisible, invulnerable armor stand is put on the
 * plate too (owned by that player, saved in stasis.json). It keeps the plate pressed whatever happens to the
 * bobber. The next time the player uses any fishing rod, anywhere, the armor stand is removed (the chunk is
 * loaded for a moment if needed) and the chamber fires. One link per player.</p>
 */
final class RemoteStasis implements Listener {

    /** Where a player's link is. stand = UUID of the armor stand on the plate. */
    record Link(String world, int x, int y, int z, String stand) {
    }

    private static final long TRACK_TIMEOUT_MILLIS = 60_000;
    private static final long CHUNK_HOLD_TICKS = 20L * 15;

    private final FriendsTools plugin;
    private final NamespacedKey ownerKey;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;

    /** Saved links, by player UUID. */
    private final Map<UUID, Link> links = new HashMap<>();
    /** Bobbers that were just cast and might land on a plate. */
    private final Map<UUID, FishHook> casting = new HashMap<>();
    private final Map<UUID, Long> castAt = new HashMap<>();
    private volatile String latestJson = "{}";

    RemoteStasis(FriendsTools plugin) {
        this.plugin = plugin;
        this.ownerKey = new NamespacedKey(plugin, "stasis_owner");
        this.file = plugin.getDataFolder().toPath().resolve("stasis.json");
    }

    void start() {
        load();
        Bukkit.getScheduler().runTaskTimer(plugin, this::checkCastHooks, 2L, 2L);
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("stasis.enabled", true);
    }

    // ------------------------------------------------------------------ casting and pulling

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (!enabled()) {
            return;
        }
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        Link link = links.get(id);

        if (event.getState() == PlayerFishEvent.State.FISHING) {
            if (link != null) {
                // The bobber is gone (portal, other world, relog, too far away): using the rod pulls the link.
                event.setCancelled(true);
                player.playSound(player.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 1f, 1f);
                pull(player, link);
                return;
            }
            casting.put(id, event.getHook());
            castAt.put(id, System.currentTimeMillis());
            return;
        }

        // Reeling the bobber in normally (it still exists)
        switch (event.getState()) {
            case REEL_IN, IN_GROUND, CAUGHT_ENTITY, CAUGHT_FISH, FAILED_ATTEMPT -> {
                casting.remove(id);
                castAt.remove(id);
                if (link != null) {
                    pull(player, link);
                }
            }
            default -> {
            }
        }
    }

    /** Turns a bobber that came to rest on a wooden or weighted pressure plate into a lasting link. */
    private void checkCastHooks() {
        if (casting.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, FishHook>> it = casting.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, FishHook> entry = it.next();
            UUID id = entry.getKey();
            FishHook hook = entry.getValue();
            Long since = castAt.get(id);
            if (hook.isDead() || since == null || now - since > TRACK_TIMEOUT_MILLIS || links.containsKey(id)) {
                it.remove();
                castAt.remove(id);
                continue;
            }
            if (!hook.isValid() || !hook.isOnGround()) {
                continue;
            }
            Block block = hook.getLocation().getBlock();
            if (!isBobberPlate(block.getType()) || !allowedWorld(block.getWorld())) {
                continue;
            }
            Player player = Bukkit.getPlayer(id);
            if (player == null) {
                it.remove();
                castAt.remove(id);
                continue;
            }
            it.remove();
            castAt.remove(id);
            link(player, block);
        }
    }

    private void link(Player player, Block plate) {
        Location at = plate.getLocation().add(0.5, 0, 0.5);
        ArmorStand stand = plate.getWorld().spawn(at, ArmorStand.class, s -> {
            s.setInvisible(true);
            s.setSmall(true);
            s.setGravity(false);
            s.setInvulnerable(true);
            s.setSilent(true);
            s.setCollidable(false);
            s.setCanPickupItems(false);
            s.setPersistent(true);
            s.setBasePlate(false);
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                try {
                    s.addEquipmentLock(slot, ArmorStand.LockType.ADDING_OR_CHANGING);
                } catch (IllegalArgumentException ignored) {
                    // slot not used by armor stands
                }
            }
            s.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, player.getUniqueId().toString());
        });
        links.put(player.getUniqueId(), new Link(plate.getWorld().getName(),
                plate.getX(), plate.getY(), plate.getZ(), stand.getUniqueId().toString()));
        save();
        plugin.getLogger().info(player.getName() + " linked a stasis at " + plate.getWorld().getName() + " "
                + plate.getX() + " " + plate.getY() + " " + plate.getZ());
        plugin.messages().send(player, "stasis-linked");
    }

    /** Removes the player's armor stand so the plate releases and the chamber fires. */
    private void pull(Player player, Link link) {
        links.remove(player.getUniqueId());
        save();
        World world = Bukkit.getWorld(link.world());
        if (world == null) {
            plugin.messages().send(player, "stasis-missing");
            return;
        }
        UUID standId = UUID.fromString(link.stand());
        Entity loaded = Bukkit.getEntity(standId);
        if (loaded != null) {
            loaded.remove();
            plugin.messages().send(player, "stasis-triggered");
            return;
        }
        // Chunk not loaded (or its entities not yet): load it, keep it loaded long enough for the chamber to fire
        world.getChunkAtAsync(link.x() >> 4, link.z() >> 4).thenAccept(chunk -> {
            chunk.addPluginChunkTicket(plugin);
            Bukkit.getScheduler().runTaskLater(plugin, () -> chunk.removePluginChunkTicket(plugin), CHUNK_HOLD_TICKS);
            removeWhenLoaded(player.getUniqueId(), chunk, standId, 0);
        });
    }

    private void removeWhenLoaded(UUID playerId, Chunk chunk, UUID standId, int attempt) {
        Entity stand = Bukkit.getEntity(standId);
        if (stand == null) {
            for (Entity e : chunk.getEntities()) {
                if (e.getUniqueId().equals(standId) || playerId.toString().equals(ownerOf(e))) {
                    stand = e;
                    break;
                }
            }
        }
        Player player = Bukkit.getPlayer(playerId);
        if (stand != null) {
            stand.remove();
            if (player != null) {
                plugin.messages().send(player, "stasis-triggered");
            }
            return;
        }
        if (attempt >= 20) {
            if (player != null) {
                plugin.messages().send(player, "stasis-missing");
            }
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> removeWhenLoaded(playerId, chunk, standId, attempt + 1), 5L);
    }

    // ------------------------------------------------------------------ cleanup and protection

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlateBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!isBobberPlate(block.getType())) {
            return;
        }
        Iterator<Map.Entry<UUID, Link>> it = links.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Link> entry = it.next();
            Link l = entry.getValue();
            if (l.world().equals(block.getWorld().getName()) && l.x() == block.getX()
                    && l.y() == block.getY() && l.z() == block.getZ()) {
                Entity stand = Bukkit.getEntity(UUID.fromString(l.stand()));
                if (stand != null) {
                    stand.remove();
                }
                it.remove();
                save();
                Player owner = Bukkit.getPlayer(entry.getKey());
                if (owner != null) {
                    plugin.messages().send(owner, "stasis-broken");
                }
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (ownerOf(event.getEntity()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onManipulate(PlayerArmorStandManipulateEvent event) {
        if (ownerOf(event.getRightClicked()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractAtEntityEvent event) {
        if (ownerOf(event.getRightClicked()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        casting.remove(event.getPlayer().getUniqueId());
        castAt.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ helpers

    private String ownerOf(Entity entity) {
        if (!(entity instanceof ArmorStand)) {
            return null;
        }
        return entity.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
    }

    /** Plates a fishing bobber can press: wooden ones and the two weighted ones (stone plates ignore bobbers). */
    private static boolean isBobberPlate(Material type) {
        return Tag.WOODEN_PRESSURE_PLATES.isTagged(type)
                || type == Material.LIGHT_WEIGHTED_PRESSURE_PLATE
                || type == Material.HEAVY_WEIGHTED_PRESSURE_PLATE;
    }

    private boolean allowedWorld(World world) {
        List<String> worlds = plugin.getConfig().getStringList("stasis.worlds");
        String name = world.getName().toLowerCase(Locale.ROOT);
        for (String w : worlds) {
            if (w.toLowerCase(Locale.ROOT).equals(name)) {
                return true;
            }
        }
        return false;
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            Type type = new TypeToken<Map<String, Link>>() { }.getType();
            Map<String, Link> data = gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), type);
            if (data != null) {
                data.forEach((id, link) -> links.put(UUID.fromString(id), link));
            }
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().warning("Could not read stasis.json: " + e);
        }
    }

    private void save() {
        Map<String, Link> data = new HashMap<>();
        links.forEach((id, link) -> data.put(id.toString(), link));
        latestJson = gson.toJson(data);
        // Each write uses the newest snapshot, so writes finishing out of order can't save old data
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::write);
    }

    private synchronized void write() {
        String json = latestJson;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("stasis.json.tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save stasis.json: " + e);
        }
    }
}
