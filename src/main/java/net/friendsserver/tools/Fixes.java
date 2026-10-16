package net.friendsserver.tools;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Raider;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.raid.RaidSpawnWaveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.OminousBottleMeta;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/** Small fixes for how our plugins and Paper 26.3 play together. */
final class Fixes implements Listener {

    /** Text Multiverse-Inventories puts on advancements it re-grants while swapping a player's world group. */
    private static final String MVINV_MARKER = "mvinv:suppressed";

    private final FriendsTools plugin;
    /** Captains that led a raid wave (they lose their raid link right before they die). */
    private final Set<UUID> raidCaptains = ConcurrentHashMap.newKeySet();

    Fixes(FriendsTools plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ attribute swapping

    /**
     * Attribute swapping (hit with one item's attributes while holding another) is blocked by Paper's
     * unsupported setting update-equipment-on-player-actions (default true). Sets it to false right away
     * (in memory) and in config/paper-global.yml, so it stays after restarts.
     */
    void applyAttributeSwapping() {
        if (!plugin.getConfig().getBoolean("fixes.attribute-swapping", true)) {
            return;
        }
        boolean live = false;
        try {
            Class<?> global = Class.forName("io.papermc.paper.configuration.GlobalConfiguration");
            Object config = global.getMethod("get").invoke(null);
            Object unsupported = config.getClass().getField("unsupportedSettings").get(config);
            Field field = unsupported.getClass().getField("updateEquipmentOnPlayerActions");
            field.setBoolean(unsupported, false);
            live = true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.getLogger().warning("Attribute swapping: could not switch it on right away (" + e
                    + "); it works after the next restart.");
        }
        boolean written = writePaperGlobal();
        if (live || written) {
            plugin.getLogger().info("Attribute swapping is on (Paper unsupported-settings."
                    + "update-equipment-on-player-actions: false).");
        }
    }

    /** Text edit of config/paper-global.yml so the comments and everything else stay as they are. */
    private boolean writePaperGlobal() {
        Path file = Path.of("config", "paper-global.yml");
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
            boolean found = false;
            boolean changed = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String trimmed = line.trim();
                if (trimmed.startsWith("update-equipment-on-player-actions:")) {
                    found = true;
                    if (!trimmed.endsWith("false")) {
                        String indent = line.substring(0, line.indexOf('u'));
                        lines.set(i, indent + "update-equipment-on-player-actions: false");
                        changed = true;
                    }
                }
            }
            if (!found) {
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).trim().equals("unsupported-settings:")) {
                        String indent = lines.get(i).substring(0, lines.get(i).indexOf('u')) + "  ";
                        lines.add(i + 1, indent + "update-equipment-on-player-actions: false");
                        changed = true;
                        break;
                    }
                }
            }
            if (changed) {
                Files.write(file, lines, StandardCharsets.UTF_8);
            }
            return found || changed;
        } catch (Exception e) {
            plugin.getLogger().warning("Attribute swapping: could not edit config/paper-global.yml: " + e);
            return false;
        }
    }

    // ------------------------------------------------------------------ raid captains

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRaidWave(RaidSpawnWaveEvent event) {
        Raider leader = event.getPatrolLeader();
        if (leader != null) {
            raidCaptains.add(leader.getUniqueId());
        }
    }

    /**
     * Captains that come with a raid wave drop an ominous bottle (vanilla only gives one for captains outside a
     * raid), so raid farms can keep themselves going.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onRaiderDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Raider raider)) {
            return;
        }
        boolean waveCaptain = raidCaptains.remove(raider.getUniqueId())
                || (raider.isPatrolLeader() && raider.getRaid() != null);
        if (!waveCaptain || !plugin.getConfig().getBoolean("raids.captain-drops-ominous-bottle", true)) {
            return;
        }
        for (ItemStack drop : event.getDrops()) {
            if (drop != null && drop.getType() == Material.OMINOUS_BOTTLE) {
                return; // already dropping one
            }
        }
        ItemStack bottle = new ItemStack(Material.OMINOUS_BOTTLE);
        bottle.editMeta(OminousBottleMeta.class,
                meta -> meta.setAmplifier(ThreadLocalRandom.current().nextInt(0, 5)));
        event.getDrops().add(bottle);
    }

    /**
     * Logging out while riding something (a 26.3 cushion, boat, horse, minecart...) normally saves the vehicle
     * with the player and spawns it again on login, in whatever world the player is in by then. Multiverse moves
     * everyone to the lobby during the join, so the vehicle was spawned in the lobby at the survival coordinates
     * and the player was put on it there (a cushion then breaks, a horse would end up in the lobby).
     * Getting off before the save leaves the vehicle where it is.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        if (!plugin.getConfig().getBoolean("fixes.dismount-on-quit", true)) {
            return;
        }
        Player player = event.getPlayer();
        Entity vehicle = player.getVehicle();
        if (vehicle != null) {
            player.leaveVehicle();
            plugin.getLogger().fine(player.getName() + " got off " + vehicle.getType() + " before logging out.");
        }
    }

    /**
     * Multiverse-Inventories marks advancements it silently re-grants (when you change world group) with the
     * text "mvinv:suppressed", but Paper then broadcasts that text in chat. Remove the message completely.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (plugin.getConfig().getBoolean("fixes.hide-mvinv-suppressed", true) && isMvInvMarker(event.message())) {
            event.message(null);
        }
    }

    static boolean isMvInvMarker(Component message) {
        return message != null
                && MVINV_MARKER.equals(PlainTextComponentSerializer.plainText().serialize(message));
    }
}
