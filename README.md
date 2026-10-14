# FriendsTools

Small Paper plugin for our 26.3 friends server.

## Features

| Command | What it does | Permission (default) |
|---|---|---|
| `/chestlog [page]` (`/cl`) | Look at a chest, barrel or shulker box and see who opened it, what they took or put in, and who placed or broke it. Nothing else is logged. | `friendstools.chestlog` (everyone) |
| `/biome <name>` | Changes the biome of the whole chunk you stand in (full height) for 32 diamonds. Survival only. Every overworld biome the server knows can be picked (new ones from updates, like 26.3's dappled forest, appear automatically); cave biomes are blocked. Grass, foliage and water colors update immediately. If you don't have enough diamonds: "You don't have enough diamonds to do this right now." | `friendstools.biome` (everyone) |
| `/teambal` (`/tbal`) | Shows how many diamonds are in your BetterTeams team ender chest. Diamond blocks count as 9; diamonds inside shulker boxes and bundles (also bundles inside shulkers or other bundles) count too. | `friendstools.teambal` (everyone) |
| `/teambal top` | Top 10 teams by diamonds. | same |
| `/friendstools reload` | Reloads `config.yml`. | `friendstools.admin` (op) |

Also:
- The diamond count is written into BetterTeams' own team money, so `/team baltop` and team info show it too. It updates whenever someone closes the team ender chest, and every 10 minutes.
- **Weather fix:** re-sends the correct weather after joining and after changing worlds, so rain from survival no longer sticks in the lobby.

## Build

GitHub Actions builds the jar on every push and publishes it as a release. Download it from the newest release under **Releases** (Assets → `FriendsTools-<version>.jar`).

The build uses Java 25 and the real Paper API (`paper-api 26.1.2`, which runs fine on 26.3). BetterTeams is called through small compile-only stand-in classes in `src/stubs`. They are not packaged, so the real BetterTeams 5.1.7 classes are used on the server.

## Install

1. Put `FriendsTools-<version>.jar` into `plugins/` (delete the older FriendsTools jar first) and restart.
2. Optional: edit `plugins/FriendsTools/config.yml` (prices, biome list, messages), then `/friendstools reload`.

Data: the chest log is stored in `plugins/FriendsTools/chestlog.json`, saved once a minute and on shutdown.
