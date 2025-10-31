package com.flyaway.simplertp;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
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
    private Map<UUID, Long> playerCooldowns = new HashMap<>();
    private Map<UUID, Integer> pendingTeleportations = new HashMap<>();
    private long cooldownTime;
    private World targetWorld;
    private int teleportRadius;
    private String messagePrefix;

    @Override
    public void onEnable() {
        this.getLogger().info("Плагин был включен!");
        this.loadConfigValues();

        getServer().getPluginManager().registerEvents(this, this);

        ConfigurationSection soundConfig = this.getConfig().getConfigurationSection("teleport-sound");
        if (soundConfig != null) {
            this.teleportSoundEnabled = soundConfig.getBoolean("enabled", true);
            this.teleportSoundVolume = (float)soundConfig.getDouble("volume", 1.0);
            this.teleportSoundPitch = (float)soundConfig.getDouble("pitch", 1.0);

            try {
                this.teleportSoundType = Sound.valueOf(soundConfig.getString("sound-type", "ENTITY_ENDERMAN_TELEPORT"));
            } catch (IllegalArgumentException e) {
                this.getLogger().warning("Неверный тип звука указан в config.yml. Используется звук по умолчанию.");
                this.teleportSoundType = Sound.ENTITY_ENDERMAN_TELEPORT;
            }
        } else {
            this.getLogger().warning("Секция teleport-sound не найдена в config.yml. Используются значения по умолчанию.");
            this.teleportSoundEnabled = true;
            this.teleportSoundType = Sound.ENTITY_ENDERMAN_TELEPORT;
            this.teleportSoundVolume = 1.0F;
            this.teleportSoundPitch = 1.0F;
        }
    }

    @Override
    public void onDisable() {
        for (Integer taskId : pendingTeleportations.values()) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
        pendingTeleportations.clear();
        this.getLogger().info("Плагин был выключен!");
    }

    private void loadConfigValues() {
        this.saveDefaultConfig();
        FileConfiguration config = this.getConfig();
        this.cooldownTime = config.getLong("cooldown-time", 15L);
        String worldName = config.getString("world", "world");
        this.teleportRadius = config.getInt("radius", 6000);
        this.messagePrefix = config.getString("message-prefix", "[RTP]");
        this.targetWorld = Bukkit.getWorld(worldName);
        if (this.targetWorld == null) {
            this.getLogger().severe("Мир '" + worldName + "' не существует. Пожалуйста, проверьте и обновите ваш config.yml с правильным/существующим миром.");
            this.getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("rtpreload")) {
            if (sender.hasPermission("rtp.reload")) {
                this.reloadConfig();
                this.loadConfigValues();
                this.sendMessage(sender, "Конфигурация перезагружена.", "§a");
            } else {
                this.sendMessage(sender, "У вас нет прав для выполнения этой команды.", "§c");
            }
            return true;
        } else if (command.getName().equalsIgnoreCase("rtp")) {
            if (sender instanceof Player) {
                Player player = (Player) sender;
                UUID playerId = player.getUniqueId();

                if (pendingTeleportations.containsKey(playerId)) {
                    this.sendMessage(player, "У вас уже есть ожидающая телепортация!", "§c");
                    return true;
                }

                if (this.playerCooldowns.containsKey(playerId)) {
                    long lastUsed = this.playerCooldowns.get(playerId);
                    long timeLeft = lastUsed + this.cooldownTime * 1000L - System.currentTimeMillis();
                    if (timeLeft > 0L) {
                        long secondsLeft = timeLeft / 1000L;
                        this.sendMessage(player, "Вы должны подождать " + secondsLeft + " секунд перед использованием этой команды снова.", "§c");
                        return true;
                    }
                }

                this.startTeleportationWithDelay(player);
                return true;
            } else {
                this.sendMessage(sender, "Только игроки могут использовать эту команду.", "§c");
                return false;
            }
        } else {
            return false;
        }
    }

    private void startTeleportationWithDelay(Player player) {
        this.sendMessage(player, "Телепортация через 5 секунд... Не двигайтесь!", "§e");
        UUID playerId = player.getUniqueId();

        final int[] countdown = {5}; // Обратный отсчет от 5 секунд

        // Создаем одну задачу для всего процесса
        int taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            if (countdown[0] > 0) {
                // Показываем обратный отсчет
                player.sendTitle(
                    "§eТелепортация через",
                    "§6" + countdown[0] + " §eсекунд",
                    0, 20, 0
                );
                countdown[0]--;
            } else {
                // Время вышло - выполняем телепортацию
                Bukkit.getScheduler().cancelTask(pendingTeleportations.get(playerId));
                pendingTeleportations.remove(playerId);

                if (this.teleportSoundEnabled) {
                    this.playTeleportSound(player.getLocation());
                }

                this.teleportPlayerToRandomLocation(player);

                if (this.teleportSoundEnabled) {
                    this.playTeleportSound(player.getLocation());
                }

                this.sendTeleportSuccessMessage(player);
                this.showTeleportationTitle(player);

                playerCooldowns.put(playerId, System.currentTimeMillis());
            }
        }, 0L, 20L); // Запускаем сразу и повторяем каждую секунду

        pendingTeleportations.put(playerId, taskId);
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        if (pendingTeleportations.containsKey(playerId)) {
            Location from = event.getFrom();
            Location to = event.getTo();

            if (to != null && (from.getBlockX() != to.getBlockX() || from.getBlockY() != to.getBlockY() || from.getBlockZ() != to.getBlockZ())) {
                int taskId = pendingTeleportations.get(playerId);
                Bukkit.getScheduler().cancelTask(taskId);
                pendingTeleportations.remove(playerId);

                // Очищаем заголовок
                player.sendTitle("", "", 0, 1, 0);

                this.sendMessage(player, "Телепортация отменена, потому что вы двигались!", "§c");
            }
        }
    }

    private void playTeleportSound(Location location) {
        location.getWorld().playSound(location, this.teleportSoundType, SoundCategory.PLAYERS, this.teleportSoundVolume, this.teleportSoundPitch);
    }

    private void teleportPlayerToRandomLocation(Player player) {
        Random random = new Random();
        Location safeLocation;

        do {
            int x = random.nextInt(2 * this.teleportRadius) - this.teleportRadius;
            int z = random.nextInt(2 * this.teleportRadius) - this.teleportRadius;

            if (this.targetWorld.getEnvironment() == Environment.NETHER) {
                safeLocation = this.findSafeNetherLocation(x, z);
            } else {
                int y = this.targetWorld.getHighestBlockYAt(x, z);
                safeLocation = new Location(this.targetWorld, x + 0.5, y, z + 0.5);
            }
        } while (!this.isLocationSafe(safeLocation));

        this.targetWorld.getChunkAt(safeLocation).load();
        player.teleport(safeLocation);
    }

    private Location findSafeNetherLocation(int x, int z) {
        for (int y = this.targetWorld.getMaxHeight() - 1; y > 0; y--) {
            Location location = new Location(this.targetWorld, x + 0.5, y, z + 0.5);
            if (this.isLocationSafe(location) && y < 127) {
                return location;
            }
        }
        return new Location(this.targetWorld, x + 0.5, 64.0, z + 0.5);
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

    private void sendTeleportSuccessMessage(Player player) {
        this.sendMessage(player, "Вы были телепортированы в случайное место!", "§a");
    }

    private void showTeleportationTitle(Player player) {
        int x = player.getLocation().getBlockX();
        int z = player.getLocation().getBlockZ();
        String title = "§aТелепортирован";
        String subtitle = "§7Координаты: X=" + x + ", Z=" + z;
        player.sendTitle(title, subtitle, 10, 70, 20);
    }

    private void sendMessage(CommandSender sender, String message, String color) {
        String formattedMessage = color + this.messagePrefix + " " + message;
        sender.sendMessage(formattedMessage);
    }
}
