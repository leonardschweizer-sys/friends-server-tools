# FriendsTools

Small Paper plugin for our 26.3 friends server.

## Features

| Command | What it does | Permission (default) |
|---|---|---|
| `/chestlog [page]` (`/cl`) | Look at a chest, barrel or shulker box and see who opened it, what they took or put in, and who placed or broke it. Nothing else is logged. | `friendstools.chestlog` (everyone) |
| `/biome <name>` | Changes the biome of the whole chunk you stand in (full height) for 32 diamonds. Survival only. Every overworld biome the server knows can be picked (new ones from updates, like 26.3's dappled forest, appear automatically); cave biomes are blocked. Grass, foliage and water colors update immediately. If you don't have enough diamonds: "You don't have enough diamonds to do this right now." | `friendstools.biome` (everyone) |
| `/teambal` (`/tbal`) | Shows how many diamonds are in your BetterTeams team ender chest. Diamond blocks count as 9; diamonds inside shulker boxes and bundles (also bundles inside shulkers or other bundles) count too. | `friendstools.teambal` (everyone) |
| `/teambal top` | Top 10 teams by diamonds. | same |
| `/speedrunreset [player]` | Resets the shared speedrun run for everyone: sends everyone in the run to the lobby, wipes the speedrun inventory group (Multiverse-Inventories), then regenerates `speedrun`, `speedrun_nether` and `speedrun_the_end` with one new random seed. Players must click twice within 10 s; 60 s cooldown between resets. No global message: only the player who reset sees the progress and the new seed, and players in the run are told why they were sent to the lobby. Meant for a lobby NPC: `console_command speedrunreset {player}`. Needs Multiverse `confirm-mode: disable_console`. | `friendstools.speedrun.reset` (op; the NPC runs it from the console) |
| `/ping [player]` | Your own ping, or another online player's, in green/yellow/red. | `friendstools.ping` (everyone) |
| `/pvp [on\|off]` | Turn your own PvP on or off. Off by default. Two players can only hurt each other when both have it on: hits, arrows, tridents and other projectiles, splash and lingering potions (healing still works), tamed wolves, fire, TNT, end crystals, channeling lightning, knockback from wind charges, maces and fishing rods. Pets of players with PvP off are protected too. After a PvP hit you can't turn it off for 30 s. Players with PvP on get a red ⚔ after their name, in the tab list and above their head. | `friendstools.pvp` (everyone) |
| `/pvp <on\|off> <player>` | Set someone else's PvP. | `friendstools.pvp.others` (op) |
| `/upload` | Private link (15 min, one upload) to a small website: pick an mp3, wav or flac file (max 50 MB) and a disc name, and it goes straight into `plugins/CustomDiscs/musicdata`. In chat a button then runs `/cd create local` for the disc in your hand. Needs its own port (`disc-upload.port` and `disc-upload.address`). Optional free https: a DuckDNS name plus token (`disc-upload.duckdns-domain`, `duckdns-token`) and FriendsTools gets and renews a Let's Encrypt certificate itself (DNS-01 through DuckDNS, works on any port; files in `plugins/FriendsTools/https/`). The page background can be replaced with `plugins/FriendsTools/web/background.jpg`. | `friendstools.discupload` (everyone) |
| `/register <pw> <pw>`, `/login <pw>`, `/changepassword <old> <new>` | Cracked players (offline mode only): set a password on the first join, log in later. Same IP logs in automatically for 30 days. | everyone |
| `/premium` | Offline mode: protect your (paid) name with your Mojang account; checked on every join from then on. Keeps your progress. | everyone |
| `/auth list \| info \| setpassword \| unregister \| protect \| unprotect` | Login admin: see accounts, set/reset passwords (also to pre-register a cracked friend), protect a new premium friend's name before their first join. | `friendstools.auth.admin` (op) |
| `/friendstools reload` | Reloads `config.yml`. | `friendstools.admin` (op) |

Also:
- **Death heads:** every player death drops that player's head (with the death message and date), also with keepInventory.
- **PvP marker above heads:** shown through PacketEvents with a separate copy of the player's team (same tag and color) on each client; the server's scoreboard and BetterTeams are not touched. Without PacketEvents the marker only shows in the tab list. PvP settings are saved in `plugins/FriendsTools/pvp.json`.
- **Login system (offline mode):** lets cracked players join without risk for everyone else. Everyone who played before keeps exactly the same ID, so inventories, advancements, teams and ranks stay. Their names are protected: joining with them needs Mojang's login check (done through PacketEvents), so a cracked client can't use them. Cracked players must be whitelisted (`/whitelist add <name>` works by name) and use a password. Until logged in they can't move, chat, use commands, items or voice chat. A known name is never let in with a different ID; if the check can't run, the join is refused. Accounts are in `plugins/FriendsTools/accounts.json`. Does nothing in online mode except remember players.
- **Attribute swapping:** turned back on (Paper `unsupported-settings.update-equipment-on-player-actions: false`), applied right away and written to `config/paper-global.yml`.
- **Raid captains:** the captain of each raid wave drops an ominous bottle, so raid farms keep going.
- **Tab list:** team tag (BetterTeams prefix and color) + name + ping in green/yellow/red. Replaces TabListPing, whose custom tab names hid the team tags. Stays off while TabListPing is installed. `tab.*` in the config.
- **Logout on a cushion/vehicle:** players get off cushions, boats, horses and minecarts when they log out, so the vehicle stays where it was instead of being respawned in the lobby at their survival coordinates on the next join.
- **"mvinv:suppressed" in chat:** Multiverse-Inventories labels advancements it re-grants during a world-group change with that text, and Paper broadcast it. FriendsTools removes the message.
- **Speedrun chat:** deaths and advancements in the speedrun worlds are only shown to players in the run (not in global chat or on Discord). `speedrun.run-only-messages`.
- **Fishing-rod pearl stasis:** cast a rod onto a wooden or weighted pressure plate and an invisible armor stand keeps the plate pressed, even when Paper removes the bobber (nether portal, other world, logging out). Using any fishing rod again, from anywhere, removes it (and any leftover bare invisible armor stand on that plate, e.g. from the old Paper Stasis Fixer plugin) and fires the stasis. One link per player, survival worlds only (`stasis.worlds`). Links are saved in `plugins/FriendsTools/stasis.json`.
- The diamond count is written into BetterTeams' own team money, so `/team baltop` and team info show it too. It updates whenever someone closes the team ender chest, and every 10 minutes.
- **Weather fix:** re-sends the correct weather after joining and after changing worlds, so rain from survival no longer sticks in the lobby.

## Build

GitHub Actions builds the jar on every push and publishes it as a release. Download it from the newest release under **Releases** (Assets → `FriendsTools-<version>.jar`).

The build uses Java 25 and the real Paper API (`paper-api 26.1.2`, which runs fine on 26.3). BetterTeams is called through small compile-only stand-in classes in `src/stubs`. They are not packaged, so the real BetterTeams 5.1.7 classes are used on the server.

## Install

1. Put `FriendsTools-<version>.jar` into `plugins/` (delete the older FriendsTools jar first) and restart.
2. Optional: edit `plugins/FriendsTools/config.yml` (prices, biome list, messages), then `/friendstools reload`.

Data: the chest log is stored in `plugins/FriendsTools/chestlog.json`, saved once a minute and on shutdown.
