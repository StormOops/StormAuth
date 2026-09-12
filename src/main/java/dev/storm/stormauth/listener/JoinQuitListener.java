package dev.storm.stormauth.listener;

import dev.storm.stormauth.StormAuthPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class JoinQuitListener implements Listener {

    private final StormAuthPlugin plugin;

    public JoinQuitListener(StormAuthPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        String ip = event.getAddress().getHostAddress();
        if (plugin.getBruteForceGuard().isBanned(ip)) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    plugin.getMessages().plain("kick-banned", null,
                            "minutes", String.valueOf(plugin.getBruteForceGuard().minutesLeft(ip))));
            return;
        }
        if (plugin.getAntiVpn().isEnabled() && plugin.getAntiVpn().isBad(ip)) {
            plugin.getSecurityLog().log("анти-vpn не пустил " + event.getName() + " с ip " + ip);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    plugin.getMessages().plain("kick-antivpn", null));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.getAuthManager().handleJoin(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.getAuthManager().handleQuit(event.getPlayer());
        plugin.getPromptManager().cancel(event.getPlayer());
        plugin.getCaptchaManager().cleanup(event.getPlayer());
    }
}
