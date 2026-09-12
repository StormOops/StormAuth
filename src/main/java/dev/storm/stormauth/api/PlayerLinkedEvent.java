package dev.storm.stormauth.api;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

public final class PlayerLinkedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID uuid;
    private final String name;
    private final String platform;

    public PlayerLinkedEvent(UUID uuid, String name, String platform) {
        this.uuid = uuid;
        this.name = name;
        this.platform = platform;
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getName() {
        return name;
    }

    public String getPlatform() {
        return platform;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
