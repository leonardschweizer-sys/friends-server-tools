package net.friendsserver.tools;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * [item] and [inventory] in chat:
 * <ul>
 *   <li>[item] (or [i]) becomes the item in your hand, with its full tooltip on hover.</li>
 *   <li>[inventory] (or [inv]) becomes a button: clicking it opens a read-only copy of your inventory as it was
 *       when you sent the message (armor, offhand, inventory, hotbar). Copies are kept for a while
 *       (chat-share.inventory-minutes).</li>
 * </ul>
 * The chat message itself is changed, so Discord shows the item name too.
 */
final class ChatShare implements Listener, CommandExecutor {

    private static final Pattern ITEM = Pattern.compile("\\[(?:item|i)]", Pattern.CASE_INSENSITIVE);
    private static final Pattern INVENTORY = Pattern.compile("\\[(?:inventory|inv)]", Pattern.CASE_INSENSITIVE);

    private record Snapshot(String owner, ItemStack[] armor, ItemStack offHand, ItemStack[] storage, long expires) {
    }

    /** Marks the read-only inventory windows. */
    private static final class ShareView implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final FriendsTools plugin;
    private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();

    ChatShare(FriendsTools plugin) {
        this.plugin = plugin;
    }

    // ---- chat

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!plugin.getConfig().getBoolean("chat-share.enabled", true)) {
            return;
        }
        String plain = PlainTextComponentSerializer.plainText().serialize(event.message());
        boolean item = ITEM.matcher(plain).find();
        boolean inventory = INVENTORY.matcher(plain).find();
        if (!item && !inventory) {
            return;
        }
        Player player = event.getPlayer();
        // the inventory belongs to the main thread: read it there
        Callable<Object[]> read = () -> new Object[]{player.getInventory().getItemInMainHand().clone(),
                inventory ? snapshot(player) : null};
        Object[] data;
        try {
            data = Bukkit.isPrimaryThread() ? read.call()
                    : Bukkit.getScheduler().callSyncMethod(plugin, read).get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            return; // server busy or shutting down: leave the message as it is
        }
        Component message = event.message();
        if (item) {
            Component shown = itemComponent((ItemStack) data[0]);
            message = message.replaceText(TextReplacementConfig.builder().match(ITEM).replacement(shown).build());
        }
        if (inventory) {
            String id = store((Snapshot) data[1]);
            Component button = Component.text("[" + player.getName() + "'s inventory]", NamedTextColor.AQUA)
                    .hoverEvent(HoverEvent.showText(Component.text("Click to look inside", NamedTextColor.GRAY)))
                    .clickEvent(ClickEvent.runCommand("/showinv " + id));
            message = message.replaceText(TextReplacementConfig.builder().match(INVENTORY).replacement(button).build());
        }
        event.message(message);
    }

    private static Component itemComponent(ItemStack hand) {
        if (hand == null || hand.getType().isAir() || hand.getAmount() <= 0) {
            return Component.text("[empty hand]", NamedTextColor.GRAY);
        }
        // "[Name]" in its rarity color with the item's own tooltip on hover
        Component name = hand.displayName();
        return hand.getAmount() > 1 ? name.append(Component.text(" x" + hand.getAmount(), NamedTextColor.GRAY)) : name;
    }

    private static Snapshot snapshot(Player player) {
        PlayerInventory inv = player.getInventory();
        return new Snapshot(player.getName(), copy(inv.getArmorContents()), copyOne(inv.getItemInOffHand()),
                copy(inv.getStorageContents()), 0L);
    }

    private static ItemStack[] copy(ItemStack[] items) {
        ItemStack[] out = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) {
            out[i] = copyOne(items[i]);
        }
        return out;
    }

    private static ItemStack copyOne(ItemStack item) {
        return item == null || item.getType().isAir() ? null : item.clone();
    }

    private String store(Snapshot s) {
        long now = System.currentTimeMillis();
        snapshots.values().removeIf(x -> x.expires() < now);
        long minutes = Math.max(1, plugin.getConfig().getLong("chat-share.inventory-minutes", 15));
        String id = UUID.randomUUID().toString().substring(0, 8);
        snapshots.put(id, new Snapshot(s.owner(), s.armor(), s.offHand(), s.storage(), now + minutes * 60_000L));
        return id;
    }

    // ---- /showinv <id> (from the chat button)

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player viewer)) {
            plugin.messages().send(sender, "players-only");
            return true;
        }
        Snapshot s = args.length == 1 ? snapshots.get(args[0]) : null;
        if (s == null || s.expires() < System.currentTimeMillis()) {
            plugin.messages().send(viewer, "chat-share-expired");
            return true;
        }
        ShareView holder = new ShareView();
        Inventory view = Bukkit.createInventory(holder, 54, Component.text(s.owner() + "'s inventory"));
        holder.inventory = view;
        // top row: helmet, chestplate, leggings, boots ... offhand (armor contents are boots first)
        ItemStack[] armor = s.armor();
        for (int i = 0; i < 4 && i < armor.length; i++) {
            view.setItem(i, armor[armor.length - 1 - i]);
        }
        view.setItem(8, s.offHand());
        // inventory rows 3-5, hotbar at the bottom, like the player's own inventory screen
        ItemStack[] storage = s.storage();
        for (int i = 9; i < 36 && i < storage.length; i++) {
            view.setItem(18 + (i - 9), storage[i]);
        }
        for (int i = 0; i < 9 && i < storage.length; i++) {
            view.setItem(45 + i, storage[i]);
        }
        viewer.openInventory(view);
        return true;
    }

    // ---- read-only

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof ShareView) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof ShareView) {
            event.setCancelled(true);
        }
    }
}
