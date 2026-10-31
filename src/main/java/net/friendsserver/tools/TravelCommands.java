package net.friendsserver.tools;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /lobby, /smp, /build, /speedrun as real commands instead of commands.yml aliases. On Paper 26.3 the alias
 * "smp: mvtp world" ran itself in a loop until the 65536 command limit (about a second of freeze each time).
 * These run Multiverse's teleport from the console one tick later, exactly like the lobby NPCs do.
 * Each is blocked while you're already in one of its worlds, so it's never a free trip to spawn.
 */
final class TravelCommands implements CommandExecutor {

    private record Target(String destination, List<String> blockedIn) {
    }

    private static final Map<String, Target> DEFAULTS = Map.of(
            "lobby", new Target("lobby", List.of()),
            "smp", new Target("world", List.of("world", "world_nether", "world_the_end")),
            "build", new Target("creative", List.of("creative")),
            "speedrun", new Target("speedrun", List.of("speedrun", "speedrun_nether", "speedrun_the_end")));

    private final FriendsTools plugin;

    TravelCommands(FriendsTools plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            plugin.messages().send(sender, "players-only");
            return true;
        }
        String name = command.getName().toLowerCase(Locale.ROOT);
        Target def = DEFAULTS.get(name);
        if (def == null) {
            return false;
        }
        var cfg = plugin.getConfig();
        String destination = cfg.getString("travel." + name + ".destination", def.destination());
        List<String> blocked = cfg.contains("travel." + name + ".blocked-in")
                ? cfg.getStringList("travel." + name + ".blocked-in") : def.blockedIn();
        if (blocked.stream().anyMatch(w -> w.equalsIgnoreCase(p.getWorld().getName()))) {
            plugin.messages().send(p, "travel-already-there");
            return true;
        }
        // next tick and from the console, outside of this command (like the NPCs)
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mvtp " + p.getName() + " " + destination);
            }
        });
        return true;
    }
}
