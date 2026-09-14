package dev.storm.stormauth.captcha;

import dev.storm.stormauth.StormAuthPlugin;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MinecraftFont;
import org.bukkit.map.MapPalette;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CaptchaManager implements Listener {

    private static final class Pending {
        private final String code;
        private int attemptsLeft;
        private final List<ItemFrame> frames = new ArrayList<>();
        private final List<Location> frameLocations = new ArrayList<>();
        private final Map<Location, Material> replaced = new LinkedHashMap<>();

        private Pending(String code, int attemptsLeft) {
            this.code = code;
            this.attemptsLeft = attemptsLeft;
        }
    }

    private final StormAuthPlugin plugin;
    private final Random random = new Random();
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final Set<UUID> frameIds = ConcurrentHashMap.newKeySet();

    public CaptchaManager(StormAuthPlugin plugin) {
        this.plugin = plugin;
    }

    public void issue(Player player) {
        cleanup(player);
        String code = String.format("%04d", random.nextInt(10_000));
        Pending captcha = new Pending(code, plugin.getConfig().getInt("max-code-attempts", 5));
        pending.put(player.getUniqueId(), captcha);
        if (plugin.getConfig().getBoolean("captcha.debug-code", false)) {
            plugin.getLogger().info("капча для " + player.getName() + ": " + code);
        }
        player.getScheduler().run(plugin, task -> {
            Location eye = player.getEyeLocation();
            BlockFace face = facing(eye.getYaw());
            // при крутом pitch yaw скачет от малейшего движения мыши и стена встает криво
            // или вне поля зрения - подвигаем её по вертикали за взглядом
            int dy = 0;
            if (eye.getPitch() > 55f) {
                dy = -1;
            } else if (eye.getPitch() < -55f) {
                dy = 1;
            }
            Location base = eye.toBlockLocation().add(face.getModX() * 2, dy, face.getModZ() * 2);
            World world = eye.getWorld();
            // карты создаем на глобальном регионе - folia запрещает createMap с потока чужого региона
            plugin.getServer().getGlobalRegionScheduler().run(plugin, task2 -> {
                List<MapView> views = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    MapView view = plugin.getServer().createMap(world);
                    view.addRenderer(new DigitRenderer(code.charAt(i), random.nextLong()));
                    views.add(view);
                }
                place(captcha, base, face, views);
            });
        }, null);
    }

    public void verify(Player player, String input) {
        Pending captcha = pending.get(player.getUniqueId());
        if (captcha == null) {
            plugin.getMessages().send(player, "captcha-none");
            return;
        }
        if (captcha.code.equals(input.trim())) {
            pending.remove(player.getUniqueId());
            cleanupFrames(captcha);
            plugin.getAuthManager().markCaptchaPassed(player);
            plugin.getMessages().send(player, "captcha-success");
            plugin.getMessages().send(player, "must-register");
            return;
        }
        int left = --captcha.attemptsLeft;
        if (left <= 0) {
            pending.remove(player.getUniqueId());
            cleanupFrames(captcha);
            plugin.getMessages().send(player, "captcha-burned");
            issue(player);
            return;
        }
        plugin.getMessages().send(player, "captcha-wrong", "left", String.valueOf(left));
    }

    public boolean hasPending(UUID uuid) {
        return pending.containsKey(uuid);
    }

    public void cleanup(Player player) {
        Pending captcha = pending.remove(player.getUniqueId());
        if (captcha != null) {
            cleanupFrames(captcha);
        }
    }

    public void cleanupAll() {
        // при выключении сервера планировщик задачи уже не исполняет - чистим напрямую,
        // иначе барьеры и рамки капчи так и остаются висеть в мире
        for (Pending captcha : pending.values()) {
            for (ItemFrame frame : captcha.frames) {
                frame.remove();
            }
            for (Map.Entry<Location, Material> entry : captcha.replaced.entrySet()) {
                entry.getKey().getBlock().setType(entry.getValue(), false);
            }
        }
        pending.clear();
    }

    private void place(Pending captcha, Location base, BlockFace face, List<MapView> views) {
        plugin.getServer().getRegionScheduler().run(plugin, base, task -> {
            World world = base.getWorld();
            BlockFace right = rightOf(face);
            for (int row = 0; row < 2; row++) {
                for (int col = 0; col < 2; col++) {
                    Location wall = base.clone().add(right.getModX() * col, 1 - row, right.getModZ() * col);
                    Block wallBlock = world.getBlockAt(wall);
                    if (!wallBlock.getType().isSolid()) {
                        captcha.replaced.put(wall.clone(), wallBlock.getType());
                        wallBlock.setType(Material.BARRIER, false);
                    }
                    Location frameLoc = wall.clone().add(-face.getModX(), 0, -face.getModZ());
                    Block frameBlock = world.getBlockAt(frameLoc);
                    if (!frameBlock.getType().isAir()) {
                        captcha.replaced.put(frameLoc.clone(), frameBlock.getType());
                        frameBlock.setType(Material.AIR, false);
                    }
                    try {
                        ItemFrame frame = world.spawn(frameLoc, ItemFrame.class);
                        frame.setFacingDirection(face.getOppositeFace(), true);
                        ItemStack stack = new ItemStack(Material.FILLED_MAP);
                        MapMeta meta = (MapMeta) stack.getItemMeta();
                        meta.setMapView(views.get(row * 2 + col));
                        stack.setItemMeta(meta);
                        frame.setItem(stack);
                        captcha.frames.add(frame);
                        frameIds.add(frame.getUniqueId());
                        captcha.frameLocations.add(frameLoc);
                    } catch (RuntimeException e) {
                        plugin.getLogger().warning("рамка капчи не встала: " + e.getMessage());
                    }
                }
            }
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onFrameBreak(HangingBreakByEntityEvent event) {
        // свои рамки ломать нельзя: любой залогиненный рядом мог снести чужую капчу
        if (frameIds.contains(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    private void cleanupFrames(Pending captcha) {
        for (int i = 0; i < captcha.frames.size(); i++) {
            ItemFrame frame = captcha.frames.get(i);
            Location location = captcha.frameLocations.get(i);
            frameIds.remove(frame.getUniqueId());
            plugin.getServer().getRegionScheduler().run(plugin, location, task -> {
                if (frame.isValid()) {
                    frame.remove();
                }
            });
        }
        for (Map.Entry<Location, Material> entry : captcha.replaced.entrySet()) {
            plugin.getServer().getRegionScheduler().run(plugin, entry.getKey(),
                    task -> entry.getKey().getBlock().setType(entry.getValue(), false));
        }
    }

    private BlockFace facing(float yaw) {
        return switch (Math.round(yaw / 90f) & 3) {
            case 1 -> BlockFace.WEST;
            case 2 -> BlockFace.NORTH;
            case 3 -> BlockFace.EAST;
            default -> BlockFace.SOUTH;
        };
    }

    private BlockFace rightOf(BlockFace face) {
        return switch (face) {
            case NORTH -> BlockFace.EAST;
            case EAST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            default -> BlockFace.NORTH;
        };
    }

    private static final class DigitRenderer extends MapRenderer {
        private final char digit;
        private final long seed;
        private boolean drawn;

        private DigitRenderer(char digit, long seed) {
            this.digit = digit;
            this.seed = seed;
        }

        @Override
        public void render(MapView view, MapCanvas canvas, Player player) {
            if (drawn) {
                return;
            }
            drawn = true;
            byte background = MapPalette.matchColor(232, 228, 213);
            for (int x = 0; x < 128; x++) {
                for (int y = 0; y < 128; y++) {
                    canvas.setPixel(x, y, background);
                }
            }
            // помеховые линии против тупого ocr
            Random noise = new Random(seed);
            for (int i = 0; i < 7; i++) {
                line(canvas, noise.nextInt(128), noise.nextInt(128), noise.nextInt(128), noise.nextInt(128),
                        MapPalette.matchColor(150 + noise.nextInt(80), 145 + noise.nextInt(80), 135 + noise.nextInt(80)));
            }
            canvas.drawText(56, 58, new MinecraftFont(), String.valueOf(digit));
        }

        private void line(MapCanvas canvas, int x0, int y0, int x1, int y1, byte color) {
            int steps = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
            for (int i = 0; i <= steps; i++) {
                canvas.setPixel(x0 + (x1 - x0) * i / steps, y0 + (y1 - y0) * i / steps, color);
            }
        }
    }
}
