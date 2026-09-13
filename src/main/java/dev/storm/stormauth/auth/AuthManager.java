package dev.storm.stormauth.auth;

import dev.storm.stormauth.StormAuthPlugin;
import dev.storm.stormauth.api.PlayerAuthedEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AuthManager {

    private record Session(String ip, long expiresAt) {
    }

    private final StormAuthPlugin plugin;
    private final Set<UUID> authed = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingTotp = ConcurrentHashMap.newKeySet();
    private final Set<UUID> captchaPassed = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> attempts = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastLoginTry = new ConcurrentHashMap<>();
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public AuthManager(StormAuthPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isAuthed(UUID uuid) {
        return authed.contains(uuid);
    }

    public boolean needsTotp(UUID uuid) {
        return pendingTotp.contains(uuid);
    }

    public void handleJoin(Player player) {
        String ip = ip(player);
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            if (plugin.getConfig().getBoolean("geyser-skip-auth", true)
                    && plugin.getFloodgateHook().isFloodgate(player.getUniqueId())) {
                completeAuth(player, null, PlayerAuthedEvent.Reason.FLOODGATE);
                return;
            }
            PlayerAccount account = plugin.getDataStore().find(player.getUniqueId(), player.getName());
            if (account != null && !account.isTotpEnabled() && !plugin.getTotpService().isRequired(player)) {
                Session session = sessions.get(player.getUniqueId());
                if (session != null && session.expiresAt() > System.currentTimeMillis()
                        && (!plugin.getConfig().getBoolean("session-check-ip", true) || session.ip().equals(ip))) {
                    completeAuth(player, account, PlayerAuthedEvent.Reason.SESSION);
                    return;
                }
                if (plugin.getPremiumCheck().isEnabled() && plugin.getPremiumCheck().isPremium(player.getName())) {
                    boolean sameIp = !plugin.getConfig().getBoolean("premium-autologin.require-same-ip", true)
                            || account.getLastIp().equals(ip);
                    if (sameIp) {
                        completeAuth(player, account, PlayerAuthedEvent.Reason.PREMIUM);
                        return;
                    }
                }
            }
            player.getScheduler().run(plugin, t -> greet(player, account), null);
        });
    }

    private void greet(Player player, PlayerAccount account) {
        plugin.getPromptManager().start(player);
        if (account == null) {
            if (plugin.getConfig().getBoolean("captcha.enabled", false)
                    && !captchaPassed.contains(player.getUniqueId())) {
                plugin.getCaptchaManager().issue(player);
                plugin.getMessages().send(player, "captcha-issued");
            }
            plugin.getMessages().send(player, "must-register");
            return;
        }
        plugin.getMessages().send(player, "must-login");
        if (plugin.getTotpService().isRequired(player) && !account.isTotpEnabled()) {
            plugin.getMessages().send(player, "totp-setup-required");
        }
    }

    public void register(Player player, String password, String repeat) {
        if (isAuthed(player.getUniqueId())) {
            plugin.getMessages().send(player, "already-authed");
            return;
        }
        if (plugin.getDataStore().find(player.getUniqueId(), player.getName()) != null) {
            plugin.getMessages().send(player, "already-registered");
            return;
        }
        if (plugin.getConfig().getBoolean("captcha.enabled", false)
                && !captchaPassed.contains(player.getUniqueId())) {
            plugin.getMessages().send(player, "captcha-required");
            return;
        }
        if (!password.equals(repeat)) {
            plugin.getMessages().send(player, "passwords-mismatch");
            return;
        }
        if (!passwordOk(player, password)) {
            return;
        }
        String ip = ip(player);
        int limit = plugin.getConfig().getInt("max-accounts-per-ip", 3);
        if (limit > 0 && plugin.getDataStore().countByIp(ip) >= limit) {
            plugin.getMessages().send(player, "accounts-limit", "max", String.valueOf(limit));
            return;
        }
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            PlayerAccount account = new PlayerAccount(player.getUniqueId(), player.getName(),
                    PasswordHasher.hash(password));
            account.setLastIp(ip);
            account.setLastLogin(System.currentTimeMillis());
            plugin.getDataStore().put(account);
            plugin.getDataStore().saveAsync(account);
            plugin.getSecurityLog().log(player.getName() + " зарегистрирован, ip " + ip);
            player.getScheduler().run(plugin, t -> {
                captchaPassed.remove(player.getUniqueId());
                plugin.getMessages().send(player, "registered");
                if (plugin.getTotpService().isRequired(player)) {
                    startEnroll(player);
                } else {
                    completeAuth(player, account, PlayerAuthedEvent.Reason.PASSWORD);
                }
            }, null);
        });
    }

    public void login(Player player, String password) {
        if (isAuthed(player.getUniqueId())) {
            plugin.getMessages().send(player, "already-authed");
            return;
        }
        PlayerAccount account = plugin.getDataStore().find(player.getUniqueId(), player.getName());
        if (account == null) {
            plugin.getMessages().send(player, "not-registered");
            return;
        }
        if (pendingTotp.contains(player.getUniqueId())) {
            plugin.getMessages().send(player, "totp-code-needed");
            return;
        }
        // кулдаун до хэширования: без него спам /login гоняет pbkdf2 на 65к итераций и грузит ядра
        long now = System.currentTimeMillis();
        Long lastTry = lastLoginTry.get(player.getUniqueId());
        long cooldown = plugin.getConfig().getLong("login-cooldown-seconds", 2) * 1000;
        if (lastTry != null && now - lastTry < cooldown) {
            plugin.getMessages().send(player, "login-cooldown");
            return;
        }
        lastLoginTry.put(player.getUniqueId(), now);
        String ip = ip(player);
        // pbkdf2 на 65к итераций считаем в async - игровой поток не морозим
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            if (!PasswordHasher.verify(password, account.getPasswordHash())) {
                player.getScheduler().run(plugin, t -> failedAttempt(player, "wrong-password"), null);
                return;
            }
            if (PasswordHasher.isLegacy(account.getPasswordHash())) {
                account.setPasswordHash(PasswordHasher.hash(password));
                plugin.getDataStore().saveAsync(account);
            }
            plugin.getBruteForceGuard().reset(ip);
            attempts.remove(player.getUniqueId());
            player.getScheduler().run(plugin, t -> afterPassword(player, account), null);
        });
    }

    private void afterPassword(Player player, PlayerAccount account) {
        if (account.isTotpEnabled()) {
            pendingTotp.add(player.getUniqueId());
            plugin.getMessages().send(player, "totp-code-needed");
            return;
        }
        if (plugin.getTotpService().isRequired(player)) {
            startEnroll(player);
            return;
        }
        completeAuth(player, account, PlayerAuthedEvent.Reason.PASSWORD);
    }

    private void startEnroll(Player player) {
        String secret = plugin.getTotpService().startSetup(player);
        plugin.getMessages().send(player, "totp-setup-required");
        plugin.getMessages().send(player, "totp-secret",
                "secret", secret,
                "url", plugin.getTotpService().otpauthUrl(player.getName(), secret));
    }

    public void verifyTotp(Player player, String code) {
        if (isAuthed(player.getUniqueId())) {
            plugin.getMessages().send(player, "already-authed");
            return;
        }
        if (!pendingTotp.contains(player.getUniqueId())) {
            plugin.getMessages().send(player, "totp-no-request");
            return;
        }
        PlayerAccount account = plugin.getDataStore().find(player.getUniqueId(), player.getName());
        if (account == null) {
            pendingTotp.remove(player.getUniqueId());
            plugin.getMessages().send(player, "not-registered");
            return;
        }
        if (plugin.getTotpService().verify(account, code)) {
            pendingTotp.remove(player.getUniqueId());
            completeAuth(player, account, PlayerAuthedEvent.Reason.TOTP);
            return;
        }
        failedAttempt(player, "totp-wrong");
    }

    public void setupTotp(Player player) {
        if (!plugin.getTotpService().isEnabled()) {
            plugin.getMessages().send(player, "totp-disabled");
            return;
        }
        PlayerAccount account = plugin.getDataStore().find(player.getUniqueId(), player.getName());
        if (account == null) {
            plugin.getMessages().send(player, "not-registered");
            return;
        }
        if (account.isTotpEnabled()) {
            plugin.getMessages().send(player, "totp-already");
            return;
        }
        if (!isAuthed(player.getUniqueId())) {
            plugin.getMessages().send(player, "not-authed");
            return;
        }
        String secret = plugin.getTotpService().startSetup(player);
        plugin.getMessages().send(player, "totp-secret",
                "secret", secret,
                "url", plugin.getTotpService().otpauthUrl(player.getName(), secret));
    }

    public void confirmSetup(Player player, String code) {
        PlayerAccount account = plugin.getDataStore().find(player.getUniqueId(), player.getName());
        if (account == null) {
            plugin.getMessages().send(player, "not-registered");
            return;
        }
        if (account.isTotpEnabled()) {
            plugin.getMessages().send(player, "totp-already");
            return;
        }
        if (plugin.getTotpService().confirmSetup(player, account, code)) {
            plugin.getMessages().send(player, "totp-setup-success");
            plugin.getSecurityLog().log(player.getName() + " включил 2fa, ip " + ip(player));
            plugin.getSocialService().notify(account, "notify-2fa-enabled", "player", account.getName());
            if (!isAuthed(player.getUniqueId())) {
                completeAuth(player, account, PlayerAuthedEvent.Reason.TOTP);
            }
            return;
        }
        plugin.getMessages().send(player, "totp-setup-wrong");
    }

    public void changePassword(Player player, String oldPassword, String newPassword, String repeat) {
        if (!isAuthed(player.getUniqueId())) {
            plugin.getMessages().send(player, "not-authed");
            return;
        }
        if (!newPassword.equals(repeat)) {
            plugin.getMessages().send(player, "passwords-mismatch");
            return;
        }
        if (!passwordOk(player, newPassword)) {
            return;
        }
        PlayerAccount account = plugin.getDataStore().find(player.getUniqueId(), player.getName());
        if (account == null) {
            plugin.getMessages().send(player, "not-registered");
            return;
        }
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            if (!PasswordHasher.verify(oldPassword, account.getPasswordHash())) {
                player.getScheduler().run(plugin, t ->
                        plugin.getMessages().send(player, "changepassword-wrong-old"), null);
                return;
            }
            account.setPasswordHash(PasswordHasher.hash(newPassword));
            plugin.getDataStore().saveAsync(account);
            player.getScheduler().run(plugin, t -> {
                plugin.getMessages().send(player, "changepassword-success");
                plugin.getSecurityLog().log(player.getName() + " сменил пароль, ip " + ip(player));
                plugin.getSocialService().notify(account, "notify-password-changed", "player", account.getName());
            }, null);
        });
    }

    public void confirmRecovery(Player player, String code, String newPassword) {
        if (isAuthed(player.getUniqueId())) {
            plugin.getMessages().send(player, "already-authed");
            return;
        }
        if (!passwordOk(player, newPassword)) {
            return;
        }
        switch (plugin.getSocialService().checkRecoveryCode(player.getUniqueId(), code)) {
            case NONE, BURNED -> plugin.getMessages().send(player, "recovery-burned");
            case WRONG -> plugin.getMessages().send(player, "recovery-wrong",
                    "left", String.valueOf(plugin.getSocialService().recoveryAttemptsLeft(player.getUniqueId())));
            case OK -> {
                PlayerAccount account = plugin.getDataStore().find(player.getUniqueId(), player.getName());
                if (account == null) {
                    plugin.getMessages().send(player, "not-registered");
                    return;
                }
                plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
                    account.setPasswordHash(PasswordHasher.hash(newPassword));
                    plugin.getDataStore().saveAsync(account);
                    player.getScheduler().run(plugin, t -> {
                        plugin.getSecurityLog().log(player.getName() + " сбросил пароль через соцсеть, ip " + ip(player));
                        plugin.getSocialService().notify(account, "notify-password-changed", "player", account.getName());
                        if (account.isTotpEnabled() || plugin.getTotpService().isRequired(player)) {
                            plugin.getMessages().send(player, "recovery-login-now");
                        } else {
                            completeAuth(player, account, PlayerAuthedEvent.Reason.RECOVERY);
                        }
                    }, null);
                });
            }
        }
    }

    public void logout(Player player) {
        if (!isAuthed(player.getUniqueId())) {
            plugin.getMessages().send(player, "not-authed");
            return;
        }
        authed.remove(player.getUniqueId());
        sessions.remove(player.getUniqueId());
        plugin.getMessages().send(player, "logged-out");
        plugin.getPromptManager().start(player);
    }

    public PlayerAccount unregister(String name) {
        PlayerAccount account = plugin.getDataStore().findByName(name);
        if (account == null) {
            return null;
        }
        plugin.getDataStore().remove(account.getUuid());
        sessions.remove(account.getUuid());
        Player online = plugin.getServer().getPlayer(account.getUuid());
        if (online != null) {
            online.getScheduler().run(plugin, task -> {
                handleQuit(online);
                online.kick(plugin.getMessages().component("kick-unregistered", online));
            }, null);
        }
        plugin.getSecurityLog().log("аккаунт " + account.getName() + " удален администратором");
        return account;
    }

    public void adminSetPassword(PlayerAccount account, String newPassword) {
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            account.setPasswordHash(PasswordHasher.hash(newPassword));
            plugin.getDataStore().saveAsync(account);
        });
        plugin.getSecurityLog().log("аккаунту " + account.getName() + " сменили пароль из админки");
    }

    public void handleQuit(Player player) {
        UUID uuid = player.getUniqueId();
        authed.remove(uuid);
        pendingTotp.remove(uuid);
        attempts.remove(uuid);
        lastLoginTry.remove(uuid);
        captchaPassed.remove(uuid);
        plugin.getTotpService().dropPending(uuid);
    }

    public void markCaptchaPassed(Player player) {
        captchaPassed.add(player.getUniqueId());
    }

    public boolean passwordOk(CommandSender sender, String password) {
        int min = plugin.getConfig().getInt("password.min-length", 6);
        int max = plugin.getConfig().getInt("password.max-length", 32);
        if (password.length() < min) {
            plugin.getMessages().send(sender, "password-short", "min", String.valueOf(min));
            return false;
        }
        if (password.length() > max) {
            plugin.getMessages().send(sender, "password-long", "max", String.valueOf(max));
            return false;
        }
        if (plugin.getConfig().getStringList("weak-passwords").contains(password.toLowerCase())) {
            plugin.getMessages().send(sender, "password-weak");
            return false;
        }
        return true;
    }

    public String ip(Player player) {
        return player.getAddress() != null ? player.getAddress().getAddress().getHostAddress() : "?";
    }

    private void failedAttempt(Player player, String messageKey) {
        String ip = ip(player);
        int max = plugin.getConfig().getInt("max-login-attempts", 3);
        int left = max - attempts.merge(player.getUniqueId(), 1, Integer::sum);
        plugin.getLogger().info("неверная попытка входа у " + player.getName() + " (" + ip + ")");
        plugin.getSecurityLog().log(player.getName() + " неверная попытка входа, ip " + ip);
        if (left <= 0) {
            attempts.remove(player.getUniqueId());
            pendingTotp.remove(player.getUniqueId());
            plugin.getBruteForceGuard().registerKick(ip);
            player.kick(plugin.getMessages().component("kick-password-limit", player));
            return;
        }
        plugin.getMessages().send(player, messageKey, "left", String.valueOf(left));
    }

    private void completeAuth(Player player, PlayerAccount account, PlayerAuthedEvent.Reason reason) {
        player.getScheduler().run(plugin, task -> {
            UUID uuid = player.getUniqueId();
            authed.add(uuid);
            pendingTotp.remove(uuid);
            attempts.remove(uuid);
            plugin.getPromptManager().cancel(player);
            String ip = ip(player);
            switch (reason) {
                case SESSION -> plugin.getMessages().send(player, "logged-in-session");
                case TOTP -> plugin.getMessages().send(player, "totp-success");
                case RECOVERY -> plugin.getMessages().send(player, "recovery-success");
                case FLOODGATE -> {
                }
                default -> plugin.getMessages().send(player, "logged-in");
            }
            if (account != null) {
                long timeout = plugin.getConfig().getLong("session-timeout-minutes", 10) * 60_000;
                sessions.put(uuid, new Session(ip, System.currentTimeMillis() + timeout));
                if (!ip.equals(account.getLastIp()) && !account.getLastIp().isEmpty() && account.hasSocial()) {
                    plugin.getSocialService().notify(account, "notify-login-new-ip",
                            "player", account.getName(), "ip", ip);
                }
                account.setLastIp(ip);
                account.setLastLogin(System.currentTimeMillis());
                plugin.getDataStore().saveAsync(account);
            }
            plugin.getSecurityLog().log(player.getName() + " вошел (" + reason + "), ip " + ip);
            plugin.getServer().getPluginManager().callEvent(new PlayerAuthedEvent(player, reason));
        }, null);
    }
}
