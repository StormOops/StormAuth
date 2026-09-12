package dev.storm.stormauth.command;

import dev.storm.stormauth.StormAuthPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class RegisterCommand implements CommandExecutor {

    private final StormAuthPlugin plugin;

    public RegisterCommand(StormAuthPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getMessages().send(sender, "only-players");
            return true;
        }
        if (args.length != 2) {
            plugin.getMessages().send(sender, "usage", "usage", "/register <пароль> <пароль>");
            return true;
        }
        plugin.getAuthManager().register(player, args[0], args[1]);
        return true;
    }
}
