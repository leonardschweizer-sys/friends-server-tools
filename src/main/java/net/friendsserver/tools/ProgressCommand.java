package net.friendsserver.tools;

import io.papermc.paper.advancement.AdvancementDisplay;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.advancement.AdvancementRequirement;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * /progress <advancement> [page]: how far you are with an advancement, and for ones with many parts (Potion Master,
 * Adventuring Time, ...) which parts are done (✔) and which are still missing (✘), 10 per page.
 * Secret (hidden) advancements stay secret until you have them. Only in the survival worlds, where advancements count.
 */
final class ProgressCommand implements TabExecutor {

    private static final int PAGE = 10;

    private final FriendsTools plugin;

    ProgressCommand(FriendsTools plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Messages msg = plugin.messages();
        if (!(sender instanceof Player p)) {
            msg.send(sender, "players-only");
            return true;
        }
        if (args.length == 0) {
            msg.send(p, "progress-usage");
            return true;
        }
        int page = 1;
        int end = args.length;
        if (args.length > 1 && args[end - 1].matches("\\d{1,4}")) {
            page = Integer.parseInt(args[end - 1]);
            end--;
        }
        String query = String.join(" ", Arrays.copyOf(args, end));
        Advancement adv = find(query);
        if (adv == null) {
            msg.send(p, "progress-unknown", "name", query);
            return true;
        }
        if (!inSurvival(p)) {
            msg.send(p, "progress-wrong-world");
            return true;
        }
        AdvancementProgress progress = p.getAdvancementProgress(adv);
        AdvancementDisplay display = adv.getDisplay();
        if (display == null || display.isHidden() && !progress.isDone()) {
            msg.send(p, "progress-secret");
            return true;
        }

        // the parts: each requirement is one thing to do (any of its criteria counts)
        List<List<String>> parts = new ArrayList<>();
        for (AdvancementRequirement r : adv.getRequirements().getRequirements()) {
            if (!r.getRequiredCriteria().isEmpty()) {
                parts.add(r.getRequiredCriteria());
            }
        }
        if (parts.isEmpty()) {
            adv.getCriteria().forEach(c -> parts.add(List.of(c)));
        }
        Set<String> awarded = new HashSet<>(progress.getAwardedCriteria());
        int done = 0;
        for (List<String> part : parts) {
            if (part.stream().anyMatch(awarded::contains)) {
                done++;
            }
        }

        int pages = Math.max(1, (parts.size() + PAGE - 1) / PAGE);
        page = Math.max(1, Math.min(page, pages));
        Component title = display.title().hoverEvent(HoverEvent.showText(display.description()));
        Component status = progress.isDone() ? msg.component("progress-completed") : Component.empty();
        msg.send(p, "progress-header", TagResolver.resolver(Placeholder.component("advancement", title),
                        Placeholder.component("status", status)),
                "done", String.valueOf(progress.isDone() ? parts.size() : done), "total", String.valueOf(parts.size()));
        p.sendMessage(display.description().colorIfAbsent(NamedTextColor.GRAY));

        if (parts.size() > 1) {
            for (List<String> part : parts.subList((page - 1) * PAGE, Math.min(parts.size(), page * PAGE))) {
                boolean partDone = progress.isDone() || part.stream().anyMatch(awarded::contains);
                String name = part.size() == 1 ? pretty(part.get(0))
                        : "One of: " + String.join(", ", part.stream().map(ProgressCommand::pretty).toList());
                p.sendMessage(Component.text(partDone ? " ✔ " : " ✘ ", partDone ? NamedTextColor.GREEN : NamedTextColor.RED)
                        .append(Component.text(name, partDone ? NamedTextColor.GRAY : NamedTextColor.WHITE)));
            }
            Component nav = PageNav.of("/progress " + adv.getKey(), page, pages);
            if (nav != null) {
                p.sendMessage(nav);
            }
        }
        return true;
    }

    /** "minecraft:snowy_taiga" -> "Snowy Taiga" */
    private static String pretty(String criterion) {
        String s = criterion.substring(criterion.indexOf(':') + 1).replace('_', ' ').replace('/', ' ').trim();
        StringBuilder out = new StringBuilder();
        for (String word : s.split(" +")) {
            if (!word.isEmpty()) {
                out.append(out.isEmpty() ? "" : " ").append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return out.toString();
    }

    private boolean inSurvival(Player p) {
        List<String> worlds = plugin.getConfig().getStringList("advancements.worlds");
        return worlds.isEmpty() || worlds.stream().anyMatch(w -> w.equalsIgnoreCase(p.getWorld().getName()));
    }

    private static boolean listed(Advancement adv) {
        return adv.getDisplay() != null && !adv.getKey().getKey().startsWith("recipes/");
    }

    private static String shortName(Advancement adv) {
        String path = adv.getKey().getKey();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /** By full id, tab/name, short name (potion_master) or its title (Potion Master). */
    private static Advancement find(String query) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        String underscored = q.replace(' ', '_');
        String plain = q.replaceAll("[^a-z0-9]", "");
        Advancement byShortName = null;
        Advancement byTitle = null;
        Iterator<Advancement> it = Bukkit.advancementIterator();
        while (it.hasNext()) {
            Advancement adv = it.next();
            if (!listed(adv)) {
                continue;
            }
            if (adv.getKey().toString().equals(q) || adv.getKey().getKey().equals(q)) {
                return adv;
            }
            String shortName = shortName(adv);
            if (byShortName == null && !shortName.equals("root") && (shortName.equals(q) || shortName.equals(underscored))) {
                byShortName = adv;
            }
            if (byTitle == null && !plain.isEmpty()) {
                String title = PlainTextComponentSerializer.plainText().serialize(adv.getDisplay().title())
                        .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
                if (title.equals(plain)) {
                    byTitle = adv;
                }
            }
        }
        return byShortName != null ? byShortName : byTitle;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        Set<String> out = new TreeSet<>();
        Iterator<Advancement> it = Bukkit.advancementIterator();
        while (it.hasNext() && out.size() < 80) {
            Advancement adv = it.next();
            if (!listed(adv)) {
                continue;
            }
            String name = shortName(adv);
            if (name.equals("root") || !name.startsWith(prefix)) {
                continue;
            }
            // secret ones only for players who have them
            if (adv.getDisplay().isHidden()
                    && !(sender instanceof Player p && p.getAdvancementProgress(adv).isDone())) {
                continue;
            }
            out.add(name);
        }
        return new ArrayList<>(out);
    }
}
