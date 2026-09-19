package dev.storm.stormauth.listener;

import dev.storm.stormauth.StormAuthPlugin;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

import java.util.Set;

public final class AuthGateListener implements Listener {

    private static final Set<String> ALLOWED = Set.of(
            "/login", "/l", "/register", "/reg", "/captcha", "/2fa", "/2fasetup", "/recovery");

    private final StormAuthPlugin plugin;

    public AuthGateListener(StormAuthPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean blocked(Player player) {
        return !plugin.getAuthManager().isAuthed(player.getUniqueId());
    }

    private void remind(Player player) {
        if (plugin.getDataStore().find(player.getUniqueId(), player.getName()) != null) {
            plugin.getMessages().send(player, "must-login");
        } else {
            plugin.getMessages().send(player, "must-register");
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        // поворот головы разрешаем, чтобы игрок читал капчу с рамок: hasChangedPosition
        // отсекает движения мыши до проверки авторизации - мапы не трогаем на каждый пакет
        if (!event.hasChangedPosition()) {
            return;
        }
        if (blocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        if (blocked(event.getPlayer())) {
            event.setCancelled(true);
            remind(event.getPlayer());
        }
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!blocked(event.getPlayer())) {
            return;
        }
        String label = event.getMessage().toLowerCase().split(" ")[0];
        label = label.substring(label.indexOf(':') + 1);
        if (!ALLOWED.contains(label)) {
            event.setCancelled(true);
            remind(event.getPlayer());
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && blocked(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDamageBy(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && blocked(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventory(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && blocked(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (blocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && blocked(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (blocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        // рамки капчи трогать можно, иначе код не собрать
        if (blocked(event.getPlayer())
                && !(event.getRightClicked() instanceof ItemFrame
                && plugin.getCaptchaManager().hasPending(event.getPlayer().getUniqueId()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onSwapHand(PlayerSwapHandItemsEvent event) {
        if (blocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }
}
