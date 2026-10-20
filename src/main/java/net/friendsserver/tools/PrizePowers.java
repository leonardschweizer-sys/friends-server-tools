package net.friendsserver.tools;

import io.papermc.paper.datacomponent.DataComponentTypes;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.joml.Matrix4f;

import java.util.List;

/**
 * What some prize items do besides looking special:
 * <ul>
 *   <li>ability "lightning" (Mjölnir): every hit calls down lightning, also without a thunderstorm. Outside storms
 *       it is only the flash and the bang (no damage, no fire); in storms Channeling does the real thing.</li>
 *   <li>ability "reusable_totem" (Nokia): saves you like a totem but comes back, then needs to recharge.</li>
 *   <li>placed-model (Nether Reactor Core): keeps its look as a block (a display over the beacon), and drops
 *       itself again when broken. Explosions can't destroy it.</li>
 * </ul>
 */
final class PrizePowers implements Listener {

    private final FriendsTools plugin;
    private final CustomItems items;
    private final NamespacedKey placedKey;
    private final NamespacedKey placedByKey;
    private final NamespacedKey placedAtKey;
    private final NamespacedKey totemReadyKey;

    PrizePowers(FriendsTools plugin, CustomItems items) {
        this.plugin = plugin;
        this.items = items;
        this.placedKey = new NamespacedKey(plugin, "placed_item");
        this.placedByKey = new NamespacedKey(plugin, "placed_earned_by");
        this.placedAtKey = new NamespacedKey(plugin, "placed_earned_at");
        this.totemReadyKey = new NamespacedKey(plugin, "totem_ready_at");
    }

    private CustomItems.Definition def(ItemStack item) {
        return items.definition(items.idOf(item));
    }

