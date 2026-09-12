package dev.storm.stormauth.security;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.storm.stormauth.StormAuthPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class AntiVpn {

    private record Verdict(boolean bad, long at) {
    }

    private final StormAuthPlugin plugin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    private final Map<String, Verdict> cache = new ConcurrentHashMap<>();

    public AntiVpn(StormAuthPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("antivpn.enabled", false);
    }

    // вызывать только с async-потока: внутри сеть
    public boolean isBad(String ip) {
        if (isPrivate(ip)) {
            return false;
        }
        long ttl = plugin.getConfig().getLong("antivpn.cache-minutes", 60) * 60_000;
        Verdict cached = cache.get(ip);
        if (cached != null && cached.at + ttl > System.currentTimeMillis()) {
            return cached.bad;
        }
        try {
            boolean bad = fetch(ip);
            cache.put(ip, new Verdict(bad, System.currentTimeMillis()));
            return bad;
        } catch (Exception e) {
            // fail-open по умолчанию: лежащий сервис проверки не должен блокировать вход игроков
            return !plugin.getConfig().getBoolean("antivpn.fail-open", true);
        }
    }

    private boolean fetch(String ip) throws Exception {
        String provider = plugin.getConfig().getString("antivpn.provider", "ip-api");
        boolean blockHosting = plugin.getConfig().getBoolean("antivpn.block-hosting", false);
        if (provider.equalsIgnoreCase("proxycheck")) {
            String key = plugin.getConfig().getString("antivpn.proxycheck-key", "");
            JsonObject json = get("https://proxycheck.io/v2/" + ip + "?key=" + key + "&vpn=1");
            JsonObject entry = json.has(ip) && json.get(ip).isJsonObject() ? json.getAsJsonObject(ip) : null;
            if (entry == null) {
                throw new IllegalStateException("proxycheck: нет данных по ip");
            }
            boolean proxy = entry.has("proxy") && "yes".equalsIgnoreCase(entry.get("proxy").getAsString());
            boolean hosting = entry.has("type") && entry.get("type").getAsString().toLowerCase().contains("hosting");
            return proxy || (blockHosting && hosting);
        }
        JsonObject json = get("http://ip-api.com/json/" + ip + "?fields=status,proxy,hosting");
        if (!json.has("status") || !"success".equals(json.get("status").getAsString())) {
            throw new IllegalStateException("ip-api: неуспех");
        }
        boolean proxy = json.has("proxy") && json.get("proxy").getAsBoolean();
        boolean hosting = json.has("hosting") && json.get("hosting").getAsBoolean();
        return proxy || (blockHosting && hosting);
    }

    private JsonObject get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(4)).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("http " + response.statusCode());
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    // приватные адреса наружным базам не отправляем
    private boolean isPrivate(String ip) {
        if (ip.equals("127.0.0.1") || ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1")) {
            return true;
        }
        if (ip.startsWith("10.") || ip.startsWith("192.168.") || ip.startsWith("169.254.")) {
            return true;
        }
        if (ip.startsWith("172.")) {
            String[] parts = ip.split("\\.");
            if (parts.length > 1) {
                try {
                    int second = Integer.parseInt(parts[1]);
                    return second >= 16 && second <= 31;
                } catch (NumberFormatException ignored) {
                    return false;
                }
            }
        }
        return false;
    }
}
