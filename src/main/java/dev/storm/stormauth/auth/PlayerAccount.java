package dev.storm.stormauth.auth;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class PlayerAccount {

    private final UUID uuid;
    private String name;
    private String passwordHash;
    private String totpSecret;
    private boolean totpEnabled;
    private List<String> backupCodeHashes = new ArrayList<>();
    private long telegramId = -1;
    private long vkId = -1;
    private String lastIp = "";
    private long lastLogin;
    private long registered = System.currentTimeMillis();

    public PlayerAccount(UUID uuid, String name, String passwordHash) {
        this.uuid = uuid;
        this.name = name;
        this.passwordHash = passwordHash;
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getTotpSecret() {
        return totpSecret;
    }

    public void setTotpSecret(String totpSecret) {
        this.totpSecret = totpSecret;
    }

    public boolean isTotpEnabled() {
        return totpEnabled;
    }

    public void setTotpEnabled(boolean totpEnabled) {
        this.totpEnabled = totpEnabled;
    }

    public List<String> getBackupCodeHashes() {
        return backupCodeHashes;
    }

    public void setBackupCodeHashes(List<String> backupCodeHashes) {
        this.backupCodeHashes = backupCodeHashes;
    }

    public long getTelegramId() {
        return telegramId;
    }

    public void setTelegramId(long telegramId) {
        this.telegramId = telegramId;
    }

    public long getVkId() {
        return vkId;
    }

    public void setVkId(long vkId) {
        this.vkId = vkId;
    }

    public String getLastIp() {
        return lastIp;
    }

    public void setLastIp(String lastIp) {
        this.lastIp = lastIp;
    }

    public long getLastLogin() {
        return lastLogin;
    }

    public void setLastLogin(long lastLogin) {
        this.lastLogin = lastLogin;
    }

    public long getRegistered() {
        return registered;
    }

    public void setRegistered(long registered) {
        this.registered = registered;
    }

    public boolean hasSocial() {
        return telegramId >= 0 || vkId >= 0;
    }
}
