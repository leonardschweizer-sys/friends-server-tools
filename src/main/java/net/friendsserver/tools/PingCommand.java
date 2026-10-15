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
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

/** /ping shows your own ping, /ping <player> shows someone else's. */
final class PingCommand implements CommandExecutor, TabCompleter {

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
