package dev.storm.stormauth.command;

import dev.storm.stormauth.StormAuthPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class RecoveryCommand implements CommandExecutor {

    private final StormAuthPlugin plugin;

    public RecoveryCommand(StormAuthPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getMessages().send(sender, "only-players");
            return true;
        }
        if (args.length == 0) {
            if (plugin.getAuthManager().isAuthed(player.getUniqueId())) {
                plugin.getMessages().send(player, "already-authed");
                return true;
            }
            plugin.getSocialService().startRecovery(player);
            return true;
        }
        if (args.length == 2) {
            plugin.getAuthManager().confirmRecovery(player, args[0], args[1]);
            return true;
        }
        plugin.getMessages().send(sender, "usage", "usage", "/recovery [код] [новый пароль]");
        return true;
    }
}
