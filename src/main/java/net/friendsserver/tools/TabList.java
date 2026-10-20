package net.friendsserver.tools;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tab list names with the BetterTeams team tag and the player's ping.
 *
 * <p>Players with PvP on get the PvP marker after their name.</p>
 *
 * <p>As soon as any plugin sets a custom tab name (like TabListPing does for the ping), the game stops adding
 * the scoreboard-team prefix in the tab list, so team tags disappear. This builds the whole name itself:
 * team prefix + name in the team color + team suffix + [ping].</p>
 */
final class TabList implements Listener {

    private final FriendsTools plugin;
    private final Map<UUID, Component> lastSent = new HashMap<>();
    private BukkitTask task;

    TabList(FriendsTools plugin) {
        this.plugin = plugin;
    }

    void start() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (!enabled()) {
            resetAll();
            return;
        }
        long period = 20L * Math.max(1, plugin.getConfig().getInt("tab.update-seconds", 3));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::updateAll, 20L, period);
    }

    void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        resetAll();
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("tab.enabled", true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        // BetterTeams adds players to their scoreboard team during the join; update right after that
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (enabled() && player.isOnline()) {
                update(player);
            }
        }, 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastSent.remove(event.getPlayer().getUniqueId());
    }

    /** Updates everyone's tab name right away (e.g. after /pvp), if the tab list is running. */
    void refresh() {
        if (task != null) {
            updateAll();
        }
    }

    private void updateAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            update(player);
        }
    }

    private void update(Player player) {
        Component name = build(player);
        if (!name.equals(lastSent.get(player.getUniqueId()))) {
            player.playerListName(name);
            lastSent.put(player.getUniqueId(), name);
        }
    }

    private Component build(Player player) {
        Component prefix = Component.empty();
        Component suffix = Component.empty();
        TextColor color = null;

        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = board.getEntryTeam(player.getName());
        if (team != null) {
            prefix = team.prefix();
            suffix = team.suffix();
            try {
                if (team.hasColor()) {
                    color = team.color();
                }
            } catch (IllegalStateException | IllegalArgumentException ignored) {
                // team without a usable color
            }
        }

        Component result = prefix.append(Component.text(player.getName(), color)).append(suffix);
        Titles titles = plugin.titles();
        Component badge = titles == null || !plugin.getConfig().getBoolean("titles.in-tab", true) ? null
                : titles.badge(player);
        if (badge != null) {
            result = badge.append(result);
        }
        PvpManager pvp = plugin.pvp();
        Component marker = pvp == null ? null : pvp.markerFor(player, true);
        if (marker != null) {
            result = result.append(Component.space()).append(marker);
        }
        if (plugin.getConfig().getBoolean("tab.show-ping", true)) {
            result = result.append(Component.text(" [", NamedTextColor.GRAY))
                    .append(PingCommand.pingComponent(player.getPing()))
                    .append(Component.text("]", NamedTextColor.GRAY));
        }
        return result;
    }

    private void resetAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (lastSent.containsKey(player.getUniqueId())) {
                player.playerListName(null);
            }
        }
        lastSent.clear();
    }
}
