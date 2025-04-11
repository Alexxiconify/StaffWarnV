package me.arcator.staffWarnV

import com.electronwill.nightconfig.core.file.FileConfig
import com.velocitypowered.api.proxy.Player
import java.nio.file.Files
import java.nio.file.Path
import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.context.MutableContextSet
import net.luckperms.api.node.metadata.types.InheritanceOriginMetadata
import net.luckperms.api.query.QueryOptions
import org.slf4j.Logger

/**
 * Manages permission checks and configuration for the StaffWarnV plugin
 *
 * This class handles loading configuration files, checking permissions against LuckPerms,
 * and determining the origin of a permission for alert messages.
 *
 * @property logger Logger for outputting messages
 * @property dataDirectory Path to the plugin's data directory
 */
internal class PermissionManager(
    private val logger: Logger,
    private val dataDirectory: Path
) {
    /** Template for alert messages with placeholders */
    val alertTemplate: String

    /** Map of command names to their required permissions, optimized for lookup */
    val commandPermissions: Map<String, String>

    /** Thread-safe cache for permission checks to reduce LuckPerms API calls */
    private val permissionCheckCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** Thread-safe cache for permission origin lookups */
    private val permissionOriginCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** List of default groups that are exempt from alerts */
    private val defaultGroups: List<String>

    /** List of servers where command alerts are disabled */
    private val excludedServers: List<String>

    /** Whether verbose debug logging is enabled */
    private val verboseLogging: Boolean

    /**
     * Checks if a player's permission requires an alert
     *
     * This method determines if a player should be alerted about their permission usage
     * based on the server they're on, default groups, and permission origin.
     *
     * @param player The player who executed the command
     * @param permission The permission required for the command
     * @return The origin of the permission if an alert should be shown, null otherwise
     */
    fun checkPermissionAndAlert(player: Player, permission: String): String? {
        // Skip if player is not on a server
        val serverOpt = player.currentServer
        if (!serverOpt.isPresent) {
            if (verboseLogging) logger.debug("Skipping alert for ${player.username} - not on a server")
            return null
        }

        val server = serverOpt.get().serverInfo.name.lowercase()

        // Skip excluded servers
        if (server in excludedServers) {
            if (verboseLogging) logger.debug("Skipping alert for ${player.username} - server $server is excluded")
            return null
        }

        // Create server context for permission checks
        val context = MutableContextSet.create().apply {
            add("server", server)
        }

        // Skip if default groups have this permission
        if (defaultGroupsHavePermission(context, permission)) {
            if (verboseLogging) logger.debug("Skipping alert for ${player.username} - permission $permission is in default groups")
            return null
        }

        // Get origin of permission for this player
        val origin = getPermissionOrigin(player, context, permission)
        if (verboseLogging) {
            logger.debug("Alert triggered for ${player.username} - permission $permission from $origin on server $server")
        }
        return origin
    }

    /**
     * Checks if any of the default groups have the specified permission
     * Uses caching to improve performance for repeated checks
     *
     * @param context The context to check permissions in (e.g., server context)
     * @param permission The permission to check
     * @return true if any default group has the permission, false otherwise
     */
    fun defaultGroupsHavePermission(context: MutableContextSet, permission: String): Boolean {
        // Create cache key using server context and permission
        val serverName = context.getValues("server").firstOrNull() ?: "global"
        val cacheKey = "$serverName:$permission"

        // Return cached result if available
        permissionCheckCache[cacheKey]?.let { return it }

        val lpApi = LuckPermsProvider.get()
        val result = defaultGroups.any { groupName ->
            lpApi.groupManager.getGroup(groupName)?.cachedData?.getPermissionData(
                QueryOptions.contextual(
                    context,
                ),
            )
                ?.checkPermission(permission)?.asBoolean()
                ?: false
        }

        // Cache the result
        permissionCheckCache[cacheKey] = result

        // Limit cache size to avoid memory issues
        if (permissionCheckCache.size > 1000) {
            permissionCheckCache.keys.take(100).forEach { permissionCheckCache.remove(it) }
        }

        return result
    }

    /**
     * Determines the origin of a permission for a player
     * Uses caching to improve performance for repeated lookups
     *
     * @param player The player to check
     * @param context The context to check permissions in
     * @param permission The permission to find the origin for
     * @return The name of the group that grants the permission, or "unknown"
     */
    fun getPermissionOrigin(
        player: Player,
        context: MutableContextSet,
        permission: String
    ): String {
        // Create cache key
        val uuid = player.uniqueId.toString()
        val serverName = context.getValues("server").firstOrNull() ?: "global"
        val cacheKey = "$uuid:$serverName:$permission"

        // Check cache first
        permissionOriginCache[cacheKey]?.let { return it }

        return try {
            val lpApi = LuckPermsProvider.get()
            val user = lpApi.getPlayerAdapter(Player::class.java).getUser(player)

            val origin = user.resolveDistinctInheritedNodes(QueryOptions.contextual(context))
                .firstOrNull { node -> node.key == permission }
                ?.metadata(InheritanceOriginMetadata.KEY)?.origin?.name ?: "unknown"

            // Cache the result
            permissionOriginCache[cacheKey] = origin

            // Limit cache size to avoid memory issues
            if (permissionOriginCache.size > 1000) {
                permissionOriginCache.keys.take(100).forEach { permissionOriginCache.remove(it) }
            }

            origin
        } catch (e: Exception) {
            logger.warn("Error determining permission origin: ${e.message}")
            "unknown"
        }
    }

    /**
     * Loads a configuration file, creating it if it doesn't exist
     *
     * @param file The name of the configuration file
     * @return The loaded FileConfig object
     */
    private fun getConfig(file: String): FileConfig {
        val configFile = dataDirectory.resolve(file)

        try {
            // Create data directory if it doesn't exist
            if (Files.notExists(dataDirectory)) {
                val created = Files.createDirectories(dataDirectory)
                logger.info("Created config folder: $created")
            }

            // Log if we're using the default config file
            if (Files.notExists(configFile)) {
                logger.info("Creating default config at: {}", configFile.toAbsolutePath())
            }

            // Load the config with default values if the file doesn't exist
            return FileConfig.builder(configFile)
                .defaultData(javaClass.getResource("/$file"))
                .autosave()
                .build()
                .apply { load() }
        } catch (e: Exception) {
            logger.error("Failed to load config file $file: ${e.message}", e)
            throw RuntimeException("Could not load configuration", e)
        }
    }

    /**
     * Initializes the permission manager by loading all configuration files
     */
    init {
        try {
            // Load command permissions mapping with optimized access
            getConfig("commandPermissions.toml").use { commands ->
                // Create a map optimized for faster lookups with expected size
                val commandCount = commands.entrySet().size
                val commandMap = HashMap<String, String>(commandCount + (commandCount / 3))

                // Populate the map with command -> permission pairs
                commands.entrySet().forEach {
                    commandMap[it.key] = it.getValue<String>()
                }

                commandPermissions = commandMap
                logger.debug("Command permission map created with ${commandMap.size} entries")
            }

            // Load main configuration
            getConfig("config.toml").use { conf ->
                defaultGroups = conf.get<List<String>>("defaultGroups")
                excludedServers = conf.get<List<String>>("excludedServers")
                verboseLogging = conf.getOrElse("debug.verbose", false)
            }

            // Load message templates
            getConfig("messages.toml").use { messages ->
                alertTemplate = messages.get<String>("alert")
            }

            logger.info("Loaded ${commandPermissions.size} command permissions")
            logger.info("Configured with ${defaultGroups.size} default groups and ${excludedServers.size} excluded servers")

            if (verboseLogging) {
                logger.debug("Default groups: ${defaultGroups.joinToString()}")
                logger.debug("Excluded servers: ${excludedServers.joinToString()}")
            }
        } catch (e: Exception) {
            logger.error("Error during initialization: ${e.message}", e)
            throw e
        }
    }
}
