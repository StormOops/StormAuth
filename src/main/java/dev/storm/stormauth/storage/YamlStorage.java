package dev.storm.stormauth.storage;

import dev.storm.stormauth.auth.PlayerAccount;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class YamlStorage implements Storage {

    private final File file;
    private final Map<UUID, PlayerAccount> pending = new HashMap<>();

    public YamlStorage(JavaPlugin plugin) {
        this.file = new File(plugin.getDataFolder(), "players.yml");
    }

    @Override
    public String name() {
        return "yaml";
    }

    @Override
    public Map<UUID, PlayerAccount> loadAll() {
        pending.clear();
        if (!file.exists()) {
            return Map.of();
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("players");
        if (root == null) {
            return Map.of();
        }
        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            try {
                PlayerAccount account = new PlayerAccount(
                        UUID.fromString(key),
                        section.getString("name", "?"),
                        section.getString("password", ""));
                account.setTotpSecret(section.getString("totp-secret"));
                account.setTotpEnabled(section.getBoolean("totp-enabled"));
                account.setTelegramId(section.getLong("telegram", -1));
                account.setVkId(section.getLong("vk", -1));
                account.setLastIp(section.getString("last-ip", ""));
                account.setLastLogin(section.getLong("last-login"));
                account.setRegistered(section.getLong("registered", System.currentTimeMillis()));
                pending.put(account.getUuid(), account);
            } catch (IllegalArgumentException badKey) {
                // битый uuid в файле - пропускаем запись, а не падаем
            }
        }
        return new HashMap<>(pending);
    }

    @Override
    public synchronized void save(PlayerAccount account) throws IOException {
        pending.put(account.getUuid(), account);
        writeAll();
    }

    @Override
    public synchronized void delete(UUID uuid) throws IOException {
        pending.remove(uuid);
        writeAll();
    }

    // запись атомарная: сначала tmp, потом замена; крах посреди записи не уносит базу
    private void writeAll() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        for (PlayerAccount account : pending.values()) {
            String path = "players." + account.getUuid();
            yaml.set(path + ".name", account.getName());
            yaml.set(path + ".password", account.getPasswordHash());
            yaml.set(path + ".totp-secret", account.getTotpSecret());
            yaml.set(path + ".totp-enabled", account.isTotpEnabled());
            yaml.set(path + ".telegram", account.getTelegramId());
            yaml.set(path + ".vk", account.getVkId());
            yaml.set(path + ".last-ip", account.getLastIp());
            yaml.set(path + ".last-login", account.getLastLogin());
            yaml.set(path + ".registered", account.getRegistered());
        }
        file.getParentFile().mkdirs();
        File tmp = new File(file.getParentFile(), "players.yml.tmp");
        yaml.save(tmp);
        if (file.exists()) {
            Files.copy(file.toPath(), new File(file.getParentFile(), "players.yml.bak").toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public void close() {
    }
}
