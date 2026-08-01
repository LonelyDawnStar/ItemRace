package kr.minq.itemrace;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BiomeSearchResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class BiomeTeleportManager implements Listener {

    private static final int DEFAULT_COOLDOWN_SECONDS = 300;
    private static final long SPECTATOR_MILLIS = 10_000L;
    private static final long SPECTATOR_TICKS = 20L * 10L;
    private static final int SEARCH_RADIUS = 20_000;

    private final ItemRacePlugin plugin;
    private final Map<UUID, Long> cooldownUntil = new HashMap<>();
    private final Map<UUID, Long> spectatorUntil = new HashMap<>();
    private final Map<UUID, BukkitTask> restoreTasks = new HashMap<>();
    private final Map<UUID, GameMode> previousModes = new HashMap<>();
    private final Map<UUID, BossBar> statusBars = new HashMap<>();

    private boolean enabled;
    private boolean raceActive;
    private int cooldownSeconds = DEFAULT_COOLDOWN_SECONDS;
    private BukkitTask barUpdateTask;

    BiomeTeleportManager(ItemRacePlugin plugin) {
        this.plugin = plugin;
    }

    void register() {
        PluginManager manager = plugin.getServer().getPluginManager();
        manager.registerEvents(this, plugin);
        barUpdateTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updateStatusBars, 0L, 20L);
    }

    boolean toggle(CommandSender sender) {
        if (raceActive) {
            sender.sendMessage(ChatColor.RED + "바이옴 텔레포트는 레이스 시작 전에만 설정할 수 있습니다.");
            return true;
        }
        enabled = !enabled;
        sender.sendMessage(enabled
                ? ChatColor.GREEN + "바이옴 텔레포트를 활성화했습니다. 레이스 중 Shift+F로 메뉴를 엽니다."
                : ChatColor.YELLOW + "바이옴 텔레포트를 비활성화했습니다.");
        return true;
    }

    boolean setCooldown(CommandSender sender, String[] args) {
        if (raceActive) {
            sender.sendMessage(ChatColor.RED + "바이옴 TP 쿨타임은 레이스 시작 전에만 설정할 수 있습니다.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "현재 바이옴 TP 쿨타임: " + formatDuration(cooldownSeconds));
            sender.sendMessage(ChatColor.GRAY + "설정: /ir biometpcooldown <초|reset>");
            return true;
        }
        if (args[1].equalsIgnoreCase("reset")) {
            cooldownSeconds = DEFAULT_COOLDOWN_SECONDS;
            sender.sendMessage(ChatColor.GREEN + "바이옴 TP 쿨타임을 기본값 5분으로 초기화했습니다.");
            return true;
        }
        try {
            int seconds = Integer.parseInt(args[1]);
            if (seconds < 1 || seconds > 86_400) {
                sender.sendMessage(ChatColor.RED + "쿨타임은 1~86400초 사이로 입력하세요.");
                return true;
            }
            cooldownSeconds = seconds;
            sender.sendMessage(ChatColor.GREEN + "바이옴 TP 쿨타임을 " + formatDuration(seconds) + "으로 설정했습니다.");
        } catch (NumberFormatException exception) {
            sender.sendMessage(ChatColor.RED + "사용법: /ir biometpcooldown <초|reset>");
        }
        return true;
    }

    void setRaceActive(boolean active) {
        raceActive = active;
        if (!active) {
            restoreAllPlayers();
            hideStatusBars();
            cooldownUntil.clear();
            spectatorUntil.clear();
        }
    }

    boolean isEnabled() {
        return enabled;
    }

    int getCooldownSeconds() {
        return cooldownSeconds;
    }

    void showStatusBars() {
        if (!enabled || !raceActive) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!plugin.isItemRaceParticipant(player.getUniqueId())) continue;
            BossBar bar = statusBars.computeIfAbsent(player.getUniqueId(), ignored -> createStatusBar());
            player.hideBossBar(bar);
            updateStatusBar(player, bar);
            player.showBossBar(bar);
        }
    }

    void hideStatusBars() {
        for (Map.Entry<UUID, BossBar> entry : statusBars.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) player.hideBossBar(entry.getValue());
        }
    }

    void disable() {
        if (barUpdateTask != null) barUpdateTask.cancel();
        restoreAllPlayers();
        hideStatusBars();
        statusBars.clear();
        cooldownUntil.clear();
        spectatorUntil.clear();
        raceActive = false;
    }

    @EventHandler
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        if (!player.isSneaking()) return;
        if (!enabled || !raceActive || !plugin.isItemRaceParticipant(player.getUniqueId())) return;
        event.setCancelled(true);
        openMenu(player);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof BiomeMenuHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;

        TravelOption option = holder.optionsBySlot().get(event.getRawSlot());
        if (option == null) return;
        player.closeInventory();
        teleportToOption(player, option);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        BukkitTask task = restoreTasks.remove(uuid);
        if (task != null) task.cancel();
        previousModes.remove(uuid);
        spectatorUntil.remove(uuid);
        BossBar bar = statusBars.remove(uuid);
        if (bar != null) event.getPlayer().hideBossBar(bar);
    }

    private void openMenu(Player player) {
        long cooldown = remainingCooldownMillis(player);
        if (cooldown > 0L) {
            int seconds = (int) ((cooldown + 999L) / 1000L);
            player.sendMessage(ChatColor.RED + "바이옴 텔레포트 쿨타임이 " + formatDuration(seconds) + " 남았습니다.");
            return;
        }

        List<TravelOption> options = travelOptions();
        BiomeMenuHolder holder = new BiomeMenuHolder();
        Inventory inventory = Bukkit.createInventory(holder, 54, "바이옴 및 스폰 이동");
        holder.inventory = inventory;

        int slot = 0;
        for (TravelOption option : options) {
            if (slot >= inventory.getSize()) break;
            holder.optionsBySlot.put(slot, option);
            inventory.setItem(slot, createMenuItem(option));
            slot++;
        }

        player.openInventory(inventory);
        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.7f, 1.2f);
    }

    private ItemStack createMenuItem(TravelOption option) {
        ItemStack stack = new ItemStack(option.icon());
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName((option.respawnPoint() ? ChatColor.GREEN : ChatColor.AQUA) + option.displayName());

        List<String> lore = new ArrayList<>();
        if (option.respawnPoint()) {
            lore.add(ChatColor.GRAY + "침대 또는 리스폰 정박기로 설정한 개인 스폰 지점");
        } else {
            lore.add(ChatColor.GRAY + "가장 가까운 해당 바이옴으로 이동");
            lore.add(ChatColor.DARK_GRAY + "차원: " + environmentName(option.environment()));
        }
        lore.add(ChatColor.DARK_GRAY + "쿨타임 " + formatDuration(cooldownSeconds) + " · 이동 후 관전 10초");
        meta.setLore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private void teleportToOption(Player player, TravelOption option) {
        if (!enabled || !raceActive || !plugin.isItemRaceParticipant(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "현재 바이옴 텔레포트를 사용할 수 없습니다.");
            return;
        }
        if (remainingCooldownMillis(player) > 0L) {
            player.sendMessage(ChatColor.RED + "아직 바이옴 텔레포트 쿨타임입니다.");
            return;
        }

        if (option.respawnPoint()) {
            Location respawn = player.getRespawnLocation();
            if (respawn == null) {
                player.sendMessage(ChatColor.RED + "유효한 개인 스폰 지점이 없습니다. 침대나 리스폰 정박기로 먼저 설정하세요.");
                return;
            }
            performTeleport(player, respawn.clone(), "내 스폰 지점");
            return;
        }

        World targetWorld = findWorld(option.environment());
        if (targetWorld == null) {
            player.sendMessage(ChatColor.RED + environmentName(option.environment()) + " 월드를 찾지 못했습니다.");
            return;
        }

        Location origin = createSearchOrigin(player, targetWorld);
        player.sendMessage(ChatColor.YELLOW + option.displayName() + "을(를) 찾는 중입니다. 잠시 멈출 수 있습니다.");

        BiomeSearchResult result = targetWorld.locateNearestBiome(
                origin,
                SEARCH_RADIUS,
                32,
                64,
                option.biome()
        );
        if (result == null) {
            player.sendMessage(ChatColor.RED + "반경 " + SEARCH_RADIUS + "블록 안에서 해당 바이옴을 찾지 못했습니다.");
            return;
        }

        Location target = findSafeLocation(targetWorld, result.getLocation());
        if (target == null) {
            player.sendMessage(ChatColor.RED + "해당 바이옴에서 안전한 이동 위치를 찾지 못했습니다.");
            return;
        }
        performTeleport(player, target, option.displayName());
    }

    private void performTeleport(Player player, Location target, String destinationName) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        // 설정된 값을 그대로 사용한다. 기본 5분 상수로 되돌리지 않는다.
        cooldownUntil.put(uuid, now + cooldownSeconds * 1000L);
        spectatorUntil.put(uuid, now + SPECTATOR_MILLIS);
        previousModes.put(uuid, player.getGameMode());

        BukkitTask old = restoreTasks.remove(uuid);
        if (old != null) old.cancel();

        player.teleport(target);
        player.setGameMode(GameMode.SPECTATOR);
        player.sendMessage(ChatColor.GREEN + destinationName + "으로 이동했습니다. 10초 동안 관전 모드입니다.");
        player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
        updateStatusBars();

        BukkitTask restore = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            restoreTasks.remove(uuid);
            spectatorUntil.remove(uuid);
            GameMode restoreMode = previousModes.remove(uuid);
            Player online = Bukkit.getPlayer(uuid);
            if (online != null && online.isOnline()) {
                online.setGameMode(restoreMode == null ? GameMode.SURVIVAL : restoreMode);
                online.sendMessage(ChatColor.GREEN + "관전 시간이 끝나 원래 게임 모드로 돌아왔습니다.");
            }
            updateStatusBars();
        }, SPECTATOR_TICKS);
        restoreTasks.put(uuid, restore);
    }

    private World findWorld(World.Environment environment) {
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() == environment) return world;
        }
        return null;
    }

    private Location createSearchOrigin(Player player, World targetWorld) {
        Location current = player.getLocation();
        if (current.getWorld() == targetWorld) return current;

        double x = current.getX();
        double z = current.getZ();
        World.Environment currentEnvironment = current.getWorld().getEnvironment();
        World.Environment targetEnvironment = targetWorld.getEnvironment();

        if (currentEnvironment == World.Environment.NORMAL && targetEnvironment == World.Environment.NETHER) {
            x /= 8.0;
            z /= 8.0;
        } else if (currentEnvironment == World.Environment.NETHER && targetEnvironment == World.Environment.NORMAL) {
            x *= 8.0;
            z *= 8.0;
        } else {
            x = targetWorld.getSpawnLocation().getX();
            z = targetWorld.getSpawnLocation().getZ();
        }

        return new Location(targetWorld, x, targetWorld.getSpawnLocation().getY(), z);
    }

    private void updateStatusBars() {
        if (!enabled || !raceActive) return;
        for (Map.Entry<UUID, BossBar> entry : statusBars.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline()) updateStatusBar(player, entry.getValue());
        }
    }

    private void updateStatusBar(Player player, BossBar bar) {
        long now = System.currentTimeMillis();
        long spectatorLeft = Math.max(0L, spectatorUntil.getOrDefault(player.getUniqueId(), 0L) - now);
        if (spectatorLeft > 0L) {
            int seconds = (int) ((spectatorLeft + 999L) / 1000L);
            bar.name(Component.text("바이옴 TP · 관전모드 " + seconds + "초"));
            bar.color(BossBar.Color.GREEN);
            bar.progress(Math.max(0.0f, Math.min(1.0f, spectatorLeft / (float) SPECTATOR_MILLIS)));
            return;
        }

        long cooldownLeft = remainingCooldownMillis(player);
        if (cooldownLeft > 0L) {
            int seconds = (int) ((cooldownLeft + 999L) / 1000L);
            bar.name(Component.text("바이옴 TP · 쿨타임 " + formatDuration(seconds)));
            bar.color(BossBar.Color.PURPLE);
            bar.progress(Math.max(0.0f, Math.min(1.0f, cooldownLeft / (float) (cooldownSeconds * 1000L))));
        } else {
            bar.name(Component.text("바이옴 TP · 사용 가능 (Shift+F)"));
            bar.color(BossBar.Color.BLUE);
            bar.progress(1.0f);
        }
    }

    private BossBar createStatusBar() {
        return BossBar.bossBar(
                Component.text("바이옴 TP · 사용 가능 (Shift+F)"),
                1.0f,
                BossBar.Color.BLUE,
                BossBar.Overlay.PROGRESS
        );
    }

    private void restoreAllPlayers() {
        for (BukkitTask task : restoreTasks.values()) task.cancel();
        restoreTasks.clear();
        for (Map.Entry<UUID, GameMode> entry : previousModes.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline()) player.setGameMode(entry.getValue());
        }
        previousModes.clear();
    }

    private Location findSafeLocation(World world, Location found) {
        int x = found.getBlockX();
        int z = found.getBlockZ();

        if (world.getEnvironment() == World.Environment.NORMAL) {
            int y = world.getHighestBlockYAt(x, z) + 1;
            Location location = new Location(world, x + 0.5, y, z + 0.5);
            if (isSafe(location)) return location;

            for (int radius = 1; radius <= 8; radius++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        int nearbyY = world.getHighestBlockYAt(x + dx, z + dz) + 1;
                        Location nearby = new Location(world, x + dx + 0.5, nearbyY, z + dz + 0.5);
                        if (isSafe(nearby)) return nearby;
                    }
                }
            }
            return null;
        }

        int centerY = Math.max(world.getMinHeight() + 2, Math.min(world.getMaxHeight() - 3, found.getBlockY()));
        for (int distance = 0; distance < world.getMaxHeight(); distance++) {
            int[] candidates = {centerY + distance, centerY - distance};
            for (int y : candidates) {
                if (y <= world.getMinHeight() + 1 || y >= world.getMaxHeight() - 2) continue;
                Location location = new Location(world, x + 0.5, y, z + 0.5);
                if (isSafe(location)) return location;
            }
        }
        return null;
    }

    private boolean isSafe(Location location) {
        Block feet = location.getBlock();
        Block head = feet.getRelative(0, 1, 0);
        Block ground = feet.getRelative(0, -1, 0);
        return feet.isPassable()
                && head.isPassable()
                && ground.getType().isSolid()
                && ground.getType() != Material.MAGMA_BLOCK;
    }

    private long remainingCooldownMillis(Player player) {
        return Math.max(0L, cooldownUntil.getOrDefault(player.getUniqueId(), 0L) - System.currentTimeMillis());
    }

    private String formatDuration(int totalSeconds) {
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return minutes > 0 ? minutes + "분 " + seconds + "초" : seconds + "초";
    }

    private String environmentName(World.Environment environment) {
        return environment == World.Environment.NETHER ? "네더" : "오버월드";
    }

    private List<TravelOption> travelOptions() {
        return List.of(
                new TravelOption("내 스폰 지점", null, Material.RED_BED, null, true),

                new TravelOption("참나무 숲", Biome.FOREST, Material.OAK_SAPLING, World.Environment.NORMAL, false),
                new TravelOption("자작나무 숲", Biome.BIRCH_FOREST, Material.BIRCH_SAPLING, World.Environment.NORMAL, false),
                new TravelOption("정글나무 숲", Biome.JUNGLE, Material.JUNGLE_SAPLING, World.Environment.NORMAL, false),
                new TravelOption("가문비나무 숲", Biome.TAIGA, Material.SPRUCE_SAPLING, World.Environment.NORMAL, false),
                new TravelOption("아카시아 지역", Biome.SAVANNA, Material.ACACIA_SAPLING, World.Environment.NORMAL, false),
                new TravelOption("사막 지역", Biome.DESERT, Material.CACTUS, World.Environment.NORMAL, false),
                new TravelOption("메사 지역", Biome.BADLANDS, Material.RED_SAND, World.Environment.NORMAL, false),
                new TravelOption("짙은 참나무 지역", Biome.DARK_FOREST, Material.DARK_OAK_SAPLING, World.Environment.NORMAL, false),
                new TravelOption("맹그로브나무 지역", Biome.MANGROVE_SWAMP, Material.MANGROVE_PROPAGULE, World.Environment.NORMAL, false),
                new TravelOption("벚꽃나무 지역", Biome.CHERRY_GROVE, Material.CHERRY_SAPLING, World.Environment.NORMAL, false),
                new TravelOption("평원", Biome.PLAINS, Material.GRASS_BLOCK, World.Environment.NORMAL, false),
                new TravelOption("창백한 나무 지역", Biome.PALE_GARDEN, Material.PALE_OAK_SAPLING, World.Environment.NORMAL, false),
                new TravelOption("눈 덮인 평원", Biome.SNOWY_PLAINS, Material.SNOW_BLOCK, World.Environment.NORMAL, false),
                new TravelOption("얼음 첨탑 지역", Biome.ICE_SPIKES, Material.PACKED_ICE, World.Environment.NORMAL, false),

                new TravelOption("네더 황무지", Biome.NETHER_WASTES, Material.NETHERRACK, World.Environment.NETHER, false),
                new TravelOption("진홍빛 숲", Biome.CRIMSON_FOREST, Material.CRIMSON_NYLIUM, World.Environment.NETHER, false),
                new TravelOption("뒤틀린 숲", Biome.WARPED_FOREST, Material.WARPED_NYLIUM, World.Environment.NETHER, false),
                new TravelOption("영혼 모래 골짜기", Biome.SOUL_SAND_VALLEY, Material.SOUL_SAND, World.Environment.NETHER, false),
                new TravelOption("현무암 삼각주", Biome.BASALT_DELTAS, Material.BASALT, World.Environment.NETHER, false)
        );
    }

    private record TravelOption(
            String displayName,
            Biome biome,
            Material icon,
            World.Environment environment,
            boolean respawnPoint
    ) {
    }

    private static final class BiomeMenuHolder implements InventoryHolder {
        private final Map<Integer, TravelOption> optionsBySlot = new HashMap<>();
        private Inventory inventory;

        Map<Integer, TravelOption> optionsBySlot() {
            return optionsBySlot;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
