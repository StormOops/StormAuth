package dev.storm.stormauth.security;

import dev.storm.stormauth.StormAuthPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// совпадение ника не доказывает владение аккаунтом: на оффлайн-сервере ник пишется руками.
// поэтому авто-вход по умолчанию требует тот же ip, а эта проверка только вспомогательная
public final class PremiumCheck {

    private record Cached(boolean premium, long at) {
    }

    private static final long TTL = 12 * 60 * 60 * 1000L;

    private final StormAuthPlugin plugin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public PremiumCheck(StormAuthPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("premium-autologin.enabled", false);
    }

    public boolean isPremium(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        Cached cached = cache.get(key);
        if (cached != null && cached.at + TTL > System.currentTimeMillis()) {
            return cached.premium;
        }
        boolean premium = fetch(name);
        cache.put(key, new Cached(premium, System.currentTimeMillis()));
        return premium;
    }

    private boolean fetch(String name) {
        try {
            HttpRequest request = HttpRequest.newBuilder(
                    URI.create("https://api.mojang.com/users/profiles/minecraft/" + name))
                    .timeout(Duration.ofSeconds(4))
                    .GET()
                    .build();
            return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (Exception e) {
            // api недоступен - считаем ник пиратским: авто-вход с fail-open был бы дырой
            return false;
        }
    }
}
