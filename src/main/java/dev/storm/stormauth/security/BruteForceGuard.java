package dev.storm.stormauth.security;

import dev.storm.stormauth.StormAuthPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class BruteForceGuard {

    private final StormAuthPlugin plugin;
    private final File file;
    private final Map<String, Long> bans = new ConcurrentHashMap<>();
    private final Map<String, Integer> kicks = new ConcurrentHashMap<>();

    public BruteForceGuard(StormAuthPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "bans.yml");
    }

    public void load() {
        bans.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        long now = System.currentTimeMillis();
        for (Map<?, ?> entry : yaml.getMapList("bans")) {
            Object ip = entry.get("ip");
            Object until = entry.get("until");
            if (ip instanceof String address && until instanceof Number timestamp && timestamp.longValue() > now) {
                bans.put(address, timestamp.longValue());
            }
        }
    }

    public boolean isBanned(String ip) {
        if (isWhitelisted(ip)) {
            return false;
        }
        Long until = bans.get(ip);
        if (until == null) {
            return false;
        }
        if (until <= System.currentTimeMillis()) {
            bans.remove(ip);
            return false;
        }
        return true;
    }

    public long minutesLeft(String ip) {
        Long until = bans.get(ip);
        if (until == null) {
            return 0;
        }
        return Math.max(1, (until - System.currentTimeMillis()) / 60_000);
    }

    public void registerKick(String ip) {
        if (!plugin.getConfig().getBoolean("bruteforce.enabled", true)) {
            return;
        }
        // за одним nat может сидеть весь дом или общага - доверенные адреса из конфига не баним
        if (isWhitelisted(ip)) {
            return;
        }
        int count = kicks.merge(ip, 1, Integer::sum);
        int limit = plugin.getConfig().getInt("bruteforce.kicks-before-ban", 2);
        if (count < limit) {
            return;
        }
        kicks.remove(ip);
        long minutes = plugin.getConfig().getLong("bruteforce.ban-minutes", 15);
        bans.put(ip, System.currentTimeMillis() + minutes * 60_000);
        save();
        plugin.getLogger().warning("ip " + ip + " забанен на " + minutes + " мин за перебор паролей");
    }

    public void reset(String ip) {
        kicks.remove(ip);
    }

    private boolean isWhitelisted(String ip) {
        return plugin.getConfig().getStringList("bruteforce.whitelist").contains(ip);
    }

    private void save() {
        List<Map<String, Object>> list = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> entry : bans.entrySet()) {
            if (entry.getValue() > now) {
                Map<String, Object> row = new HashMap<>();
                row.put("ip", entry.getKey());
                row.put("until", entry.getValue());
                list.add(row);
            }
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("bans", list);
        try {
            file.getParentFile().mkdirs();
            yaml.save(file);
        } catch (Exception e) {
            plugin.getLogger().warning("bans.yml не записался: " + e.getMessage());
        }
    }
}
