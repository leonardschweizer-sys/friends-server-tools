package net.friendsserver.tools;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

/**
 * /ping shows your own ping, /ping <player> shows someone else's.
 *
 * <p>EssentialsX also has a /ping (it only answers "Pong!" or echoes what you type) and gets the plain
 * /ping label, so typed /ping commands are redirected to /friendstools:ping before any plugin sees them.</p>
 */
final class PingCommand implements CommandExecutor, TabCompleter, Listener {

    private final FriendsTools plugin;

    PingCommand(FriendsTools plugin) {
        this.plugin = plugin;
    }

    /** Ping in green (under 80 ms), yellow (under 150 ms) or red. Also used by the tab list. */
    static Component pingComponent(int ping) {
        NamedTextColor color = ping < 80 ? NamedTextColor.GREEN
                : ping < 150 ? NamedTextColor.YELLOW : NamedTextColor.RED;
        return Component.text(ping + "ms", color);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        Messages msg = plugin.messages();
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                msg.send(sender, "ping-usage");
                return true;
            }
            msg.send(sender, "ping-self", Placeholder.component("ping", pingComponent(player.getPing())));
            return true;
        }
        Player target = Bukkit.getPlayer(args[0]);
        if (target == null || (sender instanceof Player p && !p.canSee(target))) {
            msg.send(sender, "ping-not-online", "player", args[0]);
            return true;
        }
        msg.send(sender, "ping-other", Placeholder.component("ping", pingComponent(target.getPing())),
                "player", target.getName());
        return true;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        String rewritten = redirect(event.getMessage().substring(1));
        if (rewritten != null) {
            event.setMessage("/" + rewritten);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onConsoleCommand(ServerCommandEvent event) {
        String command = event.getCommand().startsWith("/") ? event.getCommand().substring(1) : event.getCommand();
        String rewritten = redirect(command);
        if (rewritten != null) {
            event.setCommand(rewritten);
        }
    }

    /** "ping" or "ping Steve" -> "friendstools:ping ...", anything else -> null. */
    private static String redirect(String command) {
        String lower = command.toLowerCase(Locale.ROOT);
        if (lower.equals("ping") || lower.startsWith("ping ")) {
            return "friendstools:ping" + command.substring(4);
        }
        return null;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String start = args[0].toLowerCase(Locale.ROOT);
        return Bukkit.getOnlinePlayers().stream()
                .filter(p -> !(sender instanceof Player viewer) || viewer.canSee(p))
                .map(Player::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(start))
                .toList();
    }
}
