package dev.storm.stormauth.totp;

import dev.storm.stormauth.StormAuthPlugin;
import dev.storm.stormauth.auth.PlayerAccount;
import org.bukkit.entity.Player;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TotpService {

    private final StormAuthPlugin plugin;
    private final Map<UUID, String> pendingSecrets = new ConcurrentHashMap<>();

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

    public void reset(PlayerAccount account) {
        account.setTotpSecret(null);
        account.setTotpEnabled(false);
        plugin.getDataStore().saveAsync(account);
    }

    public void dropPending(UUID uuid) {
        pendingSecrets.remove(uuid);
    }
}
