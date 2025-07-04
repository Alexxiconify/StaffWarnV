package me.arcator.staffWarnV

import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.entity.Player
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import java.util.logging.Logger

/**
 * StaffWarnV - A Paper plugin to warn staff members about privileged command usage
 *
 * This plugin monitors command execution and alerts staff when they use commands that require
 * special permissions, helping to prevent accidental privilege escalation.
 *
 * @property logger Plugin logger
 */
class StaffWarnV : JavaPlugin(), Listener {
    private lateinit var permissionMan: PermissionManager
    private val mm = MiniMessage.miniMessage()

    /**
     * Initializes the plugin on server startup
     */
    override fun onEnable() {
        try {
            permissionMan = PermissionManager(logger, dataFolder.toPath())
            server.pluginManager.registerEvents(this, this)
            logger.info("StaffWarnV initialized successfully")
        } catch (e: Exception) {
            logger.severe("Failed to initialize StaffWarnV: ${e.message}")
            e.printStackTrace()
            server.pluginManager.disablePlugin(this)
        }
    }

    /**
     * Monitors command execution and alerts staff about privileged commands
     *
     * @param event The command preprocess event
     */
    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    fun onPlayerCommand(event: PlayerCommandPreprocessEvent) {
        val player = event.player

        // Extract base command without arguments
        val command = event.message.substringBefore(" ").substring(1) // Remove leading "/"

        // Skip if command not in our mapping
        val permission = permissionMan.commandPermissions[command] ?: return

        // Skip if player doesn't have permission (wouldn't be able to use it anyway)
        if (!player.hasPermission(permission)) return

        // Run permission check asynchronously to avoid blocking the main thread
        server.scheduler.runTaskAsynchronously(this, Runnable {
            try {
                val origin = permissionMan.checkPermissionAndAlert(player, permission)

                // Log and send alert to player
                if (origin != null) {
                    logger.info(
                        "${player.name} used ${event.message} (requires $permission) from $origin"
                    )
                    
                    // Send message on main thread
                    server.scheduler.runTask(this@StaffWarnV, Runnable {
                        player.sendMessage(createAlert(event.message, permission, origin))
                    })
                }
            } catch (e: Exception) {
                logger.severe("Error processing command alert: ${e.message}")
                e.printStackTrace()
            }
        })
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
        val message =
            permissionMan.alertTemplate
                .replace("%command%", command)
                .replace("%permission%", permission)
                .replace("%origin%", origin)

        return mm.deserialize(message)
    }
}
