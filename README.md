# StaffWarnV - Paper Plugin

A modern Paper plugin that alerts staff members when they use commands that regular players don't have access to.

## Features

- **Paper-Optimized**: Built specifically for Paper servers with modern APIs and optimizations
- **Permission Tracking**: The plugin checks if the command's required permission is available to default player groups
- **LuckPerms Integration**: Uses the LuckPerms API to check permissions across different server contexts
- **Origin Identification**: Shows which permission group granted the staff member access to the command
- **Server Context Awareness**: Respects server-specific permission contexts and excluded servers
- **Modern Scheduler**: Uses Paper's threaded region scheduler for optimal performance
- **Adventure Text**: Uses MiniMessage for rich text formatting

## Requirements

- **Paper 1.21+** (Required - this is a Paper-only plugin)
- **LuckPerms 5.4+** (Required)
- **EssentialsX** (Optional, for command aliases)

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

## Paper-Specific Features

- **Threaded Region Scheduler**: Uses Paper's modern scheduler for optimal performance
- **Adventure Text Components**: Rich text formatting with MiniMessage
- **Paper Plugin Metadata**: Proper `paper-plugin.yml` configuration
- **Modern Event Handling**: Optimized event processing for Paper servers

## Usage

Once installed and configured, the plugin will automatically:

- Monitor all command usage by players
- Check if the command requires special permissions
- Alert staff members when they use privileged commands
- Show which permission group granted them access

## Permissions

- `staffwarnv.bypass` - Allows players to bypass StaffWarnV alerts (default: op)

## License

This project is licensed under the GNU Affero General Public License v3.0.
