package dev.storm.stormauth.command;

import dev.storm.stormauth.StormAuthPlugin;
import dev.storm.stormauth.social.SocialPlatform;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

public final class LinkCommand implements CommandExecutor, TabCompleter {

    private final StormAuthPlugin plugin;

    public LinkCommand(StormAuthPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getMessages().send(sender, "only-players");
            return true;
        }
        SocialPlatform platform = args.length == 1 ? SocialPlatform.byId(args[0]) : null;
        if (platform == null) {
            plugin.getMessages().send(sender, "usage", "usage", "/link <telegram|vk>");
            return true;
        }
        if (!plugin.getAuthManager().isAuthed(player.getUniqueId())) {
            plugin.getMessages().send(player, "not-authed");
            return true;
        }
        plugin.getSocialService().createLinkCode(player, platform);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return plugin.getMessages().tabFilter(List.of("telegram", "vk"), args[0]);
        }
        return List.of();
    }
}
