# Gemini CLI Project Context: ServerUtils

## Project Overview
- **Type**: Minecraft (Spigot) Plugin
- **Language**: Java 21
- **Build System**: Maven
- **Core Library**: Spigot API 1.21
- **Main Class**: `org.raindrippy.serversideutils.Main`

## Key Architecture & Patterns
- **Authentication**: Custom "Sync" system requiring an external API (`bakosmp.go.ro`). Players are frozen (Blindness, Spectator mode) until authenticated.
- **Security**: 
    - AES encryption for passwords stored in `authedPlayers.json`.
    - `CommandLogFilter` prevents sensitive commands (like `/sync`) from appearing in server logs.
- **Economy**: Integration with Vault for balances and transfers.
- **Data Persistence**:
    - YAML for warnings (`warnings.yml`).
    - JSON for configuration (`conf.json`) and external player count (`plrCount.json`).
- **Dependencies**: Uses `maven-shade-plugin` to relocate Jackson libraries to avoid conflicts.

## Development Workflows
- **Building**: `mvn clean package`
- **Configuration**:
    - A `.env` file MUST exist in the root with `AES_KEY`.
    - `plugin.yml` defines the commands and permissions.

## Coding Standards
- **Wait for Previous**: When modifying multiple files or running complex build tasks, ensure `wait_for_previous: true` if dependencies exist.
- **Surgical Edits**: Prefer `replace` over `write_file` for existing logic.
- **Safety**: NEVER log or expose the `AES_KEY` or raw passwords.

## Known Limitations / Quirks
- The plugin uses "PDT" hardcoded for moderation timestamps.
- Teleportation logic relies on Multiverse-Core (`mvtp`).
- LifeSteal reset logic is hardcoded for specific versions/plugins.
