package net.friendsserver.tools;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Every player death drops the player's head, with the death message and the date on it. */
final class DeathHeads implements Listener {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final FriendsTools plugin;

    DeathHeads(FriendsTools plugin) {
        this.plugin = plugin;
    }

    /**
     * LOWEST, so the death message is read before other plugins hide it (speedrun worlds hide it from global chat).
     * The head is added to the drops, which Paper drops even with keepInventory on.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(PlayerDeathEvent event) {
        if (!plugin.getConfig().getBoolean("death-heads.enabled", true)) {
            return;
        }
        Player player = event.getEntity();
        Component deathMessage = event.deathMessage();

        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        head.editMeta(SkullMeta.class, meta -> {
            meta.setPlayerProfile(player.getPlayerProfile());
            if (plugin.getConfig().getBoolean("death-heads.lore", true)) {
                List<Component> lore = new ArrayList<>();
                if (deathMessage != null) {
                    lore.add(plain(deathMessage));
                }
                lore.add(plain(Component.text(LocalDate.now(ZoneId.systemDefault()).format(DATE))));
                meta.lore(lore);
            }
        });
        event.getDrops().add(head);
    }

    private static Component plain(Component text) {
        return Component.empty().color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false).append(text);
    }
}
