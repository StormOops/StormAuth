package dev.storm.stormauth.util;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ConfigUpdater {

    private ConfigUpdater() {
    }

    public static void update(JavaPlugin plugin, String resourceName) {
        File file = new File(plugin.getDataFolder(), resourceName);
        if (!file.exists()) {
            plugin.saveResource(resourceName, false);
            return;
        }
        try (InputStream in = plugin.getResource(resourceName)) {
            if (in == null) {
                return;
            }
            String[] defaults = new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R", -1);
            List<String> current = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            Set<String> existing = new LinkedHashSet<>();
            for (String line : current) {
                String key = topKey(line);
                if (key != null) {
                    existing.add(key);
                }
            }
            StringBuilder missing = new StringBuilder();
            int i = 0;
            while (i < defaults.length) {
                String key = topKey(defaults[i]);
                if (key == null || existing.contains(key)) {
                    i++;
                    continue;
                }
                int j = i + 1;
                while (j < defaults.length && topKey(defaults[j]) == null) {
                    j++;
                }
                for (int k = i; k < j; k++) {
                    missing.append(defaults[k]).append(System.lineSeparator());
                }
                i = j;
            }
            if (missing.length() > 0) {
                Files.writeString(file.toPath(), System.lineSeparator() + missing,
                        StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            }
        } catch (Exception e) {
            plugin.getLogger().warning(resourceName + " не обновился: " + e.getMessage());
        }
    }

    private static String topKey(String line) {
        if (line.isBlank() || line.startsWith("#") || line.startsWith(" ")
                || line.startsWith("-") || line.startsWith("\t")) {
            return null;
        }
        int colon = line.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        return line.substring(0, colon).trim();
    }
}
