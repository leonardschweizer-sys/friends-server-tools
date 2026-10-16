package net.friendsserver.tools;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;

/** Small helper that reads MiniMessage strings from config.yml and sends them. */
final class Messages {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final FriendsTools plugin;

    Messages(FriendsTools plugin) {
        this.plugin = plugin;
    }

    /**
     * Sends a message from the messages section.
     *
     * @param pairs placeholder name/value pairs, e.g. "cost", "32"
     */
    void send(CommandSender to, String key, String... pairs) {
        to.sendMessage(render(key, true, TagResolver.empty(), pairs));
    }

    /** Same, with an extra placeholder that keeps its own formatting (e.g. a colored ping). */
    void send(CommandSender to, String key, TagResolver extra, String... pairs) {
        to.sendMessage(render(key, true, extra, pairs));
    }

    /** A message as a Component without the prefix (kick screens). */
    Component component(String key, String... pairs) {
        return render(key, false, TagResolver.empty(), pairs);
    }

    /** Sends a message with the prefix to every online player and the console. */
    void broadcast(String key, String... pairs) {
        Bukkit.getServer().broadcast(render(key, true, TagResolver.empty(), pairs));
    }

    /** Shows a message above the hotbar, without the prefix. */
    void actionBar(org.bukkit.entity.Player to, String key, String... pairs) {
        to.sendActionBar(render(key, false, TagResolver.empty(), pairs));
    }

    /** Sends a message without the prefix (used for list lines). */
    void sendRaw(CommandSender to, String key, String... pairs) {
        to.sendMessage(render(key, false, TagResolver.empty(), pairs));
    }

    private Component render(String key, boolean withPrefix, TagResolver extra, String... pairs) {
        FileConfiguration cfg = plugin.getConfig();
        // No explicit default here, so keys missing from an older config.yml fall back to the jar's config.yml
        String text = cfg.getString("messages." + key);
        if (text == null) {
            text = key;
        }
        List<TagResolver> resolvers = new ArrayList<>();
        resolvers.add(extra);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            // unparsed = player names / items can't inject formatting
            resolvers.add(Placeholder.unparsed(pairs[i], pairs[i + 1]));
        }
        Component body = MM.deserialize(text, TagResolver.resolver(resolvers));
        if (!withPrefix) {
            return body;
        }
        String prefix = cfg.getString("messages.prefix");
        if (prefix == null) {
            prefix = "";
        }
        return MM.deserialize(prefix).append(body);
    }
}
