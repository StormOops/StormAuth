package dev.storm.stormauth.storage;

import dev.storm.stormauth.StormAuthPlugin;
import dev.storm.stormauth.auth.PlayerAccount;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerDataStore {

    private final StormAuthPlugin plugin;
    private final Map<UUID, PlayerAccount> accounts = new ConcurrentHashMap<>();
    private Storage backend;

    public PlayerDataStore(StormAuthPlugin plugin) {
        this.plugin = plugin;
    }

    public void init() {
        String type = plugin.getConfig().getString("storage", "yaml").toLowerCase();
        backend = switch (type) {
            case "sqlite" -> trySql(SqlStorage.sqlite(plugin));
            case "mysql" -> trySql(SqlStorage.mysql(plugin.getConfig()));
            default -> {
                Storage yaml = new YamlStorage(plugin);
                accounts.putAll(safeLoad(yaml));
                yield yaml;
            }
        };
    }

    private Storage trySql(SqlStorage sql) {
        try {
            sql.init();
            accounts.putAll(sql.loadAll());
            migrateYamlIfNeeded(sql);
            return sql;
        } catch (Exception e) {
            plugin.getLogger().severe("база " + sql.name() + " недоступна (" + e.getMessage() + "), работаем на players.yml");
            Storage yaml = new YamlStorage(plugin);
            accounts.putAll(safeLoad(yaml));
            return yaml;
        }
    }

    private void migrateYamlIfNeeded(SqlStorage sql) {
        try {
            Map<UUID, PlayerAccount> legacy = new YamlStorage(plugin).loadAll();
            int moved = 0;
            for (PlayerAccount account : legacy.values()) {
                if (!accounts.containsKey(account.getUuid())) {
                    accounts.put(account.getUuid(), account);
                    sql.save(account);
                    moved++;
                }
            }
            if (moved > 0) {
                plugin.getLogger().info("перенесено аккаунтов из players.yml в " + sql.name() + ": " + moved
                        + " (players.yml оставлен как резервная копия)");
            }
        } catch (Exception e) {
            plugin.getLogger().severe("миграция players.yml -> " + sql.name() + " не удалась: " + e.getMessage());
        }
    }

    private Map<UUID, PlayerAccount> safeLoad(Storage storage) {
        try {
            return storage.loadAll();
        } catch (Exception e) {
            plugin.getLogger().severe("не смогли прочитать аккаунты из " + storage.name() + ": " + e.getMessage());
            return Map.of();
        }
    }

    // чтение после старта - только из памяти, диск и базу на игровых потоках не трогаем
    public PlayerAccount find(UUID uuid, String name) {
        PlayerAccount account = accounts.get(uuid);
        if (account != null) {
            return account;
        }
        PlayerAccount byName = findByName(name);
        if (byName != null && !byName.getUuid().equals(uuid)) {
            reseat(byName, uuid);
        }
        return byName;
    }

    // у записей из authme uuid посчитан от ника в нижнем регистре и не совпадает с реальным -
    // пересаживаем аккаунт на настоящий uuid при первом входе, иначе он вечно "чужой"
    private void reseat(PlayerAccount account, UUID realUuid) {
        UUID importedUuid = account.getUuid();
        PlayerAccount moved = new PlayerAccount(realUuid, account.getName(), account.getPasswordHash());
        moved.setTotpSecret(account.getTotpSecret());
        moved.setTotpEnabled(account.isTotpEnabled());
        moved.setTelegramId(account.getTelegramId());
        moved.setVkId(account.getVkId());
        moved.setLastIp(account.getLastIp());
        moved.setLastLogin(account.getLastLogin());
        moved.setRegistered(account.getRegistered());
        // backup-коды тоже переезжают: на 1.7.0 игрок мог включить 2fa до пересадки,
        // потеря кодов = локаут при утере authenticator
        moved.setBackupCodeHashes(account.getBackupCodeHashes());
        accounts.put(realUuid, moved);
        remove(importedUuid);
        saveAsync(moved);
        plugin.getSecurityLog().log("аккаунт " + moved.getName() + " пересажен с uuid из authme на реальный");
    }

    public PlayerAccount findByName(String name) {
        for (PlayerAccount account : accounts.values()) {
            if (account.getName().equalsIgnoreCase(name)) {
                return account;
            }
        }
        return null;
    }

    public void put(PlayerAccount account) {
        accounts.put(account.getUuid(), account);
    }

    public void remove(UUID uuid) {
        accounts.remove(uuid);
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            try {
                backend.delete(uuid);
            } catch (Exception e) {
                plugin.getLogger().severe("не смогли удалить аккаунт из " + backend.name() + ": " + e.getMessage());
            }
        });
    }

    public void saveAsync(PlayerAccount account) {
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            try {
                backend.save(account);
            } catch (Exception e) {
                plugin.getLogger().severe("не смогли записать аккаунт в " + backend.name() + ": " + e.getMessage());
            }
        });
    }

    public int countByIp(String ip) {
        int count = 0;
        for (PlayerAccount account : accounts.values()) {
            if (account.getLastIp().equals(ip)) {
                count++;
            }
        }
        return count;
    }

    public Collection<PlayerAccount> all() {
        return accounts.values();
    }

    public String backendName() {
        return backend.name();
    }

    public void close() {
        backend.close();
    }
}
