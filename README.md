# ServerUtils

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)

ServerUtils is our custom Spigot/Bukkit plugin (uses 1.21 API) created for the BakoSMP server. It provides a variety of useful commands, an authentication system / whitelist replacement, and economy features.

## Quick overview

- Authentication: Mandatory account sync using `/sync <username> <password>`. This replaces the vanilla whitelist system on the server, which is notoriously broken on our software.
- Economy Integration: Works with (and depends on) Vault to provide a status scoreboard and custom cash transferring.
- Warning System: Comprehensive warning management for both online and offline players to supplement existing moderation utilities.
- Scoreboard: A custom side-bar scoreboard displaying player balance and season information, inspired by greater servers.
- Teleportation: Quick commands for teleporting to the event hub and the main world.
- LifeSteal system, player count tracking & filtering of sensitive commands from being logged.

## Commands

- `/eventhub`: Teleports to the event hub.
- `/return`: Teleports to the main world.
- `/togglescoreboard`: Toggles the status scoreboard.
- `/sendmoney <player> <amount>`: Sends money to another player.
- `/sync <username> <password>`: Synchronizes the RainDrippy account.
- `/warn <player> <reason>`: Warns a player.
- `/getwarnings <player>`: Displays a player's warnings.
- `/removewarning <player> <number>`: Removes a specific warning.
- `/clearwarnings <player>`: Clears all warnings for a player.
- `/owarn`, `/oclearwarnings`, `/oremovewarning`: Admin commands for offline players.
- `/setcounter <true|false>`: Toggles player tracking.
- `/lsrev <true|false>`: Toggles LifeSteal auto-revive.

## Permissions

- `serversideutils.*`: All permissions.
- `serversideutils.eventhub`: Default: true.
- `serversideutils.return`: Default: true.
- `serversideutils.togglescoreboard`: Default: true.
- `serversideutils.sendmoney`: Default: true.
- `serversideutils.sync`: Default: true.
- `serversideutils.warn`, `serversideutils.getwarnings`, `serversideutils.removewarning`, `serversideutils.clearwarnings`: Default: op.
- `serversideutils.setcounter`: Default: op.
- `serversideutils.lsrev`: Default: op.

## Configuration & Data

- `plugins/ServerUtils/warnings.yml`: Stores player warnings.
- `plugins/ServerUtils/conf.json`: General configuration (tracking, lsrev, seed).
- `authedPlayers.json`: (Root directory) Encrypted player credentials.
- `plrCount.json`: (Root directory) Current online player count.
- `.env`: Requires `AES_KEY` for password encryption (we dont store in plaintext!).

## Dependencies

- Spigot API: 1.21.4-R0.1-SNAPSHOT
- Vault API: fdddf25e44
- Jackson Databind: 2.20.0
- Gson: 2.10.1
- Dotenv Java: 3.0.0
