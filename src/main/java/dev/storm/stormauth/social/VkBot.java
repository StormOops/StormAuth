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

public final class VkBot implements SocialService.Bot {

    private final StormAuthPlugin plugin;
    private final String token;
    private final long groupId;
    private final BiConsumer<Long, String> onMessage;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private volatile boolean running;
    private Thread thread;
    private String server;
    private String key;
    private String ts;

    public VkBot(StormAuthPlugin plugin, String token, long groupId, BiConsumer<Long, String> onMessage) {
        this.plugin = plugin;
        this.token = token;
        this.groupId = groupId;
        this.onMessage = onMessage;
    }

    public void start() {
        running = true;
        thread = new Thread(this::poll, "stormauth-vk");
        thread.setDaemon(true);
        thread.start();
    }

    private void poll() {
        while (running) {
            try {
                if (server == null) {
                    fetchServer();
                }
                JsonObject json = get(server + "?act=a_check&key=" + key + "&ts=" + ts + "&wait=25", 35);
                if (json.has("failed")) {
                    if (json.get("failed").getAsInt() == 1 && json.has("ts")) {
                        ts = json.get("ts").getAsString();
                    } else {
                        server = null;
                        sleep(2000);
                    }
                    continue;
                }
                ts = json.get("ts").getAsString();
                for (JsonElement element : json.getAsJsonArray("updates")) {
                    JsonObject update = element.getAsJsonObject();
                    if (!update.has("type") || !"message_new".equals(update.get("type").getAsString())) {
                        continue;
                    }
                    JsonObject message = update.getAsJsonObject("object").getAsJsonObject("message");
                    if (message == null || !message.has("text") || !message.has("from_id")) {
                        continue;
                    }
                    onMessage.accept(message.get("from_id").getAsLong(), message.get("text").getAsString());
                }
            } catch (Exception e) {
                if (running) {
                    plugin.getLogger().warning("vk long-poll: " + e.getMessage());
                    server = null;
                    sleep(5000);
                }
            }
        }
    }

    private void fetchServer() throws Exception {
        JsonObject json = get("https://api.vk.com/method/groups.getLongPollServer?group_id=" + groupId
                + "&access_token=" + token + "&v=5.199", 10);
        if (!json.has("response")) {
            throw new IllegalStateException("vk getLongPollServer: нет response");
        }
        JsonObject response = json.getAsJsonObject("response");
        server = response.get("server").getAsString();
        key = response.get("key").getAsString();
        ts = response.get("ts").getAsString();
    }

    @Override
    public void send(long chatId, String text) {
        try {
            String body = "user_id=" + chatId
                    + "&random_id=" + System.nanoTime()
                    + "&message=" + URLEncoder.encode(text, StandardCharsets.UTF_8)
                    + "&access_token=" + token + "&v=5.199";
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.vk.com/method/messages.send"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            http.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            plugin.getLogger().warning("vk send: " + e.getMessage());
        }
    }

    @Override
    public void shutdown() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private JsonObject get(String url, int timeoutSeconds) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
