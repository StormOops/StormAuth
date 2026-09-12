package dev.storm.stormauth;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class Messages {

    private final StormAuthPlugin plugin;
    private YamlConfiguration ru;
    private YamlConfiguration en;

    public Messages(StormAuthPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        saveDefault("messages_ru.yml");
        saveDefault("messages_en.yml");
        ru = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "messages_ru.yml"));
        en = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "messages_en.yml"));
    }

    private void saveDefault(String name) {
        if (!new File(plugin.getDataFolder(), name).exists()) {
            plugin.saveResource(name, false);
        }
    }

    private YamlConfiguration pick(CommandSender to) {
        String lang = plugin.getConfig().getString("default-language", "ru");
        if (to instanceof Player player) {
            String locale = player.getLocale();
            lang = locale != null && locale.toLowerCase().startsWith("ru") ? "ru" : "en";
        }
        return "ru".equals(lang) ? ru : en;
    }

    public String raw(String key, CommandSender to) {
        YamlConfiguration cfg = pick(to);
        String value = cfg.getString(key);
        if (value == null && cfg != en) {
            value = en.getString(key);
        }
        if (value == null && cfg != ru) {
            value = ru.getString(key);
        }
        return value == null ? key : value;
    }

    public List<String> rawList(String key, CommandSender to) {
        YamlConfiguration cfg = pick(to);
        List<String> value = cfg.getStringList(key);
        if (value.isEmpty() && cfg != en) {
            value = en.getStringList(key);
        }
        if (value.isEmpty() && cfg != ru) {
            value = ru.getStringList(key);
        }
        return value;
    }

    public void send(CommandSender to, String key, String... placeholders) {
        String text = apply(raw(key, to), placeholders);
        to.sendMessage(color(raw("prefix", to) + text));
    }

    public void sendList(CommandSender to, String key, String... placeholders) {
        for (String line : rawList(key, to)) {
            to.sendMessage(color(apply(line, placeholders)));
        }
    }

    public Component component(String key, CommandSender to, String... placeholders) {
        return color(apply(raw(key, to), placeholders));
    }

    public String plain(String key, CommandSender to, String... placeholders) {
        return apply(raw(key, to), placeholders).replaceAll("&[0-9a-fk-orA-FK-OR]", "");
    }

    public Component color(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }

    private String apply(String text, String... placeholders) {
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            text = text.replace("%" + placeholders[i] + "%", placeholders[i + 1]);
        }
        return text;
    }

    public List<String> tabFilter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase();
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase().startsWith(lower)) {
                out.add(option);
            }
        }
        return out;
    }
}
