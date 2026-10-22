package net.friendsserver.tools;

import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.List;

/**
 * Survival way to hide armor stands and item frames: a splash potion of invisibility makes the placed armor stands
 * and item frames (also glow item frames) where it lands invisible, like the potion does to mobs. Whatever is on them
 * stays visible. Breaking one drops a normal armor stand or item frame again.
 */
final class InvisibleSplash implements Listener {

    /** Same reach as the potion's effect on mobs: 4 blocks around where it lands. */
    private static final double RADIUS = 4.0;

    private final FriendsTools plugin;

    InvisibleSplash(FriendsTools plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSplash(PotionSplashEvent event) {
        var cfg = plugin.getConfig();
        if (!cfg.getBoolean("invisible-splash.enabled", true)) {
            return;
        }
        ThrownPotion potion = event.getEntity();
        if (!isInvisibility(potion)) {
            return;
        }
        List<String> worlds = cfg.getStringList("invisible-splash.worlds");
        if (!worlds.isEmpty() && worlds.stream().noneMatch(w -> w.equalsIgnoreCase(potion.getWorld().getName()))) {
            return;
        }
        boolean stands = cfg.getBoolean("invisible-splash.armor-stands", true);
        boolean frames = cfg.getBoolean("invisible-splash.item-frames", true);
        for (Entity e : potion.getNearbyEntities(RADIUS, 2.0, RADIUS)) {
            if (e.getLocation().distanceSquared(potion.getLocation()) >= RADIUS * RADIUS) {
                continue;
            }
            if (stands && e instanceof ArmorStand stand && !stand.isMarker() && stand.isVisible()) {
                stand.setVisible(false);
            } else if (frames && e instanceof ItemFrame frame && frame.isVisible()) {
                frame.setVisible(false);
            }
        }
    }

    private static boolean isInvisibility(ThrownPotion potion) {
        for (PotionEffect effect : potion.getEffects()) {
            if (effect.getType().equals(PotionEffectType.INVISIBILITY)) {
                return true;
            }
        }
        return false;
    }
}
