package dev.storm.stormauth.security;

import dev.storm.stormauth.StormAuthPlugin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public final class SecurityLog {

    private final StormAuthPlugin plugin;

    public SecurityLog(StormAuthPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("security-log.enabled", true);
    }

    public void log(String event) {
        if (!isEnabled()) {
            return;
        }
        String fileName = "security-" + LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) + ".log";
        String line = "[" + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "] "
                + event + System.lineSeparator();
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            try {
                File file = new File(plugin.getDataFolder(), fileName);
                file.getParentFile().mkdirs();
                Files.writeString(file.toPath(), line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Exception e) {
                plugin.getLogger().warning("аудит не записался: " + e.getMessage());
            }
        });
    }
}
