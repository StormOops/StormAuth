package dev.storm.stormauth;

import dev.storm.stormauth.auth.AuthManager;
import dev.storm.stormauth.captcha.CaptchaManager;
import dev.storm.stormauth.command.CaptchaCommand;
import dev.storm.stormauth.command.ChangePasswordCommand;
import dev.storm.stormauth.command.LinkCommand;
import dev.storm.stormauth.command.LoginCommand;
import dev.storm.stormauth.command.LogoutCommand;
import dev.storm.stormauth.command.RecoveryCommand;
import dev.storm.stormauth.command.RegisterCommand;
import dev.storm.stormauth.command.StormAuthCommand;
import dev.storm.stormauth.command.TotpCommand;
import dev.storm.stormauth.command.TotpSetupCommand;
import dev.storm.stormauth.command.UnlinkCommand;
import dev.storm.stormauth.hook.FloodgateHook;
import dev.storm.stormauth.hook.PlaceholderHook;
import dev.storm.stormauth.listener.AuthGateListener;
import dev.storm.stormauth.listener.JoinQuitListener;
import dev.storm.stormauth.prompt.PromptManager;
import dev.storm.stormauth.security.AntiVpn;
import dev.storm.stormauth.security.BruteForceGuard;
import dev.storm.stormauth.security.PremiumCheck;
import dev.storm.stormauth.security.SecurityLog;
import dev.storm.stormauth.social.SocialService;
import dev.storm.stormauth.storage.PlayerDataStore;
import dev.storm.stormauth.totp.TotpService;
import dev.storm.stormauth.util.ConfigUpdater;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

public final class StormAuthPlugin extends JavaPlugin {

    private Messages messages;
    private PlayerDataStore dataStore;
    private AuthManager authManager;
    private TotpService totpService;
    private CaptchaManager captchaManager;
    private PromptManager promptManager;
    private SocialService socialService;
    private SecurityLog securityLog;
    private BruteForceGuard bruteForceGuard;
    private AntiVpn antiVpn;
    private PremiumCheck premiumCheck;
    private FloodgateHook floodgateHook;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ConfigUpdater.update(this, "config.yml");
        ConfigUpdater.update(this, "messages_ru.yml");
        ConfigUpdater.update(this, "messages_en.yml");
        reloadConfig();

        messages = new Messages(this);
        messages.load();

        securityLog = new SecurityLog(this);
        bruteForceGuard = new BruteForceGuard(this);
        bruteForceGuard.load();
        antiVpn = new AntiVpn(this);
        premiumCheck = new PremiumCheck(this);
        floodgateHook = FloodgateHook.create(getLogger());

        dataStore = new PlayerDataStore(this);
        dataStore.init();

        totpService = new TotpService(this);
        promptManager = new PromptManager(this);
        captchaManager = new CaptchaManager(this);
        socialService = new SocialService(this);
        socialService.start();
        authManager = new AuthManager(this);

        getServer().getPluginManager().registerEvents(new JoinQuitListener(this), this);
        getServer().getPluginManager().registerEvents(new AuthGateListener(this), this);

        command("login", new LoginCommand(this));
        command("register", new RegisterCommand(this));
        command("changepassword", new ChangePasswordCommand(this));
        command("logout", new LogoutCommand(this));
        command("captcha", new CaptchaCommand(this));
        command("link", new LinkCommand(this));
        command("unlink", new UnlinkCommand(this));
        command("recovery", new RecoveryCommand(this));
        command("2fa", new TotpCommand(this));
        command("2fasetup", new TotpSetupCommand(this));
        command("stormauth", new StormAuthCommand(this));

        PlaceholderHook.register(this);
        getLogger().info("StormAuth " + getDescription().getVersion() + " включен, хранилище: " + dataStore.backendName());
    }

    @Override
    public void onDisable() {
        if (socialService != null) {
            socialService.shutdown();
        }
        if (dataStore != null) {
            dataStore.close();
        }
    }

    public void reloadAll() {
        reloadConfig();
        ConfigUpdater.update(this, "config.yml");
        ConfigUpdater.update(this, "messages_ru.yml");
        ConfigUpdater.update(this, "messages_en.yml");
        messages.load();
        bruteForceGuard.load();
        socialService.restart();
    }

    private void command(String name, CommandExecutor executor) {
        PluginCommand cmd = getCommand(name);
        if (cmd == null) {
            throw new IllegalStateException("команда " + name + " не описана в plugin.yml");
        }
        cmd.setExecutor(executor);
        if (executor instanceof TabCompleter completer) {
            cmd.setTabCompleter(completer);
        }
    }

    public Messages getMessages() {
        return messages;
    }

    public PlayerDataStore getDataStore() {
        return dataStore;
    }

    public AuthManager getAuthManager() {
        return authManager;
    }

    public TotpService getTotpService() {
        return totpService;
    }

    public CaptchaManager getCaptchaManager() {
        return captchaManager;
    }

    public PromptManager getPromptManager() {
        return promptManager;
    }

    public SocialService getSocialService() {
        return socialService;
    }

    public SecurityLog getSecurityLog() {
        return securityLog;
    }

    public BruteForceGuard getBruteForceGuard() {
        return bruteForceGuard;
    }

    public AntiVpn getAntiVpn() {
        return antiVpn;
    }

    public PremiumCheck getPremiumCheck() {
        return premiumCheck;
    }

    public FloodgateHook getFloodgateHook() {
        return floodgateHook;
    }
}
