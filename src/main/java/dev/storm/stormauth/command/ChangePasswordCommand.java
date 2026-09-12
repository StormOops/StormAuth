package dev.storm.stormauth.command;

import dev.storm.stormauth.StormAuthPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class ChangePasswordCommand implements CommandExecutor {

    private final StormAuthPlugin plugin;

    public ChangePasswordCommand(StormAuthPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getMessages().send(sender, "only-players");
            return true;
        }
        if (args.length != 3) {
            plugin.getMessages().send(sender, "usage", "usage", "/changepassword <старый> <новый> <новый>");
            return true;
        }
        plugin.getAuthManager().changePassword(player, args[0], args[1], args[2]);
        return true;
    }
}
