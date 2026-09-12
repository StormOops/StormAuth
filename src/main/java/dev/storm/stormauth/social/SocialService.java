package dev.storm.stormauth.social;

import dev.storm.stormauth.StormAuthPlugin;
import dev.storm.stormauth.api.PlayerLinkedEvent;
import dev.storm.stormauth.auth.PlayerAccount;
import org.bukkit.entity.Player;

import java.security.SecureRandom;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SocialService {

    interface Bot {
        void send(long chatId, String text);

        void shutdown();
    }

    public enum RecoveryCheck {
        OK,
        WRONG,
        BURNED,
        NONE
    }

    private record LinkRequest(UUID uuid, SocialPlatform platform, long expiresAt) {
    }

    private record RecoveryRequest(String code, int attemptsLeft, long expiresAt) {
    }

    private record AttemptWindow(int count, long windowStart) {
    }

    private static final long CODE_TTL = 5 * 60 * 1000L;
    private static final int CHAT_ATTEMPTS = 10;
    private static final long CHAT_WINDOW = 10 * 60 * 1000L;

    private final StormAuthPlugin plugin;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, LinkRequest> linkRequests = new ConcurrentHashMap<>();
    private final Map<UUID, RecoveryRequest> recoveryRequests = new ConcurrentHashMap<>();
    private final Map<String, AttemptWindow> chatAttempts = new ConcurrentHashMap<>();
    private final Map<SocialPlatform, Bot> bots = new EnumMap<>(SocialPlatform.class);

    public SocialService(StormAuthPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (plugin.getConfig().getBoolean("telegram.enabled", false)) {
            String token = plugin.getConfig().getString("telegram.bot-token", "");
            if (!token.isBlank()) {
                TelegramBot bot = new TelegramBot(plugin, token,
                        (chatId, text) -> onBotMessage(SocialPlatform.TELEGRAM, chatId, text));
                bots.put(SocialPlatform.TELEGRAM, bot);
                bot.start();
                plugin.getLogger().info("telegram-бот запущен");
            }
        }
        if (plugin.getConfig().getBoolean("vk.enabled", false)) {
            String token = plugin.getConfig().getString("vk.access-token", "");
            long groupId = plugin.getConfig().getLong("vk.group-id");
            if (!token.isBlank() && groupId > 0) {
                VkBot bot = new VkBot(plugin, token, groupId,
                        (chatId, text) -> onBotMessage(SocialPlatform.VK, chatId, text));
                bots.put(SocialPlatform.VK, bot);
                bot.start();
                plugin.getLogger().info("vk-бот запущен");
            }
        }
    }

    public void restart() {
        shutdown();
        start();
    }

    public void shutdown() {
        for (Bot bot : bots.values()) {
            bot.shutdown();
        }
        bots.clear();
    }

    public boolean isActive(SocialPlatform platform) {
        return bots.containsKey(platform);
    }

    public void createLinkCode(Player player, SocialPlatform platform) {
        PlayerAccount account = plugin.getDataStore().find(player.getUniqueId(), player.getName());
        if (account == null) {
            plugin.getMessages().send(player, "not-registered");
            return;
        }
        if (!isActive(platform)) {
            plugin.getMessages().send(player, "platform-disabled", "platform", platform.id());
            return;
        }
        long existing = platform == SocialPlatform.TELEGRAM ? account.getTelegramId() : account.getVkId();
        if (existing >= 0) {
            plugin.getMessages().send(player, "link-already", "platform", platform.id());
            return;
        }
        long now = System.currentTimeMillis();
        linkRequests.values().removeIf(request -> request.expiresAt() < now);
        String code = code();
        linkRequests.put(code, new LinkRequest(player.getUniqueId(), platform, now + CODE_TTL));
        plugin.getMessages().send(player, "link-code", "platform", platform.id(), "code", code);
    }

    public void unlink(Player player, SocialPlatform platform) {
        PlayerAccount account = plugin.getDataStore().find(player.getUniqueId(), player.getName());
        if (account == null) {
            plugin.getMessages().send(player, "not-registered");
            return;
        }
        long linked = platform == SocialPlatform.TELEGRAM ? account.getTelegramId() : account.getVkId();
        if (linked < 0) {
            plugin.getMessages().send(player, "link-not-linked", "platform", platform.id());
            return;
        }
        if (platform == SocialPlatform.TELEGRAM) {
            account.setTelegramId(-1);
        } else {
            account.setVkId(-1);
        }
        plugin.getDataStore().saveAsync(account);
        plugin.getMessages().send(player, "unlink-success", "platform", platform.id());
        plugin.getSecurityLog().log(account.getName() + " отвязал " + platform.id());
        notify(account, "notify-unlinked", "platform", platform.id());
    }

    public void startRecovery(Player player) {
        PlayerAccount account = plugin.getDataStore().find(player.getUniqueId(), player.getName());
        if (account == null) {
            plugin.getMessages().send(player, "not-registered");
            return;
        }
        if (!account.hasSocial()) {
            plugin.getMessages().send(player, "recovery-none");
            return;
        }
        String code = code();
        int attempts = plugin.getConfig().getInt("max-code-attempts", 5);
        recoveryRequests.put(player.getUniqueId(),
                new RecoveryRequest(code, attempts, System.currentTimeMillis() + CODE_TTL));
        notify(account, "bot-recovery-code", "player", account.getName(), "code", code);
        plugin.getMessages().send(player, "recovery-sent");
        plugin.getSecurityLog().log(account.getName() + " запросил сброс пароля");
    }

    public RecoveryCheck checkRecoveryCode(UUID uuid, String code) {
        RecoveryRequest request = recoveryRequests.get(uuid);
        if (request == null || request.expiresAt() < System.currentTimeMillis()) {
            recoveryRequests.remove(uuid);
            return RecoveryCheck.NONE;
        }
        if (request.code().equals(code)) {
            recoveryRequests.remove(uuid);
            return RecoveryCheck.OK;
        }
        int left = request.attemptsLeft() - 1;
        if (left <= 0) {
            recoveryRequests.remove(uuid);
            return RecoveryCheck.BURNED;
        }
        recoveryRequests.put(uuid, new RecoveryRequest(request.code(), left, request.expiresAt()));
        return RecoveryCheck.WRONG;
    }

    public int recoveryAttemptsLeft(UUID uuid) {
        RecoveryRequest request = recoveryRequests.get(uuid);
        return request == null ? 0 : request.attemptsLeft();
    }

    public void notify(PlayerAccount account, String key, String... placeholders) {
        String text = plugin.getMessages().plain(key, null, placeholders);
        Bot telegram = bots.get(SocialPlatform.TELEGRAM);
        if (telegram != null && account.getTelegramId() >= 0) {
            telegram.send(account.getTelegramId(), text);
        }
        Bot vk = bots.get(SocialPlatform.VK);
        if (vk != null && account.getVkId() >= 0) {
            vk.send(account.getVkId(), text);
        }
    }

    private void onBotMessage(SocialPlatform platform, long chatId, String text) {
        String code = text.trim();
        // telegram шлет "/start <код>" по deep link - принимаем и такой вид
        if (code.startsWith("/start")) {
            code = code.substring(6).trim();
        }
        if (!code.matches("\\d{6}")) {
            return;
        }
        if (!chatAllowed(platform, chatId)) {
            send(platform, chatId, plugin.getMessages().plain("bot-throttled", null));
            return;
        }
        LinkRequest request = linkRequests.get(code);
        if (request == null || request.expiresAt() < System.currentTimeMillis()
                || request.platform() != platform) {
            linkRequests.remove(code);
            registerChatAttempt(platform, chatId);
            send(platform, chatId, plugin.getMessages().plain("bot-wrong-code", null));
            return;
        }
        PlayerAccount account = plugin.getDataStore().find(request.uuid(), "");
        linkRequests.remove(code);
        if (account == null) {
            send(platform, chatId, plugin.getMessages().plain("bot-no-account", null));
            return;
        }
        if (platform == SocialPlatform.TELEGRAM) {
            account.setTelegramId(chatId);
        } else {
            account.setVkId(chatId);
        }
        plugin.getDataStore().saveAsync(account);
        send(platform, chatId, plugin.getMessages().plain("bot-linked", null, "player", account.getName()));
        Player player = plugin.getServer().getPlayer(request.uuid());
        if (player != null) {
            player.getScheduler().run(plugin, task ->
                    plugin.getMessages().send(player, "link-success", "platform", platform.id()), null);
        }
        plugin.getSecurityLog().log(account.getName() + " привязал " + platform.id());
        notify(account, "notify-linked", "platform", platform.id());
        plugin.getServer().getGlobalRegionScheduler().run(plugin, task ->
                plugin.getServer().getPluginManager().callEvent(
                        new PlayerLinkedEvent(account.getUuid(), account.getName(), platform.id())));
    }

    private boolean chatAllowed(SocialPlatform platform, long chatId) {
        AttemptWindow window = chatAttempts.get(platform.id() + ":" + chatId);
        return window == null
                || System.currentTimeMillis() - window.windowStart() > CHAT_WINDOW
                || window.count() < CHAT_ATTEMPTS;
    }

    private void registerChatAttempt(SocialPlatform platform, long chatId) {
        chatAttempts.compute(platform.id() + ":" + chatId, (key, window) -> {
            long now = System.currentTimeMillis();
            if (window == null || now - window.windowStart() > CHAT_WINDOW) {
                return new AttemptWindow(1, now);
            }
            return new AttemptWindow(window.count() + 1, window.windowStart());
        });
    }

    private void send(SocialPlatform platform, long chatId, String text) {
        Bot bot = bots.get(platform);
        if (bot != null) {
            bot.send(chatId, text);
        }
    }

    private String code() {
        return String.format("%06d", random.nextInt(1_000_000));
    }
}
