package com.booksaw.betterTeams.team;

import com.booksaw.betterTeams.Team;

import java.util.Map;
import java.util.UUID;

/**
 * Compile-only stand-in for BetterTeams' TeamManager (BetterTeams 5.1.7). Never packaged.
 */
public abstract class TeamManager {

    public Map<UUID, Team> getLoadedTeamListClone() {
        throw new UnsupportedOperationException("stub");
    }
}
