# StaffWarnV - Paper Plugin

A Paper plugin that alerts staff members when they use commands that regular players don't have access to.

## Features

- **Permission Tracking**: The plugin checks if the command's required permission is available to default player groups.
- **LuckPerms Integration**: Uses the LuckPerms API to check permissions across different server contexts.
- **Origin Identification**: Shows which permission group granted the staff member access to the command.
- **Server Context Awareness**: Respects server-specific permission contexts and excluded servers.

## Requirements

- Paper 1.21+
- LuckPerms 5.4+
- EssentialsX (optional, for command aliases)

## Installation

1. Download the latest release
2. Place the JAR file in your server's `plugins` folder
3. Start your server
4. Configure the plugin in `plugins/StaffWarnV/`

## Configuration

The plugin uses TOML configuration files:

- `config.toml` - Main configuration (default groups, excluded servers, debug settings)
- `messages.toml` - Message templates using MiniMessage format
- `commandPermissions.toml` - Command to permission mappings

## Usage

Once installed and configured, the plugin will automatically:

- Monitor all command usage by players
- Check if the command requires special permissions
- Alert staff members when they use privileged commands
- Show which permission group granted them access

## License

This project is licensed under the GNU Affero General Public License v3.0.
