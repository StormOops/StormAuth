package dev.storm.stormauth.social;

public enum SocialPlatform {

    TELEGRAM("telegram"),
    VK("vk");

    private final String id;

    SocialPlatform(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static SocialPlatform byId(String text) {
        for (SocialPlatform platform : values()) {
            if (platform.id.equalsIgnoreCase(text)) {
                return platform;
            }
        }
        return null;
    }
}
