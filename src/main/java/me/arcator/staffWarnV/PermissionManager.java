package me.arcator.staffWarnV;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Collection;

/**
 * Manages permission checks and configuration for the StaffWarnV plugin
 *
 * This class handles loading configuration files, checking permissions against LuckPerms, and
 * determining the origin of a permission for alert messages.
 */
public class PermissionManager {
    private final Logger logger;
    private final Path dataDirectory;
    
    /** Template for alert messages with placeholders */
    private String alertTemplate;

    /** Map of command names to their required permissions, optimized for lookup */
    private Map<String, String> commandPermissions;

    /** Thread-safe cache for permission checks to reduce LuckPerms API calls */
    private final ConcurrentHashMap<String, Boolean> permissionCheckCache = new ConcurrentHashMap<>();

    /** Thread-safe cache for permission origin lookups */
    private final ConcurrentHashMap<String, String> permissionOriginCache = new ConcurrentHashMap<>();

    /** List of default groups that are exempt from alerts */
    private List<String> defaultGroups;

    /** List of servers where command alerts are disabled */
    private List<String> excludedServers;

    /** Whether verbose debug logging is enabled */
    private boolean verboseLogging;

    /**
     * Constructor for PermissionManager
     * 
     * @param logger Logger for outputting messages
     * @param dataDirectory Path to the plugin's data directory
     */
    public PermissionManager(Logger logger, Path dataDirectory) {
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        initialize();
    }

    /**
     * Gets the alert template
     * 
     * @return The alert template string
     */
    public String getAlertTemplate() {
        return alertTemplate;
    }

    /**
     * Gets the command permissions map
     * 
     * @return Map of command names to permissions
     */
    public Map<String, String> getCommandPermissions() {
        return commandPermissions;
    }

    /**
     * Checks if a player's permission requires an alert
     *
     * This method determines if a player should be alerted about their permission usage based on
     * the server they're on, default groups, and permission origin.
     *
     * @param player The player who executed the command
     * @param permission The permission required for the command
     * @return The origin of the permission if an alert should be shown, null otherwise
     */
    public String checkPermissionAndAlert(Player player, String permission) {
        try {
            // Skip if player is not on a server (for Paper, this is always true if player is online)
            String server = player.getWorld().getName().toLowerCase();

            // Skip excluded servers
            if (excludedServers.contains(server)) {
                if (verboseLogging)
                    logger.fine("Skipping alert for " + player.getName() + " - server " + server + " is excluded");
                return null;
            }

            // Create server context for permission checks using reflection
            Object context = createContextSet(server);

            // Skip if default groups have this permission
            if (defaultGroupsHavePermission(context, permission)) {
                if (verboseLogging)
                    logger.fine(
                        "Skipping alert for " + player.getName() + " - permission " + permission + " is in default groups"
                    );
                return null;
            }

            // Get origin of permission for this player
            String origin = getPermissionOrigin(player, context, permission);
            if (verboseLogging) {
                logger.fine(
                    "Alert triggered for " + player.getName() + " - permission " + permission + " from " + origin + " on server " + server
                );
            }
            return origin;
        } catch (Exception e) {
            logger.warning("Error checking permission alert: " + e.getMessage());
            return null;
        }
    }

