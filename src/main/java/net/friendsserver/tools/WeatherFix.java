package net.friendsserver.tools;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Re-sends the real weather of the player's current world shortly after joining or changing worlds.
 * Fixes survival rain still being shown in the lobby after a player is moved there on join.
 */
final class WeatherFix implements Listener {

    private final FriendsTools plugin;

    WeatherFix(FriendsTools plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        later(event.getPlayer(), 40L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        later(event.getPlayer(), 10L);
    }

    private void later(Player player, long ticks) {
        if (!plugin.getConfig().getBoolean("weather-fix.enabled", true)) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                player.resetPlayerWeather();
            }
        }, ticks);
    }
}
