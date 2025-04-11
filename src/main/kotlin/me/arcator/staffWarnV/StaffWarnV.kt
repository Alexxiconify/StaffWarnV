package me.arcator.staffWarnV

import com.google.inject.Inject
import com.velocitypowered.api.event.PostOrder
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.command.CommandExecuteEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import java.nio.file.Path
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.slf4j.Logger

/**
 * StaffWarnV - A Velocity plugin to warn staff members about privileged command usage
 *
 * This plugin monitors command execution and alerts staff when they use commands
 * that require special permissions, helping to prevent accidental privilege escalation.
 * @property proxy The Velocity proxy server instance
 * @property logger Plugin logger
 * @property dataDirectory Plugin data directory
 */
@Plugin(
    authors = ["Restitutor"],
    id = "staffwarnv",
    name = "StaffWarnV",
    version = "1.0.0",
)
class StaffWarnV @Inject constructor(
    private val proxy: ProxyServer,
    private val logger: Logger,
    @DataDirectory private val dataDirectory: Path
) {
    private lateinit var permissionMan: PermissionManager
    private val mm = MiniMessage.miniMessage()

    /**
     * Initializes the plugin on proxy startup
     *
     * @param event The proxy initialization event
     */
    @Subscribe
    fun onProxyInitialization(event: ProxyInitializeEvent) {
        try {
            permissionMan = PermissionManager(logger, dataDirectory)
            logger.info("StaffWarnV initialized successfully")
        } catch (e: Exception) {
            logger.error("Failed to initialize StaffWarnV: ${e.message}", e)
        }
    }


    /**
     * Monitors command execution and alerts staff about privileged commands
     *
     * @param event The command execution event
     */
    @Subscribe(priority = 1, order = PostOrder.CUSTOM)
    fun onCommand(event: CommandExecuteEvent) {
        // Skip non-player sources
        val player = event.commandSource as? Player ?: return

        // Extract base command without arguments
        val command = event.command.substringBefore(" ")

        // Skip if command not in our mapping
        val permission = permissionMan.commandPermissions[command] ?: return

        // Skip if player doesn't have permission (wouldn't be able to use it anyway)
        if (!player.hasPermission(permission)) return

        proxy.scheduler.buildTask(this) { ->
            try {
                val origin = permissionMan.checkPermissionAndAlert(player, permission)

                // Log and send alert to player
                if (origin != null) {
                    logger.info("${player.username} used ${event.command} (requires $permission) from $origin")
                    player.sendMessage(createAlert(event.command, permission, origin))
                }
            } catch (e: Exception) {
                logger.error("Error processing command alert: ${e.message}", e)
            }
        }.schedule()
    }

    /**
     * Creates an alert message for a privileged command usage
     *
     * @param command The command that was executed
     * @param permission The permission required for the command
     * @param origin The origin of the permission (group name)
     * @return A formatted component with the alert message
     */
    private fun createAlert(command: String, permission: String, origin: String): Component {
        val message = permissionMan.alertTemplate
            .replace("%command%", command)
            .replace("%permission%", permission)
            .replace("%origin%", origin)

        return mm.deserialize(message)
    }

}
