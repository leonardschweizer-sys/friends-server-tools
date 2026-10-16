package net.friendsserver.tools;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.CollisionRule;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.NameTagVisibility;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.OptionData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.ScoreBoardTeamInfo;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.TeamMode;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The PvP marker above the heads of players with PvP on (needs PacketEvents).
 *
 * <p>The name above a player's head is "team prefix + name + team suffix" of their scoreboard team, and BetterTeams
 * already uses that team for the tag. A team's suffix is the same for all its members, so the marker can't go
 * there on the server. Instead every client gets its own small team per player with PvP on ("CRIPS~pvp..."), with
 * the same prefix, color and settings as the real team plus the marker as suffix. The server's scoreboard is never
 * touched, so BetterTeams, the tab list and everything else keep working as before.</p>
 *
 * <p>To keep the clients consistent, every team packet sent to a client is mirrored here: players that are shown in
 * their own PvP team are taken out of the server's team packets for that client, and a removal from a team the
 * client doesn't have them in is never sent (the client would disconnect).</p>
 */
final class PvpNametags extends PacketListenerAbstract implements PvpManager.Nametags {

    /** Marks our per-player teams. It also sorts them right after the real team in the tab list. */
    private static final String MARK = "~pvp";

    private record Look(String team, Component prefix, Component suffix, NamedTextColor color,
                        NameTagVisibility visibility, CollisionRule collision, OptionData options) {

        ScoreBoardTeamInfo info() {
            return new ScoreBoardTeamInfo(Component.text(team), prefix, suffix, visibility, collision, color, options);
        }
    }

    /** One client's scoreboard teams, as far as the packets sent to it say (updated on the network thread). */
    private static final class ClientTeams {
        private final Map<String, Set<String>> members = new HashMap<>();
        private final Map<String, String> teamOf = new HashMap<>();

        boolean knows(String team) {
            return members.containsKey(team);
        }

        String teamOf(String entry) {
            return teamOf.get(entry);
        }

        void create(String team, Collection<String> entries) {
            members.computeIfAbsent(team, k -> new HashSet<>());
            add(team, entries);
        }

        void remove(String team) {
            Set<String> old = members.remove(team);
            if (old != null) {
                for (String entry : old) {
                    teamOf.remove(entry, team);
                }
            }
        }

        void add(String team, Collection<String> entries) {
            Set<String> set = members.get(team);
            if (set == null) {
                return; // the client ignores entries for a team it doesn't know
            }
            for (String entry : entries) {
                String previous = teamOf.put(entry, team);
                if (previous != null && !previous.equals(team)) {
                    Set<String> old = members.get(previous);
                    if (old != null) {
                        old.remove(entry);
                    }
                }
                set.add(entry);
            }
        }

        void removeEntries(String team, Collection<String> entries) {
            Set<String> set = members.get(team);
            if (set == null) {
                return;
            }
            for (String entry : entries) {
                set.remove(entry);
                teamOf.remove(entry, team);
            }
        }
    }

    private final FriendsTools plugin;
    private final PvpManager pvp;
    /** Names currently shown in their own PvP team (read on network threads). */
    private final Set<String> marked = ConcurrentHashMap.newKeySet();
    private final Map<User, ClientTeams> clients = Collections.synchronizedMap(new WeakHashMap<>());
    /** Main thread: per viewer, the PvP teams we created on their client and how they look. */
    private final Map<UUID, Map<String, Look>> sent = new HashMap<>();
    private final BukkitTask task;

    /** Factory with the interface as return type, so PvpManager loads without PacketEvents installed. */
    static PvpManager.Nametags create(FriendsTools plugin, PvpManager pvp) {
        return new PvpNametags(plugin, pvp);
    }

