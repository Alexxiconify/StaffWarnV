package me.arcator.staffWarnV;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.event.EventPriority;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.bukkit.plugin.PluginLoadOrder;

/**
 * StaffWarnV - A Paper plugin to warn staff members about privileged command usage
 *
 * This plugin monitors command execution and alerts staff when they use commands that require
 * special permissions, helping to prevent accidental privilege escalation.
 */
public class StaffWarnV extends JavaPlugin implements Listener {
    private PermissionManager permissionMan;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final AtomicReference<ScheduledTask> cleanupTask = new AtomicReference<>();

    /**
     * Initializes the plugin on server startup
     */
    @Override
    public void onEnable() {
        try {
            // Save default config if it doesn't exist
            saveDefaultConfig();
            
            permissionMan = new PermissionManager(getLogger(), getDataFolder().toPath());
            getServer().getPluginManager().registerEvents(this, this);
            
            // Schedule cache cleanup every 5 minutes using Paper's async scheduler
            ScheduledTask task = Bukkit.getAsyncScheduler().runAtFixedRate(this, new Consumer<ScheduledTask>() {
                @Override
                public void accept(ScheduledTask scheduledTask) {
                    permissionMan.cleanupCache();
                }
            }, 5 * 60 * 20L, 5 * 60 * 20L, TimeUnit.MILLISECONDS);
            cleanupTask.set(task);
            
            getLogger().info("StaffWarnV initialized successfully");
        } catch (Exception e) {
            getLogger().severe("Failed to initialize StaffWarnV: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    /**
     * Cleanup when plugin is disabled
     */
    @Override
    public void onDisable() {
        ScheduledTask task = cleanupTask.getAndSet(null);
        if (task != null) {
            task.cancel();
        }
        getLogger().info("StaffWarnV disabled");
    }

    /**
     * Monitors command execution and alerts staff about privileged commands
     *
     * @param event The command preprocess event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();

        // Skip if player has bypass permission
        if (player.hasPermission("staffwarnv.bypass")) return;

        // Extract base command without arguments
        String command = event.getMessage().substring(1).split(" ")[0].toLowerCase(); // Remove leading "/" and normalize

        // Skip if command not in our mapping
        String permission = permissionMan.getCommandPermissions().get(command);
        if (permission == null) return;

        // Skip if player doesn't have permission (wouldn't be able to use it anyway)
        if (!player.hasPermission(permission)) return;

        // Use Paper's async scheduler for permission check
        Bukkit.getAsyncScheduler().runNow(this, new Consumer<ScheduledTask>() {
            @Override
            public void accept(ScheduledTask scheduledTask) {
                try {
                    String origin = permissionMan.checkPermissionAndAlert(player, permission);

                    // Log and send alert to player
                    if (origin != null) {
                        getLogger().info(
                            player.getName() + " used " + event.getMessage() + " (requires " + permission + ") from " + origin
                        );
                        
                        // Use Paper's global region scheduler to send message on main thread
                        Bukkit.getGlobalRegionScheduler().execute(StaffWarnV.this, new Runnable() {
                            @Override
                            public void run() {
                                if (player.isOnline()) {
                                    player.sendMessage(createAlert(event.getMessage(), permission, origin));
                                }
                            }
                        });
                    }
                } catch (Exception e) {
                    getLogger().severe("Error processing command alert: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        });
    }

    /**
     * Creates an alert message for a privileged command usage
     *
     * @param command The command that was executed
     * @param permission The permission required for the command
     * @param origin The origin of the permission (group name)
     * @return A formatted component with the alert message
     */
    private Component createAlert(String command, String permission, String origin) {
        String message = permissionMan.getAlertTemplate()
            .replace("%command%", command)
            .replace("%permission%", permission)
            .replace("%origin%", origin);

        return mm.deserialize(message);
    }
} 