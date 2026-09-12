package dev.storm.stormauth.api;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public final class PlayerAuthedEvent extends Event {

    public enum Reason {
        PASSWORD,
        SESSION,
        TOTP,
        FLOODGATE,
        PREMIUM,
        RECOVERY
    }

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Reason reason;

    public PlayerAuthedEvent(Player player, Reason reason) {
        this.player = player;
        this.reason = reason;
    }

    public Player getPlayer() {
        return player;
    }

    public Reason getReason() {
        return reason;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
