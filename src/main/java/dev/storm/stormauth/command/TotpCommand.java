package dev.storm.stormauth.command;

import dev.storm.stormauth.StormAuthPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class TotpCommand implements CommandExecutor {

    private final StormAuthPlugin plugin;

    public TotpCommand(StormAuthPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getMessages().send(sender, "only-players");
            return true;
        }
        if (args.length != 1) {
            plugin.getMessages().send(sender, "usage", "usage", "/2fa <код>");
            return true;
        }
        plugin.getAuthManager().verifyTotp(player, args[0]);
        return true;
    }
}
