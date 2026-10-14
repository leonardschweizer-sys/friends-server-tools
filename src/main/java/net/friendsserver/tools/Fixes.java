package net.friendsserver.tools;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Small fixes for how our plugins and Paper 26.3 play together. */
final class Fixes implements Listener {

    /** Text Multiverse-Inventories puts on advancements it re-grants while swapping a player's world group. */
    private static final String MVINV_MARKER = "mvinv:suppressed";

    private final FriendsTools plugin;

    Fixes(FriendsTools plugin) {
        this.plugin = plugin;
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
