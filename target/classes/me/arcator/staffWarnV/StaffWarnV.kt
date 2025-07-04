package me.arcator.staffWarnV

import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.entity.Player
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger
import org.bukkit.event.EventPriority
import org.bukkit.Bukkit
import org.bukkit.scheduler.BukkitTask
import org.bukkit.scheduler.BukkitRunnable
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import java.time.Duration
import java.util.function.Consumer

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
    private var cleanupTask: AtomicReference<ScheduledTask?> = AtomicReference(null)

    /**
     * Initializes the plugin on server startup
     */
    override fun onEnable() {
        try {
            // Save default config if it doesn't exist
            saveDefaultConfig()
            
            permissionMan = PermissionManager(logger, dataFolder.toPath())
            server.pluginManager.registerEvents(this, this)
            
            // Schedule cache cleanup every 5 minutes using Paper's async scheduler
            val task = Bukkit.getAsyncScheduler().runAtFixedRate(this, Consumer {
                permissionMan.cleanupCache()
            }, 5 * 60 * 20L, 5 * 60 * 20L, java.util.concurrent.TimeUnit.MILLISECONDS)
            cleanupTask.set(task)
            
            logger.info("StaffWarnV initialized successfully")
        } catch (e: Exception) {
            logger.severe("Failed to initialize StaffWarnV: ${e.message}")
            e.printStackTrace()
            server.pluginManager.disablePlugin(this)
        }
    }

    /**
     * Cleanup when plugin is disabled
     */
    override fun onDisable() {
        cleanupTask.getAndSet(null)?.cancel()
        logger.info("StaffWarnV disabled")
    }

    /**
     * Monitors command execution and alerts staff about privileged commands
     *
     * @param event The command preprocess event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerCommand(event: PlayerCommandPreprocessEvent) {
        val player = event.player

        // Skip if player has bypass permission
        if (player.hasPermission("staffwarnv.bypass")) return

        // Extract base command without arguments
        val command = event.message.substringBefore(" ").substring(1).lowercase() // Remove leading "/" and normalize

        // Skip if command not in our mapping
        val permission = permissionMan.commandPermissions[command] ?: return

        // Skip if player doesn't have permission (wouldn't be able to use it anyway)
        if (!player.hasPermission(permission)) return

        // Use Paper's async scheduler for permission check
        Bukkit.getAsyncScheduler().runNow(this) {
            try {
                val origin = permissionMan.checkPermissionAndAlert(player, permission)

                // Log and send alert to player
                if (origin != null) {
                    logger.info(
                        "${player.name} used ${event.message} (requires $permission) from $origin"
                    )
                    
                    // Use Paper's global region scheduler to send message on main thread
                    Bukkit.getGlobalRegionScheduler().execute(this, Runnable {
                        if (player.isOnline) {
                            player.sendMessage(createAlert(event.message, permission, origin))
                        }
                    })
                }
            } catch (e: Exception) {
                logger.severe("Error processing command alert: ${e.message}")
                e.printStackTrace()
            }
        }
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
