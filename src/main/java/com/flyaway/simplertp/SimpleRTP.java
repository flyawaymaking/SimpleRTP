package com.flyaway.simplertp;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.World.Environment;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class SimpleRTP extends JavaPlugin implements Listener {
    private boolean teleportSoundEnabled;
    private Sound teleportSoundType;
    private float teleportSoundVolume;
    private float teleportSoundPitch;
    private final Map<UUID, Long> playerCooldowns = new HashMap<>();
    private final Map<UUID, Integer> pendingTeleportations = new HashMap<>();
    private long cooldownTime;
    private int waitTime;
    private World targetWorld;
    private int teleportRadius;
    private String messagePrefix;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    @Override
    public void onEnable() {
        getLogger().info("Плагин был включен!");
        loadConfigValues();
        getServer().getPluginManager().registerEvents(this, this);
        loadSoundValued();
    }

    @Override
    public void onDisable() {
        for (Integer taskId : pendingTeleportations.values()) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
        pendingTeleportations.clear();
        getLogger().info("Плагин был выключен!");
    }

    private void loadConfigValues() {
        saveDefaultConfig();
        FileConfiguration config = getConfig();
        cooldownTime = config.getLong("cooldown-time", 15L);
        waitTime = config.getInt("wait-time", 5);
        String worldName = config.getString("world", "world");
        teleportRadius = config.getInt("radius", 6000);
        messagePrefix = config.getString("message-prefix", "[RTP]");
        targetWorld = Bukkit.getWorld(worldName);
        if (targetWorld == null) {
            getLogger().severe("Мир '" + worldName + "' не существует. Пожалуйста, проверьте и обновите ваш config.yml с правильным/существующим миром.");
            getServer().getPluginManager().disablePlugin(this);
        }
    }


    private void loadSoundValued() {
        teleportSoundEnabled = getConfig().getBoolean("teleport-sound.enabled", true);
        teleportSoundVolume = (float) getConfig().getDouble("teleport-sound.volume", 1.0);
        teleportSoundPitch = (float) getConfig().getDouble("teleport-sound.pitch", 1.0);
        teleportSoundType = Sound.ENTITY_ENDERMAN_TELEPORT;

        String soundType = getConfig().getString("teleport-sound.sound-type", "entity.enderman.teleport");
        NamespacedKey soundKey = NamespacedKey.fromString(soundType);
        if (soundKey != null) {
            Sound sound = Registry.SOUNDS.get(soundKey);
            if (sound != null) {
                teleportSoundType = sound;
            } else {
                getLogger().warning("Звук не найден в реестре: " + soundType);
            }
        } else {
            getLogger().warning("Неверный sound key: " + soundType);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("rtpreload")) {
            if (sender.hasPermission("rtp.reload")) {
                reloadConfig();
                loadConfigValues();
                sendMessage(sender, "config-reloaded");
            } else {
                sendMessage(sender, "no-permissions");
            }
            return true;
        } else if (command.getName().equalsIgnoreCase("rtp")) {
            if (sender instanceof Player player) {
                UUID playerId = player.getUniqueId();

                if (pendingTeleportations.containsKey(playerId)) {
                    sendMessage(player, "tp-already-active");
                    return true;
                }

                if (!player.hasPermission("rtp.bypass") && playerCooldowns.containsKey(playerId)) {
                    long lastUsed = playerCooldowns.get(playerId);
                    long timeLeft = lastUsed + cooldownTime * 1000L - System.currentTimeMillis();
                    if (timeLeft > 0L) {
                        long secondsLeft = timeLeft / 1000L;
                        sendMessage(player, "cooldown-await", "seconds", String.valueOf(secondsLeft));
                        return true;
                    }
                }

                startTeleportationWithDelay(player);
                return true;
            } else {
                sendMessage(sender, "only-player");
                return false;
            }
        } else {
            return false;
        }
    }

    private void startTeleportationWithDelay(Player player) {
        sendMessage(player, "wait", "seconds", String.valueOf(waitTime));
        UUID playerId = player.getUniqueId();

        final int[] countdown = {waitTime};

        int taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            if (countdown[0] > 0) {
                sendTitle(player, "wait-title", "wait-subtitle", 0, 20, 0, "seconds", String.valueOf(countdown[0]));
                countdown[0]--;
            } else {
                // Время вышло - выполняем телепортацию
                Bukkit.getScheduler().cancelTask(pendingTeleportations.get(playerId));
                pendingTeleportations.remove(playerId);

                if (teleportSoundEnabled) {
                    playTeleportSound(player.getLocation());
                }

                teleportPlayerToRandomLocation(player);

                if (teleportSoundEnabled) {
                    playTeleportSound(player.getLocation());
                }

                sendMessage(player, "success");
                showTeleportationTitle(player);

                playerCooldowns.put(playerId, System.currentTimeMillis());
            }
        }, 0L, 20L);

        pendingTeleportations.put(playerId, taskId);
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        if (pendingTeleportations.containsKey(playerId)) {
            Location from = event.getFrom();
            Location to = event.getTo();

            if (from.getBlockX() != to.getBlockX() || from.getBlockY() != to.getBlockY() || from.getBlockZ() != to.getBlockZ()) {
                int taskId = pendingTeleportations.get(playerId);
                Bukkit.getScheduler().cancelTask(taskId);
                pendingTeleportations.remove(playerId);

                sendTitle(player, "", "", 0, 1, 0);
                sendMessage(player, "canceled");
            }
        }
    }

    private void playTeleportSound(Location location) {
        location.getWorld().playSound(location, teleportSoundType, SoundCategory.PLAYERS, teleportSoundVolume, teleportSoundPitch);
    }

    private void teleportPlayerToRandomLocation(Player player) {
        Random random = new Random();
        Location safeLocation;

        do {
            int x = random.nextInt(2 * teleportRadius) - teleportRadius;
            int z = random.nextInt(2 * teleportRadius) - teleportRadius;

            if (targetWorld.getEnvironment() == Environment.NETHER) {
                safeLocation = findSafeNetherLocation(x, z);
            } else {
                int y = targetWorld.getHighestBlockYAt(x, z);
                safeLocation = new Location(targetWorld, x + 0.5, y, z + 0.5);
            }
        } while (!isLocationSafe(safeLocation));

        targetWorld.getChunkAt(safeLocation).load();
        player.teleport(safeLocation);
    }

    private Location findSafeNetherLocation(int x, int z) {
        for (int y = targetWorld.getMaxHeight() - 1; y > 0; y--) {
            Location location = new Location(targetWorld, x + 0.5, y, z + 0.5);
            if (isLocationSafe(location) && y < 127) {
                return location;
            }
        }
        return new Location(targetWorld, x + 0.5, 64.0, z + 0.5);
    }

    private boolean isLocationSafe(Location location) {
        Material blockType = location.getBlock().getType();
        Material belowBlockType = location.clone().subtract(0.0, 1.0, 0.0).getBlock().getType();

        return blockType.isSolid() &&
                blockType != Material.WATER &&
                blockType != Material.LAVA &&
                belowBlockType != Material.WATER &&
                belowBlockType != Material.LAVA;
    }

    private void showTeleportationTitle(Player player) {
        String x = String.valueOf(player.getLocation().getBlockX());
        String y = String.valueOf(player.getLocation().getBlockY());
        String z = String.valueOf(player.getLocation().getBlockZ());
        sendTitle(player, "teleported-title", "teleported-subtitle", 10, 70, 20, "x", x, "y", y, "z", z);
    }

    private void sendMessage(CommandSender sender, String key, String... placeholders) {
        String message = getMessage(key, placeholders);
        if (message.isEmpty()) return;
        sender.sendMessage(miniMessage.deserialize(messagePrefix + " " + message));
    }

    private void sendTitle(Player player, String titleKey, String subTitleKey, int fadeInTicks, int stayTicks, int fadeOutTicks, String... placeholders) {
        String title = getMessage("titles." + titleKey, placeholders);
        String subTitle = getMessage("titles." + subTitleKey, placeholders);
        player.showTitle(Title.title(miniMessage.deserialize(title), miniMessage.deserialize(subTitle), fadeInTicks, stayTicks, fadeOutTicks));
    }

    public String getMessage(String key, String... placeholders) {
        if (key.isEmpty()) return "";
        String message = getConfig().getString("messages." + key, "<red>message." + key + " not-found");

        if (message.isEmpty()) return "";
        message = message.trim();

        if (placeholders != null && placeholders.length > 0) {
            if (placeholders.length % 2 != 0) {
                throw new IllegalArgumentException("Количество плейсхолдеров должно быть четным (ключ-значение)");
            }

            for (int i = 0; i < placeholders.length; i += 2) {
                String placeholder = placeholders[i];
                String value = placeholders[i + 1];
                message = message.replace("{" + placeholder + "}", value);
            }
        }
        return message;
    }
}
