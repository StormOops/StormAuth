package dev.storm.stormauth.totp;

import dev.storm.stormauth.StormAuthPlugin;
import dev.storm.stormauth.auth.PasswordHasher;
import dev.storm.stormauth.auth.PlayerAccount;
import org.bukkit.entity.Player;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TotpService {

    private static final int BACKUP_CODES_COUNT = 8;

    private final StormAuthPlugin plugin;
    private final Map<UUID, String> pendingSecrets = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public TotpService(StormAuthPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("totp.enabled", true);
    }

    public boolean isRequired(Player player) {
        return isEnabled() && player.hasPermission("stormauth.totp.required");
    }

    public String startSetup(Player player) {
        String secret = Totp.generateSecret();
        pendingSecrets.put(player.getUniqueId(), secret);
        return secret;
    }

    public String otpauthUrl(String name, String secret) {
        return "otpauth://totp/StormAuth:" + URLEncoder.encode(name, StandardCharsets.UTF_8)
                + "?secret=" + secret + "&issuer=StormAuth";
    }

    public boolean confirmSetup(Player player, PlayerAccount account, String code) {
        String secret = pendingSecrets.get(player.getUniqueId());
        if (secret == null || !Totp.verify(secret, code)) {
            return false;
        }
        pendingSecrets.remove(player.getUniqueId());
        account.setTotpSecret(secret);
        account.setTotpEnabled(true);
        plugin.getDataStore().saveAsync(account);
        return true;
    }

    public boolean verify(PlayerAccount account, String code) {
        return account.getTotpSecret() != null && Totp.verify(account.getTotpSecret(), code);
    }

    // TODO: коды активны только после async-сохранения хэшей; введенный в первую секунду
    // после сетапа backup-код не сработает. окно крошечное, чинить дороже, чем жить
    public List<String> issueBackupCodes(PlayerAccount account) {
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < BACKUP_CODES_COUNT; i++) {
            codes.add(newBackupCode());
        }
        // 8 хэшей pbkdf2 подряд - заметная пауза, считаем в async: игровой поток не морозим
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            List<String> hashes = new ArrayList<>();
            for (String code : codes) {
                hashes.add(PasswordHasher.hash(code));
            }
            account.setBackupCodeHashes(hashes);
            plugin.getDataStore().saveAsync(account);
        });
        return codes;
    }

    public boolean consumeBackupCode(PlayerAccount account, String input) {
        String code = input.trim().toLowerCase();
        for (Iterator<String> it = account.getBackupCodeHashes().iterator(); it.hasNext(); ) {
            if (PasswordHasher.verify(code, it.next())) {
                it.remove();
                plugin.getDataStore().saveAsync(account);
                return true;
            }
        }
        return false;
    }

    public int backupCodesLeft(PlayerAccount account) {
        return account.getBackupCodeHashes().size();
    }

    // алфавит без 0/o и 1/l: их путают при ручном вводе с клавиатуры
    private String newBackupCode() {
        String alphabet = "abcdefghjkmnpqrstuvwxyz23456789";
        StringBuilder code = new StringBuilder(9);
        for (int i = 0; i < 8; i++) {
            if (i == 4) {
                code.append('-');
            }
            code.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return code.toString();
    }

    public void reset(PlayerAccount account) {
        account.setTotpSecret(null);
        account.setTotpEnabled(false);
        plugin.getDataStore().saveAsync(account);
    }

    public void dropPending(UUID uuid) {
        pendingSecrets.remove(uuid);
    }
}
