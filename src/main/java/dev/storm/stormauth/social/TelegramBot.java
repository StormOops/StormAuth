package dev.storm.stormauth.social;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.storm.stormauth.StormAuthPlugin;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.BiConsumer;

public final class TelegramBot implements SocialService.Bot {

    private final StormAuthPlugin plugin;
    private final String token;
    private final BiConsumer<Long, String> onMessage;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private volatile boolean running;
    private Thread thread;

    public TelegramBot(StormAuthPlugin plugin, String token, BiConsumer<Long, String> onMessage) {
        this.plugin = plugin;
        this.token = token;
        this.onMessage = onMessage;
    }

    public void start() {
        running = true;
        // своя нить: long-poll держит один запрос по 25 секунд, вебхук и открытый порт не нужны
        thread = new Thread(this::poll, "stormauth-telegram");
        thread.setDaemon(true);
        thread.start();
    }

    private void poll() {
        long offset = 0;
        while (running) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.telegram.org/bot" + token
                                + "/getUpdates?timeout=25&offset=" + offset))
                        .timeout(Duration.ofSeconds(35))
                        .GET()
                        .build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                if (!json.has("ok") || !json.get("ok").getAsBoolean()) {
                    sleep(5000);
                    continue;
                }
                for (JsonElement element : json.getAsJsonArray("result")) {
                    JsonObject update = element.getAsJsonObject();
                    offset = update.get("update_id").getAsLong() + 1;
                    if (!update.has("message")) {
                        continue;
                    }
                    JsonObject message = update.getAsJsonObject("message");
                    if (!message.has("text") || !message.has("chat")) {
                        continue;
                    }
                    long chatId = message.getAsJsonObject("chat").get("id").getAsLong();
                    onMessage.accept(chatId, message.get("text").getAsString());
                }
            } catch (Exception e) {
                if (running) {
                    plugin.getLogger().warning("telegram long-poll: " + e.getMessage());
                    sleep(5000);
                }
            }
        }
    }

    @Override
    public void send(long chatId, String text) {
        try {
            String body = "chat_id=" + chatId + "&text=" + URLEncoder.encode(text, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(
                    URI.create("https://api.telegram.org/bot" + token + "/sendMessage"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            http.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            plugin.getLogger().warning("telegram send: " + e.getMessage());
        }
    }

    @Override
    public void shutdown() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