    /**
     * Checks if any of the default groups have the specified permission.
     * Uses caching to improve performance for repeated checks.
     *
     * @param context The context to check permissions in (e.g., server context)
     * @param permission The permission to check
     * @return true if any default group has the permission, false otherwise
     */
    public boolean defaultGroupsHavePermission(Object context, String permission) {
        try {
            // Create cache key using server context and permission
            String serverName = getServerFromContext(context);
            String cacheKey = serverName + ":" + permission;

            // Return cached result if available
            Boolean cached = permissionCheckCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }

            Object lpApi = getLuckPermsProvider();
            boolean result = defaultGroups.stream().anyMatch(groupName -> {
                try {
                    Object group = getGroup(lpApi, groupName);
                    if (group == null) return false;
                    
                    Object cachedData = getCachedData(group);
                    if (cachedData == null) return false;
                    
                    Object permissionData = getPermissionData(cachedData, context);
                    if (permissionData == null) return false;
                    
                    Object tristate = checkPermission(permissionData, permission);
                    return tristate != null && (Boolean) tristate;
                } catch (Exception e) {
                    logger.fine("Error checking permission for group " + groupName + ": " + e.getMessage());
                    return false;
                }
            });

            // Cache the result
            permissionCheckCache.put(cacheKey, result);

            // Limit cache size to avoid memory issues
            if (permissionCheckCache.size() > 1000) {
                permissionCheckCache.keySet().stream().limit(100).forEach(permissionCheckCache::remove);
            }

            return result;
        } catch (Exception e) {
            logger.warning("Error checking default groups permission: " + e.getMessage());
            return false;
        }
    }

    /**
     * Determines the origin of a permission for a player Uses caching to improve performance for
     * repeated lookups
     *
     * @param player The player to check
     * @param context The context to check permissions in
     * @param permission The permission to find the origin for
     * @return The name of the group that grants the permission, or "unknown"
     */
    public String getPermissionOrigin(Player player, Object context, String permission) {
        try {
            // Create cache key
            String uuid = player.getUniqueId().toString();
            String serverName = getServerFromContext(context);
            String cacheKey = uuid + ":" + serverName + ":" + permission;

            // Check cache first
            String cached = permissionOriginCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }

            Object lpApi = getLuckPermsProvider();
            Object user = getUser(lpApi, player);

            String origin = getPermissionOriginFromUser(user, context, permission);

            // Cache the result
            permissionOriginCache.put(cacheKey, origin);

            // Limit cache size to avoid memory issues
            if (permissionOriginCache.size() > 1000) {
                permissionOriginCache.keySet().stream().limit(100).forEach(permissionOriginCache::remove);
            }

            return origin;
        } catch (Exception e) {
            logger.warning("Error determining permission origin: " + e.getMessage());
            return "unknown";
        }
    }

    /**
     * Loads a configuration file, creating it if it doesn't exist
     *
     * @param file The name of the configuration file
     * @return The loaded FileConfiguration object
     */
    private FileConfiguration getConfig(String file) {
        File configFile = dataDirectory.resolve(file).toFile();

        try {
            // Create data directory if it doesn't exist
            if (!dataDirectory.toFile().exists()) {
                Files.createDirectories(dataDirectory);
                logger.info("Created config folder: " + dataDirectory);
            }

            // Log if we're using the default config file
            if (!configFile.exists()) {
                logger.info("Creating default config at: " + configFile.getAbsolutePath());
            }

            // Load the config with default values if the file doesn't exist
            FileConfiguration config = YamlConfiguration.loadConfiguration(configFile);
            
            // Save default config if it doesn't exist
            if (!configFile.exists()) {
                config.options().copyDefaults(true);
                config.save(configFile);
            }
            
            return config;
        } catch (Exception e) {
            logger.severe("Failed to load config file " + file + ": " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Could not load configuration", e);
        }
    }

    /**
     * Cleans up caches to prevent memory leaks
     * Called periodically by the main plugin
     */
    public void cleanupCache() {
        int permissionCacheSize = permissionCheckCache.size();
        int originCacheSize = permissionOriginCache.size();
        
        // Clear caches if they get too large
        if (permissionCheckCache.size() > 500) {
            permissionCheckCache.clear();
            logger.fine("Cleared permission check cache (was " + permissionCacheSize + " entries)");
        }
        
        if (permissionOriginCache.size() > 500) {
            permissionOriginCache.clear();
            logger.fine("Cleared permission origin cache (was " + originCacheSize + " entries)");
        }
    }

    /** Initializes the permission manager by loading all configuration files */
    private void initialize() {
        try {
            // Load command permissions mapping with optimized access
            FileConfiguration commands = getConfig("commandPermissions.yml");
            // Create a map optimized for faster lookups with expected size
            int commandCount = commands.getKeys(false).size();
            Map<String, String> commandMap = new HashMap<>(commandCount + (commandCount / 3));

            // Populate the map with command -> permission pairs
            for (String command : commands.getKeys(false)) {
                commandMap.put(command, commands.getString(command));
            }

            this.commandPermissions = commandMap;
            logger.fine("Command permission map created with " + commandMap.size() + " entries");

            // Load main configuration
            FileConfiguration conf = getConfig("config.yml");
            this.defaultGroups = conf.getStringList("defaultGroups");
            this.excludedServers = conf.getStringList("excludedServers");
            this.verboseLogging = conf.getBoolean("debug.verbose", false);

            // Load message templates
            FileConfiguration messages = getConfig("messages.yml");
            this.alertTemplate = messages.getString("alert");

            logger.info("Loaded " + commandPermissions.size() + " command permissions");
            logger.info(
                "Configured with " + defaultGroups.size() + " default groups and " + excludedServers.size() + " excluded servers"
            );

            if (verboseLogging) {
                logger.fine("Default groups: " + String.join(", ", defaultGroups));
                logger.fine("Excluded servers: " + String.join(", ", excludedServers));
            }
        } catch (Exception e) {
            logger.severe("Error during initialization: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }

    // Reflection helper methods for LuckPerms API access
    private Object createContextSet(String server) throws Exception {
        Class<?> contextSetClass = Class.forName("net.luckperms.api.context.MutableContextSet");
        Object context = contextSetClass.getMethod("create").invoke(null);
        contextSetClass.getMethod("add", String.class, String.class).invoke(context, "server", server);
        return context;
    }

    private String getServerFromContext(Object context) throws Exception {
        Class<?> contextSetClass = context.getClass();
        Collection<String> serverValues = (Collection<String>) contextSetClass.getMethod("getValues", String.class).invoke(context, "server");
        return serverValues.isEmpty() ? "global" : serverValues.iterator().next();
    }

    private Object getLuckPermsProvider() throws Exception {
        Class<?> providerClass = Class.forName("net.luckperms.api.LuckPermsProvider");
        return providerClass.getMethod("get").invoke(null);
    }

    private Object getGroup(Object lpApi, String groupName) throws Exception {
        Object groupManager = lpApi.getClass().getMethod("getGroupManager").invoke(lpApi);
        return groupManager.getClass().getMethod("getGroup", String.class).invoke(groupManager, groupName);
    }

    private Object getCachedData(Object group) throws Exception {
        return group.getClass().getMethod("getCachedData").invoke(group);
    }

    private Object getPermissionData(Object cachedData, Object context) throws Exception {
        Class<?> queryOptionsClass = Class.forName("net.luckperms.api.query.QueryOptions");
        Object queryOptions = queryOptionsClass.getMethod("contextual", Object.class).invoke(null, context);
        return cachedData.getClass().getMethod("getPermissionData", queryOptionsClass).invoke(cachedData, queryOptions);
    }

    private Object checkPermission(Object permissionData, String permission) throws Exception {
        Object tristate = permissionData.getClass().getMethod("checkPermission", String.class).invoke(permissionData, permission);
        if (tristate == null) return null;
        return tristate.getClass().getMethod("asBoolean").invoke(tristate);
    }

    private Object getUser(Object lpApi, Player player) throws Exception {
        Object playerAdapter = lpApi.getClass().getMethod("getPlayerAdapter", Class.class).invoke(lpApi, Player.class);
        return playerAdapter.getClass().getMethod("getUser", Object.class).invoke(playerAdapter, player);
    }

    private String getPermissionOriginFromUser(Object user, Object context, String permission) throws Exception {
        Class<?> queryOptionsClass = Class.forName("net.luckperms.api.query.QueryOptions");
        Object queryOptions = queryOptionsClass.getMethod("contextual", Object.class).invoke(null, context);
        
        Object nodes = user.getClass().getMethod("resolveDistinctInheritedNodes", queryOptionsClass).invoke(user, queryOptions);
        
        // Find the node with the matching permission
        Object matchingNode = null;
        for (Object node : (Iterable<?>) nodes) {
            String key = (String) node.getClass().getMethod("getKey").invoke(node);
            if (permission.equals(key)) {
                matchingNode = node;
                break;
            }
        }
        
        if (matchingNode == null) return "unknown";
        
        // Get the metadata
        Class<?> inheritanceOriginMetadataClass = Class.forName("net.luckperms.api.node.metadata.types.InheritanceOriginMetadata");
        Object metadata = matchingNode.getClass().getMethod("getMetadata", Class.class).invoke(matchingNode, inheritanceOriginMetadataClass);
        
        if (metadata == null) return "unknown";
        
        Object origin = metadata.getClass().getMethod("getOrigin").invoke(metadata);
        if (origin == null) return "unknown";
        
        return (String) origin.getClass().getMethod("getName").invoke(origin);
    }
} 