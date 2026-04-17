# ServerUtils

ServerUtils is a custom Spigot/Bukkit plugin (API version 1.21) designed for the BakoSMP server. It provides a variety of utility commands, an authentication system (Sync), and economy integration.

## Features

- **Authentication System**: Mandatory account sync using `/sync <username> <password>`.
- **Economy Integration**: Works with Vault to provide a balance scoreboard and money transfer capabilities.
- **Warning System**: Comprehensive warning management (warn, get warnings, clear warnings) for both online and offline players.
- **Scoreboard**: A custom side-bar scoreboard displaying player balance and server information.
- **Teleportation**: Quick commands for teleporting to the event hub and the main world.
- **Server Management**:
  - LifeSteal auto-revive system toggle.
  - Player count tracking and exporting to JSON.
  - Command log filtering for sensitive information.

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
- `.env`: Requires `AES_KEY` for password encryption.

## Dependencies

- **Spigot API**: 1.21.4-R0.1-SNAPSHOT
- **Vault API**: fdddf25e44
- **Jackson Databind**: 2.20.0
- **Gson**: 2.10.1
- **Dotenv Java**: 3.0.0
