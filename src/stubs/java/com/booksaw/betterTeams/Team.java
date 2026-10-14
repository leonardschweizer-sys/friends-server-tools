package com.booksaw.betterTeams;

import com.booksaw.betterTeams.team.TeamManager;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.Inventory;

/**
 * Compile-only stand-in for BetterTeams' Team class (BetterTeams 5.1.7).
 * Only the members FriendsTools calls are declared, with the exact same signatures.
 * Never packaged; the real class is used at runtime.
 */
public class Team {

    public static Team getTeam(OfflinePlayer player) {
        throw new UnsupportedOperationException("stub");
    }

    public static TeamManager getTeamManager() {
        throw new UnsupportedOperationException("stub");
    }

    public String getName() {
        throw new UnsupportedOperationException("stub");
    }

    public Inventory getEchest() {
        throw new UnsupportedOperationException("stub");
    }

    public double getMoney() {
        throw new UnsupportedOperationException("stub");
    }

    public void setMoney(double money) {
        throw new UnsupportedOperationException("stub");
    }
}
