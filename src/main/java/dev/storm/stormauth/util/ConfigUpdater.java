package dev.storm.stormauth.util;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
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
            String newline = System.lineSeparator();
            StringBuilder missing = new StringBuilder();
            List<String> pending = new ArrayList<>();
            int i = 0;
            while (i < defaults.length) {
                String key = topKey(defaults[i]);
                if (key == null) {
                    pending.add(defaults[i]);
                    i++;
                    continue;
                }
                int j = i + 1;
                while (j < defaults.length && topKey(defaults[j]) == null) {
                    j++;
                }
                int end = j;
                while (end > i + 1 && isCommentOrBlank(defaults[end - 1])) {
                    end--;
                }
                if (!existing.contains(key)) {
                    for (String comment : pending) {
                        missing.append(comment).append(newline);
                    }
                    for (int k = i; k < end; k++) {
                        missing.append(defaults[k]).append(newline);
                    }
                }
                pending.clear();
                for (int k = end; k < j; k++) {
                    pending.add(defaults[k]);
                }
                i = j;
            }
            if (missing.length() > 0) {
                Files.writeString(file.toPath(), newline + missing, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            }
        } catch (Exception e) {
            plugin.getLogger().warning(resourceName + " не обновился: " + e.getMessage());
        }
    }

    private static boolean isCommentOrBlank(String line) {
        return line.isBlank() || line.trim().startsWith("#");
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
