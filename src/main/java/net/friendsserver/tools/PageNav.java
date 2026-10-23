package net.friendsserver.tools;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

/** The "« previous   page 2/5   next »" line under paged lists (/chestlog, /progress, /titles). */
final class PageNav {

    private PageNav() {
    }

    /** @param command the command without the page number, e.g. "/chestlog"; null when there is only one page */
    static Component of(String command, int page, int pages) {
        if (pages <= 1) {
            return null;
        }
        return button("« previous", command, page - 1, page > 1)
                .append(Component.text("   page " + page + "/" + pages + "   ", NamedTextColor.GRAY))
                .append(button("next »", command, page + 1, page < pages));
    }

    private static Component button(String text, String command, int target, boolean active) {
        if (!active) {
            return Component.text(text, NamedTextColor.DARK_GRAY);
        }
        return Component.text(text, NamedTextColor.YELLOW)
                .hoverEvent(HoverEvent.showText(Component.text("Page " + target, NamedTextColor.GRAY)))
                .clickEvent(ClickEvent.runCommand(command + " " + target));
    }
}
