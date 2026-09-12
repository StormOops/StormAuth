package dev.storm.stormauth.prompt;

import dev.storm.stormauth.StormAuthPlugin;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PromptManager {

    private final StormAuthPlugin plugin;
    private final Map<UUID, ScheduledTask> tasks = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> bars = new ConcurrentHashMap<>();

    public PromptManager(StormAuthPlugin plugin) {
        this.plugin = plugin;
    }

    public void start(Player player) {
        cancel(player);
        int seconds = plugin.getConfig().getInt("auth-timeout-seconds", 30);
        if (seconds <= 0) {
            return;
        }
        boolean showTitle = plugin.getConfig().getBoolean("prompt.title", true);
        BossBar bar = null;
        if (plugin.getConfig().getBoolean("prompt.bossbar", true)) {
            bar = BossBar.bossBar(
                    plugin.getMessages().component("bossbar-text", player, "seconds", String.valueOf(seconds)),
                    1f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
            player.showBossBar(bar);
            bars.put(player.getUniqueId(), bar);
        }
        BossBar activeBar = bar;
        int[] left = {seconds};
        ScheduledTask task = player.getScheduler().runAtFixedRate(plugin, tick -> {
            if (plugin.getAuthManager().isAuthed(player.getUniqueId())) {
                tick.cancel();
                tasks.remove(player.getUniqueId());
                return;
            }
            left[0]--;
            if (left[0] <= 0) {
                cancel(player);
                player.kick(plugin.getMessages().component("kick-timeout", player));
                return;
            }
            if (showTitle) {
                player.showTitle(Title.title(
                        plugin.getMessages().component("prompt-title", player,
                                "seconds", String.valueOf(left[0])),
                        plugin.getMessages().component("prompt-subtitle", player),
                        Title.Times.times(Duration.ZERO, Duration.ofSeconds(2), Duration.ZERO)));
            }
            if (activeBar != null) {
                activeBar.progress((float) left[0] / seconds);
                activeBar.name(plugin.getMessages().component("bossbar-text", player,
                        "seconds", String.valueOf(left[0])));
            }
            if (left[0] % 10 == 0) {
                if (plugin.getDataStore().find(player.getUniqueId(), player.getName()) != null) {
                    plugin.getMessages().send(player, "prompt-hint-login");
                } else {
                    plugin.getMessages().send(player, "prompt-hint-register");
                }
            }
        }, null, 20, 20);
        if (task != null) {
            tasks.put(player.getUniqueId(), task);
        }
    }

    public void cancel(Player player) {
        ScheduledTask task = tasks.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
        }
        BossBar bar = bars.remove(player.getUniqueId());
        if (bar != null) {
            player.hideBossBar(bar);
        }
    }
}
