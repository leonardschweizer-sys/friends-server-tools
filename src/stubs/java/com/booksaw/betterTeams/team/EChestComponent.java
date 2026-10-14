package com.booksaw.betterTeams.team;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Compile-only stand-in for BetterTeams' EChestComponent (BetterTeams 5.1.7). Never packaged.
 * Only used for an instanceof check on the team ender chest's inventory holder.
 */
public class EChestComponent {

    public class EchestInventoryHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }
}
