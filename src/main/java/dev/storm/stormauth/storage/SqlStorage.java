package dev.storm.stormauth.storage;

import dev.storm.stormauth.auth.PlayerAccount;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class SqlStorage implements Storage {

    public enum Dialect {
        SQLITE,
        MYSQL
    }

    private final Dialect dialect;
    private final String url;
    private final String user;
    private final String password;
    private final Object writeLock = new Object();

    private SqlStorage(Dialect dialect, String url, String user, String password) {
        this.dialect = dialect;
        this.url = url;
        this.user = user;
        this.password = password;
    }

    public static SqlStorage sqlite(JavaPlugin plugin) {
        String path = new File(plugin.getDataFolder(), "stormauth.db").getAbsolutePath();
        return new SqlStorage(Dialect.SQLITE, "jdbc:sqlite:" + path, null, null);
    }

    public static SqlStorage mysql(FileConfiguration config) {
        String url = "jdbc:mysql://" + config.getString("mysql.host", "127.0.0.1")
                + ":" + config.getInt("mysql.port", 3306)
                + "/" + config.getString("mysql.database", "stormauth")
                + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8";
        return new SqlStorage(Dialect.MYSQL, url,
                config.getString("mysql.user", "root"),
                config.getString("mysql.password", ""));
    }

    @Override
    public String name() {
        return dialect == Dialect.SQLITE ? "sqlite" : "mysql";
    }

    public void init() throws Exception {
        try (Connection con = connect(); Statement st = con.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS accounts ("
                    + "uuid VARCHAR(36) PRIMARY KEY,"
                    + "name VARCHAR(32) NOT NULL,"
                    + "password TEXT NOT NULL,"
                    + "totp_secret TEXT,"
                    + "totp_enabled INTEGER NOT NULL DEFAULT 0,"
                    + "telegram_id BIGINT NOT NULL DEFAULT -1,"
                    + "vk_id BIGINT NOT NULL DEFAULT -1,"
                    + "last_ip VARCHAR(45) NOT NULL DEFAULT '',"
                    + "last_login BIGINT NOT NULL DEFAULT 0,"
                    + "registered BIGINT NOT NULL DEFAULT 0)");
        }
    }

    @Override
    public Map<UUID, PlayerAccount> loadAll() throws Exception {
        Map<UUID, PlayerAccount> out = new HashMap<>();
        try (Connection con = connect();
             PreparedStatement ps = con.prepareStatement("SELECT uuid, name, password, totp_secret, totp_enabled,"
                     + " telegram_id, vk_id, last_ip, last_login, registered FROM accounts");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                PlayerAccount account = new PlayerAccount(
                        UUID.fromString(rs.getString("uuid")),
                        rs.getString("name"),
                        rs.getString("password"));
                account.setTotpSecret(rs.getString("totp_secret"));
                account.setTotpEnabled(rs.getInt("totp_enabled") == 1);
                account.setTelegramId(rs.getLong("telegram_id"));
                account.setVkId(rs.getLong("vk_id"));
                account.setLastIp(rs.getString("last_ip"));
                account.setLastLogin(rs.getLong("last_login"));
                account.setRegistered(rs.getLong("registered"));
                out.put(account.getUuid(), account);
            }
        }
        return out;
    }

    @Override
    public void save(PlayerAccount account) throws Exception {
        String sql = dialect == Dialect.SQLITE
                ? "INSERT OR REPLACE INTO accounts (uuid, name, password, totp_secret, totp_enabled,"
                  + " telegram_id, vk_id, last_ip, last_login, registered) VALUES (?,?,?,?,?,?,?,?,?,?)"
                : "INSERT INTO accounts (uuid, name, password, totp_secret, totp_enabled,"
                  + " telegram_id, vk_id, last_ip, last_login, registered) VALUES (?,?,?,?,?,?,?,?,?,?)"
                  + " ON DUPLICATE KEY UPDATE name=VALUES(name), password=VALUES(password),"
                  + " totp_secret=VALUES(totp_secret), totp_enabled=VALUES(totp_enabled),"
                  + " telegram_id=VALUES(telegram_id), vk_id=VALUES(vk_id), last_ip=VALUES(last_ip),"
                  + " last_login=VALUES(last_login), registered=VALUES(registered)";
        // sqlite однописательная база: конкурентные записи выстраиваем локом, иначе SQLITE_BUSY
        synchronized (writeLock) {
            try (Connection con = connect(); PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setString(1, account.getUuid().toString());
                ps.setString(2, account.getName());
                ps.setString(3, account.getPasswordHash());
                ps.setString(4, account.getTotpSecret());
                ps.setInt(5, account.isTotpEnabled() ? 1 : 0);
                ps.setLong(6, account.getTelegramId());
                ps.setLong(7, account.getVkId());
                ps.setString(8, account.getLastIp());
                ps.setLong(9, account.getLastLogin());
                ps.setLong(10, account.getRegistered());
                ps.executeUpdate();
            }
        }
    }

    @Override
    public void delete(UUID uuid) throws Exception {
        synchronized (writeLock) {
            try (Connection con = connect();
                 PreparedStatement ps = con.prepareStatement("DELETE FROM accounts WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            }
        }
    }

    // пула коннектов нет нарочно: записей мало (логин, регистрация, смена пароля)
    private Connection connect() throws Exception {
        if (dialect == Dialect.SQLITE) {
            return DriverManager.getConnection(url);
        }
        return DriverManager.getConnection(url, user, password);
    }

    @Override
    public void close() {
    }
}