    private PvpNametags(FriendsTools plugin, PvpManager pvp) {
        super(PacketListenerPriority.HIGHEST);
        this.plugin = plugin;
        this.pvp = pvp;
        // Players already online (plugin reload): their clients have the server's teams.
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        for (Player player : Bukkit.getOnlinePlayers()) {
            User user = user(player);
            if (user != null) {
                clients.put(user, snapshot(board));
            }
        }
        PacketEvents.getAPI().getEventManager().registerListener(this);
        // Team tags or colors can change at any time (BetterTeams); keep the copies up to date
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::sync, 20L, 20L);
    }

    private static ClientTeams snapshot(Scoreboard board) {
        ClientTeams client = new ClientTeams();
        for (Team team : board.getTeams()) {
            client.create(team.getName(), team.getEntries());
        }
        return client;
    }

    private static User user(Player player) {
        try {
            return PacketEvents.getAPI().getPlayerManager().getUser(player);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ main thread

    @Override
    public void sync() {
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        Map<String, Look> wanted = new HashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Component marker = pvp.markerFor(player, false);
            if (marker != null) {
                wanted.put(player.getName(), lookOf(board, player.getName(), marker));
            }
        }
        // Update before sending anything, so the packet filter already treats them right
        marked.retainAll(wanted.keySet());
        marked.addAll(wanted.keySet());

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            User user = user(viewer);
            if (user == null) {
                continue;
            }
            Map<String, Look> mine = sent.computeIfAbsent(viewer.getUniqueId(), k -> new HashMap<>());
            Iterator<Map.Entry<String, Look>> it = mine.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Look> entry = it.next();
                if (!wanted.containsKey(entry.getKey())) {
                    remove(user, entry.getValue().team());
                    restore(user, board, entry.getKey());
                    it.remove();
                }
            }
            for (Map.Entry<String, Look> entry : wanted.entrySet()) {
                String name = entry.getKey();
                Look look = entry.getValue();
                Look previous = mine.get(name);
                if (previous != null && previous.equals(look)) {
                    continue;
                }
                if (previous == null || !previous.team().equals(look.team())) {
                    if (previous != null) {
                        remove(user, previous.team()); // their real team changed
                    }
                    send(user, new WrapperPlayServerTeams(look.team(), TeamMode.CREATE, look.info(), List.of(name)));
                } else {
                    send(user, new WrapperPlayServerTeams(look.team(), TeamMode.UPDATE, look.info(),
                            List.<String>of()));
                }
                mine.put(name, look);
            }
        }
    }

    @Override
    public void viewerJoined(Player player) {
        // A fresh client: it gets all of the server's teams during the join, ours follow on the next tick
        sent.remove(player.getUniqueId());
        Bukkit.getScheduler().runTask(plugin, this::sync);
    }

    @Override
    public void viewerQuit(Player player) {
        sent.remove(player.getUniqueId());
        // If they had PvP on, the others' clients get their normal team back on the next tick
        Bukkit.getScheduler().runTask(plugin, this::sync);
    }

    @Override
    public void close() {
        task.cancel();
        try {
            PacketEvents.getAPI().getEventManager().unregisterListener(this);
        } catch (RuntimeException ignored) {
            // PacketEvents already shut down
        }
        marked.clear();
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            Map<String, Look> mine = sent.get(viewer.getUniqueId());
            User user = user(viewer);
            if (mine == null || user == null) {
                continue;
            }
            for (Map.Entry<String, Look> entry : mine.entrySet()) {
                remove(user, entry.getValue().team());
                restore(user, board, entry.getKey());
            }
        }
        sent.clear();
        clients.clear();
    }

    private void remove(User user, String team) {
        send(user, new WrapperPlayServerTeams(team, TeamMode.REMOVE, (ScoreBoardTeamInfo) null, List.<String>of()));
    }

    /** Puts the player back into their real team on this client. */
    private void restore(User user, Scoreboard board, String name) {
        Team real = board.getEntryTeam(name);
        if (real != null) {
            send(user, new WrapperPlayServerTeams(real.getName(), TeamMode.ADD_ENTITIES, (ScoreBoardTeamInfo) null,
                    List.of(name)));
        }
    }

    private void send(User user, WrapperPlayServerTeams packet) {
        try {
            user.sendPacket(packet);
        } catch (RuntimeException e) {
            plugin.getLogger().fine("Could not send a PvP team packet: " + e);
        }
    }

    private static Look lookOf(Scoreboard board, String name, Component marker) {
        Team real = board.getEntryTeam(name);
        String team = (real == null ? "" : real.getName()) + MARK + Integer.toHexString(name.hashCode());
        Component prefix = Component.empty();
        Component suffix = Component.empty();
        NamedTextColor color = null;
        NameTagVisibility visibility = NameTagVisibility.ALWAYS;
        CollisionRule collision = CollisionRule.ALWAYS;
        boolean friendlyFire = true;
        boolean seeInvisible = false;
        if (real != null) {
            prefix = real.prefix();
            suffix = real.suffix();
            try {
                if (real.hasColor()) {
                    color = NamedTextColor.nearestTo(real.color());
                }
            } catch (IllegalStateException | IllegalArgumentException ignored) {
                // team without a usable color
            }
            visibility = switch (real.getOption(Team.Option.NAME_TAG_VISIBILITY)) {
                case NEVER -> NameTagVisibility.NEVER;
                case FOR_OTHER_TEAMS -> NameTagVisibility.HIDE_FOR_OTHER_TEAMS;
                case FOR_OWN_TEAM -> NameTagVisibility.HIDE_FOR_OWN_TEAM;
                default -> NameTagVisibility.ALWAYS;
            };
            collision = switch (real.getOption(Team.Option.COLLISION_RULE)) {
                case NEVER -> CollisionRule.NEVER;
                case FOR_OTHER_TEAMS -> CollisionRule.PUSH_OTHER_TEAMS;
                case FOR_OWN_TEAM -> CollisionRule.PUSH_OWN_TEAM;
                default -> CollisionRule.ALWAYS;
            };
            friendlyFire = real.allowFriendlyFire();
            seeInvisible = real.canSeeFriendlyInvisibles();
        }
        OptionData options = friendlyFire
                ? (seeInvisible ? OptionData.ALL : OptionData.FRIENDLY_FIRE)
                : (seeInvisible ? OptionData.FRIENDLY_CAN_SEE_INVISIBLE : OptionData.NONE);
        suffix = suffix.append(Component.space()).append(marker);
        return new Look(team, prefix, suffix, color, visibility, collision, options);
    }

    // ------------------------------------------------------------------ network threads

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() == PacketType.Play.Server.JOIN_GAME) {
            clients.put(event.getUser(), new ClientTeams()); // new client scoreboard
            return;
        }
        if (event.getPacketType() != PacketType.Play.Server.TEAMS) {
            return;
        }
        ClientTeams client;
        synchronized (clients) {
            client = clients.computeIfAbsent(event.getUser(), u -> new ClientTeams());
        }
        WrapperPlayServerTeams packet = new WrapperPlayServerTeams(event);
        String team = packet.getTeamName();
        boolean ours = team.contains(MARK);

        synchronized (client) {
            switch (packet.getTeamMode()) {
                case CREATE -> {
                    Collection<String> entries = ours ? packet.getPlayers() : withoutMarked(packet, event);
                    client.create(team, entries);
                }
                case REMOVE -> client.remove(team);
                case ADD_ENTITIES -> {
                    Collection<String> entries = ours ? packet.getPlayers() : withoutMarked(packet, event);
                    if (entries.isEmpty()) {
                        event.setCancelled(true);
                        return;
                    }
                    client.add(team, entries);
                }
                case REMOVE_ENTITIES -> {
                    List<String> kept = new ArrayList<>();
                    for (String entry : packet.getPlayers()) {
                        boolean valid = client.knows(team) ? team.equals(client.teamOf(entry)) : !marked.contains(entry);
                        if (valid) {
                            kept.add(entry);
                        }
                    }
                    if (kept.isEmpty()) {
                        event.setCancelled(true);
                        return;
                    }
                    if (kept.size() != packet.getPlayers().size()) {
                        packet.setPlayers(kept);
                        event.markForReEncode(true);
                    }
                    client.removeEntries(team, kept);
                }
                default -> {
                    // UPDATE: only looks, no members
                }
            }
        }
    }

    /** The server's team packet without the players that are shown in their own PvP team. */
    private Collection<String> withoutMarked(WrapperPlayServerTeams packet, PacketSendEvent event) {
        Collection<String> entries = packet.getPlayers();
        if (marked.isEmpty()) {
            return entries;
        }
        List<String> kept = new ArrayList<>(entries.size());
        for (String entry : entries) {
            if (!marked.contains(entry)) {
                kept.add(entry);
            }
        }
        if (kept.size() != entries.size()) {
            packet.setPlayers(kept);
            event.markForReEncode(true);
        }
        return kept;
    }
}
