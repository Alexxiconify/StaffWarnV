package me.arcator.staffWarnV;

import com.electronwill.nightconfig.core.file.FileConfig;
import org.bukkit.entity.Player;
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
        // Skip if player is not on a server (for Paper, this is always true if player is online)
        String server = player.getWorld().getName().toLowerCase();

        // Skip excluded servers
        if (excludedServers.contains(server)) {
            if (verboseLogging)
                logger.fine("Skipping alert for " + player.getName() + " - server " + server + " is excluded");
            return null;
        }

        // Create server context for permission checks
        MutableContextSet context = MutableContextSet.create();
        context.add("server", server);

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
    }

    /**
     * Checks if any of the default groups have the specified permission.
     * Uses caching to improve performance for repeated checks.
     *
     * @param context The context to check permissions in (e.g., server context)
     * @param permission The permission to check
     * @return true if any default group has the permission, false otherwise
     */
    public boolean defaultGroupsHavePermission(MutableContextSet context, String permission) {
        // Create cache key using server context and permission
        Collection<String> serverValues = context.getValues("server");
        String serverName = serverValues.isEmpty() ? "global" : serverValues.iterator().next();
        String cacheKey = serverName + ":" + permission;

        // Return cached result if available
        Boolean cached = permissionCheckCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        var lpApi = LuckPermsProvider.get();
        boolean result = defaultGroups.stream().anyMatch(groupName -> {
            var group = lpApi.getGroupManager().getGroup(groupName);
            if (group == null) return false;
            
            var cachedData = group.getCachedData();
            if (cachedData == null) return false;
            
            var permissionData = cachedData.getPermissionData(QueryOptions.contextual(context));
            if (permissionData == null) return false;
            
            var tristate = permissionData.checkPermission(permission);
            return tristate != null && tristate.asBoolean();
        });

        // Cache the result
        permissionCheckCache.put(cacheKey, result);

        // Limit cache size to avoid memory issues
        if (permissionCheckCache.size() > 1000) {
            permissionCheckCache.keySet().stream().limit(100).forEach(permissionCheckCache::remove);
        }

        return result;
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
    public String getPermissionOrigin(Player player, MutableContextSet context, String permission) {
        // Create cache key
        String uuid = player.getUniqueId().toString();
        Collection<String> serverValues = context.getValues("server");
        String serverName = serverValues.isEmpty() ? "global" : serverValues.iterator().next();
        String cacheKey = uuid + ":" + serverName + ":" + permission;

        // Check cache first
        String cached = permissionOriginCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        try {
            var lpApi = LuckPermsProvider.get();
            var user = lpApi.getPlayerAdapter(Player.class).getUser(player);

            String origin = user.resolveDistinctInheritedNodes(QueryOptions.contextual(context))
                .stream()
                .filter(node -> node.getKey().equals(permission))
                .findFirst()
                .flatMap(node -> node.getMetadata(InheritanceOriginMetadata.KEY))
                .map(metadata -> metadata.getOrigin())
                .map(originObj -> originObj.getName())
                .orElse("unknown");

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
     * @return The loaded FileConfig object
     */
    private FileConfig getConfig(String file) {
        Path configFile = dataDirectory.resolve(file);

        try {
            // Create data directory if it doesn't exist
            if (Files.notExists(dataDirectory)) {
                Path created = Files.createDirectories(dataDirectory);
                logger.info("Created config folder: " + created);
            }

            // Log if we're using the default config file
            if (Files.notExists(configFile)) {
                logger.info("Creating default config at: " + configFile.toAbsolutePath());
            }

            // Load the config with default values if the file doesn't exist
            FileConfig config = FileConfig.builder(configFile)
                .defaultData(getClass().getResource("/" + file))
                .autosave()
                .build();
            config.load();
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
            try (FileConfig commands = getConfig("commandPermissions.toml")) {
                // Create a map optimized for faster lookups with expected size
                int commandCount = commands.entrySet().size();
                Map<String, String> commandMap = new HashMap<>(commandCount + (commandCount / 3));

                // Populate the map with command -> permission pairs
                for (var entry : commands.entrySet()) {
                    commandMap.put(entry.getKey(), (String) entry.getValue());
                }

                this.commandPermissions = commandMap;
                logger.fine("Command permission map created with " + commandMap.size() + " entries");
            }

            // Load main configuration
            try (FileConfig conf = getConfig("config.toml")) {
                this.defaultGroups = conf.get("defaultGroups");
                this.excludedServers = conf.get("excludedServers");
                this.verboseLogging = conf.getOrElse("debug.verbose", false);
            }

            // Load message templates
            try (FileConfig messages = getConfig("messages.toml")) {
                this.alertTemplate = messages.get("alert");
            }

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
} 