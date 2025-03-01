package ru.meinych15.realminecooldownclx;

import com.github.sirblobman.combatlogx.api.event.PlayerTagEvent;
import com.github.sirblobman.combatlogx.api.event.PlayerUntagEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RealMineCooldownCLX extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final int MAX_CACHE_SIZE = 1000;
    private final Map<UUID, Map<Material, Long>> playerItemCooldowns = new ConcurrentHashMap<>();
    private Map<Material, Long> itemUseCooldowns;
    private String cooldownMessage;
    private String noPermissionMessage;
    private String reloadMessage;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("rmcooldownclx")).setExecutor(this);
        Objects.requireNonNull(getCommand("rmcooldownclx")).setTabCompleter(this);
    }

    @Override
    public void onDisable() {
        playerItemCooldowns.clear();
    }

    @EventHandler
    public void onPlayerCombatTag(PlayerTagEvent event) {
        Player player = event.getPlayer();
        playerItemCooldowns.put(player.getUniqueId(), new ConcurrentHashMap<>());
        cleanUpCache();
    }

    @EventHandler
    public void onPlayerUntag(PlayerUntagEvent event) {
        Player player = event.getPlayer();
        playerItemCooldowns.remove(player.getUniqueId());
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!isInCombat(player)) return;

        Material material = event.getMaterial();
        if (!itemUseCooldowns.containsKey(material)) return;

        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        long cooldown = itemUseCooldowns.get(material);
        if (hasCooldown(player, material, cooldown)) {
            long remaining = getRemainingCooldown(player, material, cooldown);
            player.sendMessage(applyColorCodes(cooldownMessage.replace("%d", String.valueOf(remaining))));
            event.setCancelled(true);
        } else {
            if (material == Material.ENDER_PEARL || material == Material.FIREWORK_ROCKET) {
                setCooldown(player, material);
            }
        }
    }

    @EventHandler
    public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        Material material = event.getItem().getType();
        if (isInCombat(player) && itemUseCooldowns.containsKey(material)) {
            setCooldown(player, material);
        }
    }

    private boolean isInCombat(Player player) {
        return playerItemCooldowns.containsKey(player.getUniqueId());
    }

    private boolean hasCooldown(Player player, Material material, long cooldown) {
        Map<Material, Long> cooldowns = playerItemCooldowns.get(player.getUniqueId());
        if (cooldowns == null) return false;

        Long lastUse = cooldowns.get(material);
        if (lastUse == null) return false;

        return (System.currentTimeMillis() - lastUse) < cooldown;
    }

    private long getRemainingCooldown(Player player, Material material, long cooldown) {
        Map<Material, Long> cooldowns = playerItemCooldowns.get(player.getUniqueId());
        if (cooldowns == null) return 0;

        Long lastUse = cooldowns.get(material);
        if (lastUse == null) return 0;

        long elapsed = System.currentTimeMillis() - lastUse;
        long remaining = cooldown - elapsed;
        return Math.max(0, TimeUnit.MILLISECONDS.toSeconds(remaining));
    }

    private void setCooldown(Player player, Material material) {
        Map<Material, Long> cooldowns = playerItemCooldowns.get(player.getUniqueId());
        if (cooldowns != null) {
            cooldowns.put(material, System.currentTimeMillis());
        }
    }

    private void loadConfig() {
        FileConfiguration config = getConfig();
        itemUseCooldowns = new ConcurrentHashMap<>();

        if (config.contains("item-use-cooldowns")) {
            ConfigurationSection section = config.getConfigurationSection("item-use-cooldowns");
            for (String key : section.getKeys(false)) {
                try {
                    Material material = Material.valueOf(key.toUpperCase());
                    long cooldown = section.getLong(key) * 1000;
                    itemUseCooldowns.put(material, cooldown);
                } catch (IllegalArgumentException ex) {
                    getLogger().warning("Неверный материал '" + key + "' в конфиге");
                }
            }
        }

        cooldownMessage = config.getString("messages.cooldown-message");
        noPermissionMessage = config.getString("messages.no-permission");
        reloadMessage = config.getString("messages.reloaded");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length != 1 || !args[0].equalsIgnoreCase("reload")) return false;

        if (!sender.hasPermission("rmcooldownclx.reload")) {
            sender.sendMessage(applyColorCodes(noPermissionMessage));
            return true;
        }

        reloadConfig();
        loadConfig();
        sender.sendMessage(applyColorCodes(reloadMessage));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            return Collections.singletonList("reload");
        }
        return Collections.emptyList();
    }

    private String applyColorCodes(String message) {
        Pattern hexPattern = Pattern.compile("&#([A-Fa-f0-9]{6})");
        Matcher matcher = hexPattern.matcher(message);
        StringBuffer buffer = new StringBuffer();

        while (matcher.find()) {
            String hex = matcher.group(1);
            String replacement = "§x§" + hex.charAt(0) + "§" + hex.charAt(1) + "§"
                    + hex.charAt(2) + "§" + hex.charAt(3) + "§" + hex.charAt(4) + "§" + hex.charAt(5);
            matcher.appendReplacement(buffer, replacement);
        }
        matcher.appendTail(buffer);
        message = buffer.toString();

        message = message.replace('&', '§');

        return message;
    }

    private void cleanUpCache() {
        Iterator<UUID> iterator = playerItemCooldowns.keySet().iterator();
        while (iterator.hasNext()) {
            UUID uuid = iterator.next();
            if (playerItemCooldowns.size() <= MAX_CACHE_SIZE) break;
            if (!Bukkit.getOfflinePlayer(uuid).isOnline()) {
                iterator.remove();
            }
        }
    }
}