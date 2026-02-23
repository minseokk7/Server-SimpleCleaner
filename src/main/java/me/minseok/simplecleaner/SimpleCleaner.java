package me.minseok.simplecleaner;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class SimpleCleaner extends JavaPlugin {

    private FileConfiguration messagesConfig;

    @Override
    public void onEnable() {
        // Save default config
        saveDefaultConfig();
        loadLanguage();

        // Register command
        if (getCommand("cleandrop") != null) {
            getCommand("cleandrop").setExecutor(this);
        }

        // Schedule the auto-clean task (every 5 minutes = 6000 ticks)
        new BukkitRunnable() {
            int timeLeft = 300; // 5 minutes in seconds

            @Override
            public void run() {
                if (timeLeft == 30) {
                    broadcast(getMessage("warning-30s"));
                } else if (timeLeft == 5) {
                    broadcast(getMessage("warning-5s"));
                } else if (timeLeft <= 0) {
                    cleanItems();
                    timeLeft = 300; // Reset timer
                }
                timeLeft--;
            }
        }.runTaskTimer(this, 0L, 20L); // Run every second

        getLogger().info("SimpleCleaner has been enabled!");

        // Schedule TPS check task (every 20 seconds = 400 ticks)
        if (getConfig().getBoolean("tps-trigger.enabled", false)) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    double[] tps = Bukkit.getTPS();
                    if (tps != null && tps.length > 0) {
                        double currentTps = tps[0]; // 1-minute average
                        double threshold = getConfig().getDouble("tps-trigger.threshold", 18.0);

                        if (currentTps < threshold) {
                            cleanItems();
                        }
                    }
                }
            }.runTaskTimer(this, 400L, 400L);
        }
    }

    private void loadLanguage() {
        String lang = getConfig().getString("lang", "en");
        String fileName = "messages_" + lang + ".yml";
        File messageFile = new File(getDataFolder(), fileName);

        if (!messageFile.exists()) {
            saveResource(fileName, false);
        }

        messagesConfig = YamlConfiguration.loadConfiguration(messageFile);

        // Load default from JAR if file is empty or missing keys
        InputStream defConfigStream = getResource(fileName);
        if (defConfigStream != null) {
            messagesConfig.setDefaults(YamlConfiguration
                    .loadConfiguration(new InputStreamReader(defConfigStream, StandardCharsets.UTF_8)));
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("cleandrop")) {
            if (!sender.hasPermission("simplecleaner.cleandrop")) {
                sender.sendMessage(getMessage("no-permission"));
                return true;
            }
            cleanItems();
            sender.sendMessage(getMessage("manual-clean"));
            return true;
        }
        return false;
    }

    @Override
    public void onDisable() {
        Bukkit.getScheduler().cancelTasks(this);
        getLogger().info("SimpleCleaner has been disabled!");
    }

    private void cleanItems() {
        List<String> whitelist = getConfig().getStringList("whitelist");

        // 최적화 방안 2: 화이트리스트 검사 전처리 (루프 내 불필요한 연산 제거)
        java.util.Set<String> exactMatches = new java.util.HashSet<>();
        java.util.List<String> startsWithMatches = new java.util.ArrayList<>();
        java.util.List<String> endsWithMatches = new java.util.ArrayList<>();

        for (String whiteItem : whitelist) {
            if (whiteItem.endsWith("*")) {
                startsWithMatches.add(whiteItem.substring(0, whiteItem.length() - 1));
            } else if (whiteItem.startsWith("*")) {
                endsWithMatches.add(whiteItem.substring(1));
            } else {
                exactMatches.add(whiteItem);
            }
        }

        // 스케줄러를 통한 삭제 대기열 수집 (메인 스레드)
        final java.util.List<Item> targetItems = new java.util.ArrayList<>();

        for (World world : Bukkit.getWorlds()) {
            for (Item item : world.getEntitiesByClass(Item.class)) {
                String typeName = item.getItemStack().getType().name();

                boolean isWhitelisted = exactMatches.contains(typeName);

                if (!isWhitelisted) {
                    for (String prefix : startsWithMatches) {
                        if (typeName.startsWith(prefix)) {
                            isWhitelisted = true;
                            break;
                        }
                    }
                }

                if (!isWhitelisted) {
                    for (String suffix : endsWithMatches) {
                        if (typeName.endsWith(suffix)) {
                            isWhitelisted = true;
                            break;
                        }
                    }
                }

                if (!isWhitelisted) {
                    targetItems.add(item);
                }
            }
        }

        int totalCount = targetItems.size();
        if (totalCount == 0)
            return;

        // 최적화 방안 3: PaperMC 틱당 아이템 분할 삭제 로직 (Lag spike 방지)
        int itemsPerTick = getConfig().getInt("items-per-tick", 50); // 기본값 50개씩

        new BukkitRunnable() {
            int currentIndex = 0;

            @Override
            public void run() {
                int limit = Math.min(currentIndex + itemsPerTick, totalCount);

                for (int i = currentIndex; i < limit; i++) {
                    Item item = targetItems.get(i);
                    // 삭제 과정에서 객체가 유효한지 재확인
                    if (item.isValid() && !item.isDead()) {
                        item.remove();
                    }
                }

                currentIndex = limit;

                // 모든 작업이 끝났을 때
                if (currentIndex >= totalCount) {
                    String rawMsg = getRawMessage("clean-complete").replace("%count%", String.valueOf(totalCount));
                    broadcast(parseMessage(rawMsg));
                    this.cancel();
                }
            }
        }.runTaskTimer(this, 1L, 1L); // 1틱마다 실행
    }

    /**
     * Adventure Component를 서버 전체에 방송합니다.
     */
    private void broadcast(Component message) {
        Bukkit.broadcast(message);
    }

    /**
     * 메시지 키로부터 Component를 생성합니다.
     */
    private Component getMessage(String key) {
        return parseMessage(getRawMessage(key));
    }

    /**
     * 메시지 키로부터 접두사가 포함된 원본 문자열을 반환합니다.
     */
    private String getRawMessage(String key) {
        String prefix = getConfig().getString("prefix", "&8[&bSimpleCleaner&8] ");
        String msg = messagesConfig.getString(key);
        if (msg == null)
            return "";
        return prefix + msg;
    }

    /**
     * 레거시 색상 코드(&)를 Adventure Component로 변환합니다.
     */
    private Component parseMessage(String message) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(message);
    }
}
