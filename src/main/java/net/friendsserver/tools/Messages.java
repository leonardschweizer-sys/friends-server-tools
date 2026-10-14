package net.friendsserver.tools;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
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
        to.sendMessage(render(key, true, pairs));
    }

    /** Sends a message without the prefix (used for list lines). */
    void sendRaw(CommandSender to, String key, String... pairs) {
        to.sendMessage(render(key, false, pairs));
    }

    private Component render(String key, boolean withPrefix, String... pairs) {
        FileConfiguration cfg = plugin.getConfig();
        String text = cfg.getString("messages." + key, key);
        List<TagResolver> resolvers = new ArrayList<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            // unparsed = player names / items can't inject formatting
            resolvers.add(Placeholder.unparsed(pairs[i], pairs[i + 1]));
        }
        Component body = MM.deserialize(text, TagResolver.resolver(resolvers));
        if (!withPrefix) {
            return body;
        }
        String prefix = cfg.getString("messages.prefix", "");
        return MM.deserialize(prefix).append(body);
    }
}
