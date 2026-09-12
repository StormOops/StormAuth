package dev.storm.stormauth.hook;

import dev.storm.stormauth.StormAuthPlugin;
import dev.storm.stormauth.auth.PlayerAccount;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class PlaceholderHook {

    private PlaceholderHook() {
    }

    public static void register(StormAuthPlugin plugin) {
        if (plugin.getServer().getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return;
        }
        new Expansion(plugin).register();
        plugin.getLogger().info("placeholderapi найдена, плейсхолдеры %stormauth_*% активны");
    }

    private static final class Expansion extends PlaceholderExpansion {

        private final StormAuthPlugin plugin;

        private Expansion(StormAuthPlugin plugin) {
            this.plugin = plugin;
        }

        @Override
        public @NotNull String getIdentifier() {
            return "stormauth";
        }

        @Override
        public @NotNull String getAuthor() {
            return "StormOops";
        }

        @Override
        public @NotNull String getVersion() {
            return plugin.getDescription().getVersion();
        }

        @Override
        public boolean persist() {
            return true;
        }

        @Override
        public String onPlaceholderRequest(Player player, @NotNull String params) {
            if (player == null) {
                return "no";
            }
            PlayerAccount account = plugin.getDataStore().find(player.getUniqueId(), player.getName());
            boolean value = switch (params.toLowerCase()) {
                case "authed" -> plugin.getAuthManager().isAuthed(player.getUniqueId());
                case "registered" -> account != null;
                case "2fa" -> account != null && account.isTotpEnabled();
                case "telegram" -> account != null && account.getTelegramId() >= 0;
                case "vk" -> account != null && account.getVkId() >= 0;
                default -> false;
            };
            return value ? "yes" : "no";
        }
    }
}
