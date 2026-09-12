package dev.storm.stormauth.importer;

import dev.storm.stormauth.StormAuthPlugin;
import dev.storm.stormauth.auth.PlayerAccount;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

public final class AuthMeImporter {

    public record Result(int imported, int skipped, int existing) {
    }

    private final StormAuthPlugin plugin;

    public AuthMeImporter(StormAuthPlugin plugin) {
        this.plugin = plugin;
    }

    public Result importFrom(File file) throws Exception {
        if (!file.isFile()) {
            throw new IllegalArgumentException("файл не найден: " + file.getPath());
        }
        int imported = 0;
        int skipped = 0;
        int existing = 0;
        try (Connection con = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
             PreparedStatement ps = con.prepareStatement("SELECT username, password FROM authme");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String name = rs.getString("username");
                String hash = rs.getString("password");
                if (hash != null && (hash.startsWith("$2") || hash.startsWith("$argon2"))) {
                    // TODO: bcrypt/argon2 пропускаем - без исходного пароля хэш не пересчитать;
                    // поддержать можно проверкой через их алгоритм при первом входе
                    skipped++;
                    continue;
                }
                UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
                if (plugin.getDataStore().find(uuid, name) != null) {
                    existing++;
                    continue;
                }
                PlayerAccount account = new PlayerAccount(uuid, name, hash == null ? "" : hash);
                plugin.getDataStore().put(account);
                plugin.getDataStore().saveAsync(account);
                imported++;
            }
        }
        return new Result(imported, skipped, existing);
    }
}
