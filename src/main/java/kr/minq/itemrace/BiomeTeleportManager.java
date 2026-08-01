package kr.minq.itemrace;

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

    private static final long COOLDOWN_MILLIS = 5L * 60L * 1000L;
    private static final long SPECTATOR_TICKS = 20L * 10L;
    private static final int SEARCH_RADIUS = 12_000;

    private final ItemRacePlugin plugin;
    private final Map<UUID, Long> cooldownUntil = new HashMap<>();
    private final Map<UUID, BukkitTask> restoreTasks = new HashMap<>();
    private final Map<UUID, GameMode> previousModes = new HashMap<>();
    private boolean enabled;
    private boolean raceActive;

    BiomeTeleportManager(ItemRacePlugin plugin) {
        this.plugin = plugin;
    }

    void register() {
        PluginManager manager = plugin.getServer().getPluginManager();
        manager.registerEvents(this, plugin);
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

    void setRaceActive(boolean raceActive) {
        this.raceActive = raceActive;
    }

    boolean isEnabled() {
        return enabled;
    }

    void disable() {
        for (BukkitTask task : restoreTasks.values()) task.cancel();
        restoreTasks.clear();
        previousModes.clear();
        cooldownUntil.clear();
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

        BiomeOption option = holder.optionsBySlot().get(event.getRawSlot());
        if (option == null) return;
        player.closeInventory();
        teleportToBiome(player, option);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        BukkitTask task = restoreTasks.remove(uuid);
        if (task != null) task.cancel();
        previousModes.remove(uuid);
    }

    private void openMenu(Player player) {
        if (remainingCooldownMillis(player) > 0) {
            long seconds = (remainingCooldownMillis(player) + 999L) / 1000L;
            player.sendMessage(ChatColor.RED + "바이옴 텔레포트 쿨타임이 " + seconds + "초 남았습니다.");
            return;
        }

        World.Environment environment = player.getWorld().getEnvironment();
        List<BiomeOption> options;
        String title;
        if (environment == World.Environment.NORMAL) {
            options = overworldOptions();
            title = "가까운 오버월드 바이옴";
        } else if (environment == World.Environment.NETHER) {
            options = netherOptions();
            title = "가까운 네더 바이옴";
        } else {
            player.sendMessage(ChatColor.RED + "엔드에서는 바이옴 텔레포트를 사용할 수 없습니다.");
            return;
        }

        BiomeMenuHolder holder = new BiomeMenuHolder();
        Inventory inventory = Bukkit.createInventory(holder, 27, title);
        holder.inventory = inventory;

        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
        for (int i = 0; i < options.size() && i < slots.length; i++) {
            BiomeOption option = options.get(i);
            int slot = slots[i];
            holder.optionsBySlot.put(slot, option);
            inventory.setItem(slot, createMenuItem(option));
        }
        player.openInventory(inventory);
        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.7f, 1.2f);
    }

    private ItemStack createMenuItem(BiomeOption option) {
        ItemStack stack = new ItemStack(option.icon());
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + option.displayName());
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "가장 가까운 해당 바이옴으로 이동");
        lore.add(ChatColor.DARK_GRAY + "쿨타임 5분 · 이동 후 관전 10초");
        meta.setLore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private void teleportToBiome(Player player, BiomeOption option) {
        if (!enabled || !raceActive || !plugin.isItemRaceParticipant(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "현재 바이옴 텔레포트를 사용할 수 없습니다.");
            return;
        }
        if (remainingCooldownMillis(player) > 0) {
            player.sendMessage(ChatColor.RED + "아직 바이옴 텔레포트 쿨타임입니다.");
            return;
        }

        World world = player.getWorld();
        if (world.getEnvironment() != option.environment()) {
            player.sendMessage(ChatColor.RED + "현재 차원과 선택한 바이옴의 차원이 다릅니다.");
            return;
        }

        player.sendMessage(ChatColor.YELLOW + option.displayName() + " 바이옴을 찾는 중입니다. 잠시 멈출 수 있습니다.");
        BiomeSearchResult result = world.locateNearestBiome(
                player.getLocation(), SEARCH_RADIUS, 32, 64, option.biome()
        );
        if (result == null) {
            player.sendMessage(ChatColor.RED + "반경 " + SEARCH_RADIUS + "블록 안에서 해당 바이옴을 찾지 못했습니다.");
            return;
        }

        Location target = findSafeLocation(world, result.getLocation());
        if (target == null) {
            player.sendMessage(ChatColor.RED + "해당 바이옴에서 안전한 이동 위치를 찾지 못했습니다.");
            return;
        }

        UUID uuid = player.getUniqueId();
        cooldownUntil.put(uuid, System.currentTimeMillis() + COOLDOWN_MILLIS);
        GameMode previous = player.getGameMode();
        previousModes.put(uuid, previous);
        BukkitTask old = restoreTasks.remove(uuid);
        if (old != null) old.cancel();

        player.teleport(target);
        player.setGameMode(GameMode.SPECTATOR);
        player.sendMessage(ChatColor.GREEN + option.displayName() + " 바이옴으로 이동했습니다. 10초 동안 관전 모드입니다.");
        player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);

        BukkitTask restore = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            restoreTasks.remove(uuid);
            GameMode restoreMode = previousModes.remove(uuid);
            Player online = Bukkit.getPlayer(uuid);
            if (online != null && online.isOnline()) {
                online.setGameMode(restoreMode == null ? GameMode.SURVIVAL : restoreMode);
                online.sendMessage(ChatColor.GREEN + "관전 시간이 끝나 원래 게임 모드로 돌아왔습니다.");
            }
        }, SPECTATOR_TICKS);
        restoreTasks.put(uuid, restore);
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
        return feet.isPassable() && head.isPassable() && ground.getType().isSolid()
                && ground.getType() != Material.MAGMA_BLOCK;
    }

    private long remainingCooldownMillis(Player player) {
        return Math.max(0L, cooldownUntil.getOrDefault(player.getUniqueId(), 0L) - System.currentTimeMillis());
    }

    private List<BiomeOption> overworldOptions() {
        return List.of(
                new BiomeOption("평원", Biome.PLAINS, Material.GRASS_BLOCK, World.Environment.NORMAL),
                new BiomeOption("사막", Biome.DESERT, Material.SAND, World.Environment.NORMAL),
                new BiomeOption("정글", Biome.JUNGLE, Material.JUNGLE_LOG, World.Environment.NORMAL),
                new BiomeOption("대나무 정글", Biome.BAMBOO_JUNGLE, Material.BAMBOO, World.Environment.NORMAL),
                new BiomeOption("늪", Biome.SWAMP, Material.LILY_PAD, World.Environment.NORMAL),
                new BiomeOption("맹그로브 늪", Biome.MANGROVE_SWAMP, Material.MANGROVE_LOG, World.Environment.NORMAL),
                new BiomeOption("벚나무 숲", Biome.CHERRY_GROVE, Material.CHERRY_LOG, World.Environment.NORMAL),
                new BiomeOption("악지", Biome.BADLANDS, Material.RED_SAND, World.Environment.NORMAL),
                new BiomeOption("눈 덮인 평원", Biome.SNOWY_PLAINS, Material.SNOW_BLOCK, World.Environment.NORMAL),
                new BiomeOption("버섯 들판", Biome.MUSHROOM_FIELDS, Material.RED_MUSHROOM_BLOCK, World.Environment.NORMAL),
                new BiomeOption("따뜻한 바다", Biome.WARM_OCEAN, Material.TUBE_CORAL_BLOCK, World.Environment.NORMAL),
                new BiomeOption("깊은 어둠", Biome.DEEP_DARK, Material.SCULK, World.Environment.NORMAL)
        );
    }

    private List<BiomeOption> netherOptions() {
        return List.of(
                new BiomeOption("네더 황무지", Biome.NETHER_WASTES, Material.NETHERRACK, World.Environment.NETHER),
                new BiomeOption("진홍빛 숲", Biome.CRIMSON_FOREST, Material.CRIMSON_STEM, World.Environment.NETHER),
                new BiomeOption("뒤틀린 숲", Biome.WARPED_FOREST, Material.WARPED_STEM, World.Environment.NETHER),
                new BiomeOption("영혼 모래 골짜기", Biome.SOUL_SAND_VALLEY, Material.SOUL_SAND, World.Environment.NETHER),
                new BiomeOption("현무암 삼각주", Biome.BASALT_DELTAS, Material.BASALT, World.Environment.NETHER)
        );
    }

    private record BiomeOption(String displayName, Biome biome, Material icon, World.Environment environment) {
    }

    private static final class BiomeMenuHolder implements InventoryHolder {
        private final Map<Integer, BiomeOption> optionsBySlot = new HashMap<>();
        private Inventory inventory;

        Map<Integer, BiomeOption> optionsBySlot() {
            return optionsBySlot;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
