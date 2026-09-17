package dev.storm.stormauth.util;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
            List<String> defaults = new ArrayList<>(List.of(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R", -1)));
            if (!defaults.isEmpty() && defaults.get(defaults.size() - 1).isEmpty()) {
                defaults.remove(defaults.size() - 1);
            }
            List<String> current = new ArrayList<>(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
            boolean dirty = false;
            String newline = System.lineSeparator();
            StringBuilder missing = new StringBuilder();
            List<String> pending = new ArrayList<>();
            int i = 0;
            while (i < defaults.size()) {
                String key = topKey(defaults.get(i));
                if (key == null) {
                    pending.add(defaults.get(i));
                    i++;
                    continue;
                }
                int j = i + 1;
                while (j < defaults.size() && topKey(defaults.get(j)) == null) {
                    j++;
                }
                int end = j;
                while (end > i + 1 && isCommentOrBlank(defaults.get(end - 1))) {
                    end--;
                }
                int topIdx = indexOfTopKey(current, key);
                if (topIdx < 0) {
                    for (String comment : pending) {
                        missing.append(comment).append(newline);
                    }
                    for (int k = i; k < end; k++) {
                        missing.append(defaults.get(k)).append(newline);
                    }
                } else {
                    // секция на месте, но в ней могут появиться новые вложенные ключи
                    dirty |= mergeNested(current, topIdx, defaults.subList(i, end));
                }
                pending.clear();
                for (int k = end; k < j; k++) {
                    pending.add(defaults.get(k));
                }
                i = j;
            }
            if (missing.length() > 0) {
                current.add("");
                missing.toString().lines().forEach(current::add);
                dirty = true;
            }
            if (dirty) {
                Files.write(file.toPath(), current, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            plugin.getLogger().warning(resourceName + " не обновился: " + e.getMessage());
        }
    }

    // вложенных ключей (отступ в 2 пробела) у существующей секции может не хватать
    // после обновления плагина - переносим их из дефолтного конфига внутрь секции,
    // иначе новая опция молча не заводится у тех, кто обновился
    private static boolean mergeNested(List<String> current, int topIdx, List<String> defaultBlock) {
        int sectionEnd = topIdx + 1;
        while (sectionEnd < current.size() && topKey(current.get(sectionEnd)) == null) {
            sectionEnd++;
        }
        Set<String> existing = new LinkedHashSet<>();
        for (int k = topIdx + 1; k < sectionEnd; k++) {
            String key = nestedKey(current.get(k));
            if (key != null) {
                existing.add(key);
            }
        }
        boolean dirty = false;
        List<String> carry = new ArrayList<>();
        int i = 1;
        while (i < defaultBlock.size()) {
            String key = nestedKey(defaultBlock.get(i));
            if (key == null) {
                carry.add(defaultBlock.get(i));
                i++;
                continue;
            }
            int j = i + 1;
            while (j < defaultBlock.size() && nestedKey(defaultBlock.get(j)) == null
                    && isDeep(defaultBlock.get(j))) {
                j++;
            }
            int end = j;
            while (end > i + 1 && isCommentOrBlank(defaultBlock.get(end - 1))) {
                end--;
            }
            if (!existing.contains(key)) {
                // вставляем до хвостовых пустых строк секции, чтобы не отрывать её от следующей
                int at = sectionEnd;
                while (at > topIdx + 1 && current.get(at - 1).isBlank()) {
                    at--;
                }
                List<String> ins = new ArrayList<>(carry);
                for (int k = i; k < end; k++) {
                    ins.add(defaultBlock.get(k));
                }
                current.addAll(at, ins);
                sectionEnd += ins.size();
                dirty = true;
            }
            carry.clear();
            for (int k = end; k < j; k++) {
                carry.add(defaultBlock.get(k));
            }
            i = j;
        }
        return dirty;
    }

    private static int indexOfTopKey(List<String> lines, String key) {
        for (int k = 0; k < lines.size(); k++) {
            if (key.equals(topKey(lines.get(k)))) {
                return k;
            }
        }
        return -1;
    }

    private static boolean isCommentOrBlank(String line) {
        return line.isBlank() || line.trim().startsWith("#");
    }

    private static boolean isDeep(String line) {
        if (line.isBlank()) {
            return true;
        }
        int spaces = 0;
        while (spaces < line.length() && line.charAt(spaces) == ' ') {
            spaces++;
        }
        return spaces > 2;
    }

    private static String nestedKey(String line) {
        if (!line.startsWith("  ") || line.startsWith("   ")) {
            return null;
        }
        String t = line.trim();
        if (t.startsWith("#") || t.startsWith("-")) {
            return null;
        }
        int colon = t.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        return t.substring(0, colon).trim();
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