    // ---- Mjölnir

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTridentHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Trident trident)) {
            return;
        }
        CustomItems.Definition def = def(trident.getItemStack());
        if (def == null || !"lightning".equals(def.ability())) {
            return;
        }
        World world = trident.getWorld();
        Entity hit = event.getHitEntity();
        if (hit != null && world.isThundering()) {
            return; // Channeling strikes for real
        }
        Location at = hit != null ? hit.getLocation()
                : event.getHitBlock() != null ? event.getHitBlock().getLocation().add(0.5, 1, 0.5) : trident.getLocation();
        world.strikeLightningEffect(at);
    }

    // ---- Nokia (reusable totem)

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onResurrectCheck(EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player player) || event.getHand() == null) {
            return;
        }
        ItemStack used = player.getInventory().getItem(event.getHand());
        CustomItems.Definition d = def(used);
        if (d == null || !"reusable_totem".equals(d.ability()) || ready(player)) {
            return;
        }
        // Still recharging: a normal totem in the other hand still saves you, otherwise nothing does
        if (!isNormalTotem(player.getInventory().getItem(otherHand(event.getHand())))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResurrect(EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player player) || event.getHand() == null) {
            return;
        }
        EquipmentSlot hand = event.getHand();
        ItemStack used = player.getInventory().getItem(hand);
        CustomItems.Definition d = def(used);
        if (d == null || !"reusable_totem".equals(d.ability())) {
            return;
        }
        ItemStack nokia = used.clone();
        nokia.setAmount(1);
        boolean charged = ready(player);
        int minutes = Math.max(1, plugin.getConfig().getInt("prize-powers.totem-recharge-minutes", 10));
        if (charged) {
            player.getPersistentDataContainer().set(totemReadyKey, PersistentDataType.LONG,
                    System.currentTimeMillis() + minutes * 60_000L);
        }
        // the game uses up the totem right after this event: give it back next tick
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (!charged) {
                // the normal totem in the other hand saved them, not the Nokia
                ItemStack other = player.getInventory().getItem(otherHand(hand));
                if (isNormalTotem(other)) {
                    other.subtract();
                }
            }
            ItemStack now = player.getInventory().getItem(hand);
            if (now.getType().isAir()) {
                player.getInventory().setItem(hand, nokia);
            } else {
                player.getInventory().addItem(nokia).values()
                        .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
            }
            showRecharge(player, nokia);
            if (charged) {
                plugin.messages().send(player, "nokia-saved", "minutes", String.valueOf(minutes));
            }
        });
    }

    private boolean ready(Player player) {
        long readyAt = player.getPersistentDataContainer().getOrDefault(totemReadyKey, PersistentDataType.LONG, 0L);
        return System.currentTimeMillis() >= readyAt;
    }

    private void showRecharge(Player player, ItemStack nokia) {
        long readyAt = player.getPersistentDataContainer().getOrDefault(totemReadyKey, PersistentDataType.LONG, 0L);
        long left = readyAt - System.currentTimeMillis();
        if (left > 0) {
            player.setCooldown(nokia, (int) Math.min(Integer.MAX_VALUE, left / 50));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (ready(player)) {
            return;
        }
        // cooldown overlays aren't saved, show the remaining recharge again
        for (ItemStack item : player.getInventory().getContents()) {
            CustomItems.Definition d = def(item);
            if (d != null && "reusable_totem".equals(d.ability())) {
                showRecharge(player, item);
                return;
            }
        }
    }

    /** A totem that works the normal way (also custom-looking ones like the heart totem). */
    private boolean isNormalTotem(ItemStack item) {
        if (item == null || !item.hasData(DataComponentTypes.DEATH_PROTECTION)) {
            return false;
        }
        CustomItems.Definition d = def(item);
        return d == null || !"reusable_totem".equals(d.ability());
    }

    private static EquipmentSlot otherHand(EquipmentSlot hand) {
        return hand == EquipmentSlot.HAND ? EquipmentSlot.OFF_HAND : EquipmentSlot.HAND;
    }

    // ---- placed blocks that keep their look (Nether Reactor Core)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        CustomItems.Definition d = def(item);
        if (d == null || !d.placedModel() || d.model() == null) {
            return;
        }
        Block block = event.getBlockPlaced();
        String[] earned = items.earnedOf(item);
        ItemStack look = ItemStack.of(Material.PAPER);
        look.setData(DataComponentTypes.ITEM_MODEL, d.model());
        block.getWorld().spawn(block.getLocation().add(0.5, 0.5, 0.5), ItemDisplay.class, disp -> {
            disp.setItemStack(look);
            disp.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            disp.setTransformationMatrix(new Matrix4f().scale(1.004f)); // just over the block, no flicker
            disp.setBrightness(new Display.Brightness(15, 15));
            PersistentDataContainer pdc = disp.getPersistentDataContainer();
            pdc.set(placedKey, PersistentDataType.STRING, d.id());
            pdc.set(placedByKey, PersistentDataType.STRING, earned[0]);
            pdc.set(placedAtKey, PersistentDataType.STRING, earned[1]);
        });
    }

    private ItemDisplay placedAt(Block block) {
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        for (Entity e : block.getWorld().getNearbyEntities(center, 0.2, 0.2, 0.2)) {
            if (e instanceof ItemDisplay d && d.getPersistentDataContainer().has(placedKey)) {
                return d;
            }
        }
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        ItemDisplay display = placedAt(event.getBlock());
        if (display == null) {
            return;
        }
        PersistentDataContainer pdc = display.getPersistentDataContainer();
        CustomItems.Definition d = items.definition(pdc.get(placedKey, PersistentDataType.STRING));
        display.remove();
        if (d != null) {
            event.setDropItems(false);
            Location at = event.getBlock().getLocation().add(0.5, 0.5, 0.5);
            at.getWorld().dropItemNaturally(at, items.create(d,
                    pdc.getOrDefault(placedByKey, PersistentDataType.STRING, ""),
                    pdc.getOrDefault(placedAtKey, PersistentDataType.STRING, "")));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        protect(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        protect(event.blockList());
    }

    private void protect(List<Block> blocks) {
        blocks.removeIf(b -> b.getType() == Material.BEACON && placedAt(b) != null);
    }

    /** A placed look whose block is gone (e.g. replaced by a command) is cleaned up when its chunk loads. */
    @EventHandler
    public void onLoad(EntitiesLoadEvent event) {
        for (Entity e : event.getEntities()) {
            if (e instanceof ItemDisplay d && d.getPersistentDataContainer().has(placedKey)
                    && d.getLocation().getBlock().getType().isAir()) {
                d.remove();
            }
        }
    }
}
