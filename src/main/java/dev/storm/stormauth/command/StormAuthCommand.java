package dev.storm.stormauth.command;

import dev.storm.stormauth.StormAuthPlugin;
import dev.storm.stormauth.auth.PlayerAccount;
import dev.storm.stormauth.importer.AuthMeImporter;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class StormAuthCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of(
            "reload", "unregister", "changepassword", "reset2fa", "info", "import");
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final StormAuthPlugin plugin;

    public StormAuthCommand(StormAuthPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("stormauth.admin")) {
            plugin.getMessages().send(sender, "no-permission");
            return true;
        }
        if (args.length == 0) {
            plugin.getMessages().send(sender, "admin-usage");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload" -> {
                plugin.reloadAll();
                plugin.getMessages().send(sender, "admin-reloaded");
            }
            case "unregister" -> {
                if (args.length != 2) {
                    plugin.getMessages().send(sender, "usage", "usage", "/stormauth unregister <ник>");
                    return true;
                }
                PlayerAccount account = plugin.getAuthManager().unregister(args[1]);
                if (account == null) {
                    plugin.getMessages().send(sender, "admin-player-not-found", "player", args[1]);
                    return true;
                }
                plugin.getMessages().send(sender, "admin-unregistered", "player", account.getName());
            }
            case "changepassword" -> {
                if (args.length != 3) {
                    plugin.getMessages().send(sender, "usage", "usage", "/stormauth changepassword <ник> <новый>");
                    return true;
                }
                PlayerAccount account = plugin.getDataStore().findByName(args[1]);
                if (account == null) {
                    plugin.getMessages().send(sender, "admin-player-not-found", "player", args[1]);
                    return true;
                }
                if (!plugin.getAuthManager().passwordOk(sender, args[2])) {
                    return true;
                }
                plugin.getAuthManager().adminSetPassword(account, args[2]);
                plugin.getMessages().send(sender, "admin-password-changed", "player", account.getName());
            }
            case "reset2fa" -> {
                if (args.length != 2) {
                    plugin.getMessages().send(sender, "usage", "usage", "/stormauth reset2fa <ник>");
                    return true;
                }
                PlayerAccount account = plugin.getDataStore().findByName(args[1]);
                if (account == null) {
                    plugin.getMessages().send(sender, "admin-player-not-found", "player", args[1]);
                    return true;
                }
                plugin.getTotpService().reset(account);
                plugin.getSocialService().notify(account, "notify-2fa-reset", "player", account.getName());
                plugin.getSecurityLog().log("админ сбросил 2fa у " + account.getName());
                plugin.getMessages().send(sender, "admin-2fa-reset", "player", account.getName());
            }
            case "info" -> {
                if (args.length != 2) {
                    plugin.getMessages().send(sender, "usage", "usage", "/stormauth info <ник>");
                    return true;
                }
                PlayerAccount account = plugin.getDataStore().findByName(args[1]);
                if (account == null) {
                    plugin.getMessages().send(sender, "admin-player-not-found", "player", args[1]);
                    return true;
                }
                plugin.getMessages().sendList(sender, "admin-info",
                        "player", account.getName(),
                        "registered", "yes",
                        "twofa", yesNo(account.isTotpEnabled()),
                        "telegram", yesNo(account.getTelegramId() >= 0),
                        "vk", yesNo(account.getVkId() >= 0),
                        "ip", account.getLastIp().isEmpty() ? "-" : account.getLastIp(),
                        "lastlogin", account.getLastLogin() > 0
                                ? DATE_FORMAT.format(Instant.ofEpochMilli(account.getLastLogin())) : "-");
            }
            case "import" -> {
                if (args.length != 3 || !args[1].equalsIgnoreCase("authme")) {
                    plugin.getMessages().send(sender, "usage", "usage", "/stormauth import authme <файл>");
                    return true;
                }
                File file = new File(args[2]);
                if (!file.isAbsolute()) {
                    file = new File(plugin.getDataFolder(), args[2]);
                }
                File target = file;
                plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
                    try {
                        AuthMeImporter.Result result = new AuthMeImporter(plugin).importFrom(target);
                        plugin.getMessages().send(sender, "admin-import",
                                "imported", String.valueOf(result.imported()),
                                "skipped", String.valueOf(result.skipped()),
                                "existing", String.valueOf(result.existing()));
                    } catch (Exception e) {
                        plugin.getMessages().send(sender, "admin-import-fail",
                                "error", String.valueOf(e.getMessage()));
                    }
                });
            }
            default -> plugin.getMessages().send(sender, "admin-usage");
        }
        return true;
    }

    private String yesNo(boolean value) {
        return value ? "yes" : "no";
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return plugin.getMessages().tabFilter(SUBCOMMANDS, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("import")) {
            return plugin.getMessages().tabFilter(List.of("authme"), args[1]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("unregister")
                || args[0].equalsIgnoreCase("changepassword")
                || args[0].equalsIgnoreCase("reset2fa")
                || args[0].equalsIgnoreCase("info"))) {
            return plugin.getMessages().tabFilter(
                    plugin.getServer().getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        }
        return List.of();
    }
}
