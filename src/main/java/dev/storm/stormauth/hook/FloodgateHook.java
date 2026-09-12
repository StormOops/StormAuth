package dev.storm.stormauth.hook;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Logger;

public final class FloodgateHook {

    private final Object api;
    private final Method isFloodgatePlayer;

    private FloodgateHook(Object api, Method isFloodgatePlayer) {
        this.api = api;
        this.isFloodgatePlayer = isFloodgatePlayer;
    }

    public static FloodgateHook create(Logger logger) {
        try {
            Class<?> floodgate = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object api = floodgate.getMethod("getInstance").invoke(null);
            Method method = floodgate.getMethod("isFloodgatePlayer", UUID.class);
            logger.info("floodgate найден, bedrock-игроки заходят без пароля");
            return new FloodgateHook(api, method);
        } catch (ReflectiveOperationException e) {
            return new FloodgateHook(null, null);
        }
    }

    public boolean isFloodgate(UUID uuid) {
        if (api == null) {
            return false;
        }
        try {
            return (boolean) isFloodgatePlayer.invoke(api, uuid);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }
}
