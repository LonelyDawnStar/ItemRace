package kr.minq.itemrace;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public final class ItemRacePlugin extends JavaPlugin implements CommandExecutor {

    private final Random random = new Random();
    private final Map<UUID, Integer> scores = new HashMap<>();
    private final Map<UUID, Integer> roundBaselines = new HashMap<>();
    private final Set<UUID> participants = new HashSet<>();

    private List<Material> selectableMaterials;
    private boolean running;
    private boolean resolvingRound;
    private Material currentTarget;
    private BukkitTask inventoryScanner;
    private BossBar targetBossBar;

    @Override
    public void onEnable() {
        selectableMaterials = Arrays.stream(Material.values())
                .filter(Material::isItem)
                .filter(material -> !material.isAir())
                .filter(material -> !material.name().startsWith("LEGACY_"))
                .toList();

        PluginCommand command = getCommand("itemrace");
        if (command == null) {
            getLogger().severe("plugin.yml에서 itemrace 명령어를 찾지 못했습니다.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        command.setExecutor(this);
        inventoryScanner = Bukkit.getScheduler().runTaskTimer(this, this::scanInventories, 5L, 5L);
        getLogger().info("ItemRace 0.4.0 enabled successfully. Selectable items: " + selectableMaterials.size());
    }

    @Override
    public void onDisable() {
        if (inventoryScanner != null) inventoryScanner.cancel();
        clearGameUi();
        running = false;
        resolvingRound = false;
        currentTarget = null;
        scores.clear();
        roundBaselines.clear();
        participants.clear();
        getLogger().info("ItemRace disabled.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendUsage(sender, label);
            return true;
        }

        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "start" -> startGame(sender);
            case "stop" -> stopGame(sender);
            case "status" -> showStatus(sender);
            case "score", "scores" -> showScores(sender);
            default -> {
                sendUsage(sender, label);
                yield true;
            }
        };
    }

    private boolean startGame(CommandSender sender) {
        if (running) {
            sender.sendMessage(ChatColor.RED + "이미 ItemRace가 진행 중입니다.");
            return true;
        }
        if (selectableMaterials.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "출제 가능한 아이템이 없습니다.");
            return true;
        }
        if (Bukkit.getOnlinePlayers().isEmpty()) {
            sender.sendMessage(ChatColor.RED + "참가할 온라인 플레이어가 없습니다.");
            return true;
        }

        running = true;
        resolvingRound = false;
        scores.clear();
        participants.clear();

        for (Player player : Bukkit.getOnlinePlayers()) {
            participants.add(player.getUniqueId());
            scores.put(player.getUniqueId(), 0);
        }

        chooseNextTarget();
        createGameUi();
        updateGameUi();

        Bukkit.broadcastMessage(ChatColor.GOLD + "[ItemRace] " + ChatColor.YELLOW + "게임이 시작되었습니다!");
        broadcastTarget("첫 목표");
        return true;
    }

    private boolean stopGame(CommandSender sender) {
        if (!running) {
            sender.sendMessage(ChatColor.RED + "현재 진행 중인 ItemRace가 없습니다.");
            return true;
        }

        running = false;
        resolvingRound = false;
        currentTarget = null;
        roundBaselines.clear();
        clearGameUi();
        Bukkit.broadcastMessage(ChatColor.GOLD + "[ItemRace] " + ChatColor.RED + "게임이 종료되었습니다.");
        broadcastScores();
        participants.clear();
        return true;
    }

    private boolean showStatus(CommandSender sender) {
        if (!running || currentTarget == null) {
            sender.sendMessage(ChatColor.YELLOW + "ItemRace 상태: " + ChatColor.RED + "중지됨");
            return true;
        }

        sender.sendMessage(ChatColor.YELLOW + "ItemRace 상태: " + ChatColor.GREEN + "진행 중");
        sender.sendMessage(ChatColor.YELLOW + "현재 목표: " + ChatColor.AQUA + formatMaterialName(currentTarget));
        return true;
    }

    private boolean showScores(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "===== ItemRace 점수 =====");
        if (scores.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "아직 참가자나 점수가 없습니다.");
            return true;
        }

        sortedScores().forEach(entry -> sender.sendMessage(
                ChatColor.YELLOW + playerName(entry.getKey())
                        + ChatColor.WHITE + ": "
                        + ChatColor.AQUA + entry.getValue() + "점"
        ));
        return true;
    }

    private void scanInventories() {
        if (!running || resolvingRound || currentTarget == null) return;

        for (UUID uuid : participants) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) continue;

            int now = countMaterial(player, currentTarget);
            int baseline = roundBaselines.getOrDefault(uuid, 0);
            if (now > baseline) {
                completeRound(player);
                return;
            }
        }
    }

    private void completeRound(Player winner) {
        resolvingRound = true;
        int score = scores.merge(winner.getUniqueId(), 1, Integer::sum);
        String solvedName = formatMaterialName(currentTarget);
        updateScoreboards();

        Bukkit.broadcastMessage(ChatColor.GOLD + "[ItemRace] " + ChatColor.GREEN + winner.getName()
                + ChatColor.YELLOW + "님이 " + ChatColor.AQUA + solvedName
                + ChatColor.YELLOW + "을(를) 획득했습니다!");
        Bukkit.broadcastMessage(ChatColor.GOLD + "[ItemRace] " + ChatColor.WHITE + winner.getName()
                + "의 현재 점수: " + ChatColor.AQUA + score + "점");

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!running) return;
            chooseNextTarget();
            resolvingRound = false;
            updateGameUi();
            broadcastTarget("다음 목표");
        }, 40L);
    }

    private void chooseNextTarget() {
        Material previous = currentTarget;
        if (selectableMaterials.size() == 1) {
            currentTarget = selectableMaterials.getFirst();
        } else {
            do {
                currentTarget = selectableMaterials.get(random.nextInt(selectableMaterials.size()));
            } while (currentTarget == previous);
        }
        captureRoundBaselines();
    }

    private void captureRoundBaselines() {
        roundBaselines.clear();
        for (UUID uuid : participants) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                roundBaselines.put(uuid, countMaterial(player, currentTarget));
            }
        }
    }

    private int countMaterial(Player player, Material material) {
        int total = 0;
        for (var stack : player.getInventory().getContents()) {
            if (stack != null && stack.getType() == material) total += stack.getAmount();
        }
        return total;
    }

    private void createGameUi() {
        targetBossBar = BossBar.bossBar(
                Component.text("현재 목표: " + formatMaterialName(currentTarget)),
                1.0f,
                BossBar.Color.BLUE,
                BossBar.Overlay.PROGRESS
        );

        for (UUID uuid : participants) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                player.showBossBar(targetBossBar);
            }
        }
    }

    private void updateGameUi() {
        if (targetBossBar != null && currentTarget != null) {
            targetBossBar.name(Component.text("현재 목표: " + formatMaterialName(currentTarget)));
        }
        updateScoreboards();
    }

    private void updateScoreboards() {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) return;

        List<Map.Entry<UUID, Integer>> ranking = sortedScores();

        for (UUID viewerId : participants) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer == null || !viewer.isOnline()) continue;

            Scoreboard board = manager.getNewScoreboard();
            Objective objective = board.registerNewObjective(
                    "itemrace",
                    Criteria.DUMMY,
                    Component.text("ItemRace")
            );
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);

            int line = 15;
            objective.getScore(ChatColor.YELLOW + "현재 점수").setScore(line--);
            objective.getScore(ChatColor.GRAY + "──────────").setScore(line--);

            int shown = 0;
            for (Map.Entry<UUID, Integer> entry : ranking) {
                if (shown >= 10 || line <= 0) break;
                String name = playerName(entry.getKey());
                String scoreLine = ChatColor.WHITE + trimName(name, 12)
                        + ChatColor.GRAY + " : "
                        + ChatColor.AQUA + entry.getValue();
                objective.getScore(scoreLine).setScore(line--);
                shown++;
            }

            viewer.setScoreboard(board);
        }
    }

    private void clearGameUi() {
        ScoreboardManager manager = Bukkit.getScoreboardManager();

        for (UUID uuid : participants) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) continue;

            if (targetBossBar != null) {
                player.hideBossBar(targetBossBar);
            }
            if (manager != null) {
                player.setScoreboard(manager.getMainScoreboard());
            }
        }
        targetBossBar = null;
    }

    private List<Map.Entry<UUID, Integer>> sortedScores() {
        List<Map.Entry<UUID, Integer>> ranking = new ArrayList<>(scores.entrySet());
        ranking.sort(
                Comparator.<Map.Entry<UUID, Integer>>comparingInt(Map.Entry::getValue)
                        .reversed()
                        .thenComparing(entry -> playerName(entry.getKey()), String.CASE_INSENSITIVE_ORDER)
        );
        return ranking;
    }

    private String playerName(UUID uuid) {
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name == null ? uuid.toString().substring(0, 8) : name;
    }

    private String trimName(String name, int maxLength) {
        return name.length() <= maxLength ? name : name.substring(0, maxLength);
    }

    private void broadcastTarget(String prefix) {
        Bukkit.broadcastMessage(ChatColor.GOLD + "[ItemRace] " + ChatColor.WHITE + prefix + ": "
                + ChatColor.AQUA + formatMaterialName(currentTarget));
    }

    private void broadcastScores() {
        Bukkit.broadcastMessage(ChatColor.GOLD + "===== ItemRace 최종 점수 =====");
        if (scores.isEmpty()) {
            Bukkit.broadcastMessage(ChatColor.GRAY + "획득 점수가 없습니다.");
            return;
        }

        sortedScores().forEach(entry -> Bukkit.broadcastMessage(
                ChatColor.YELLOW + playerName(entry.getKey())
                        + ChatColor.WHITE + ": "
                        + ChatColor.AQUA + entry.getValue() + "점"
        ));
    }

    private String formatMaterialName(Material material) {
        return material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private void sendUsage(CommandSender sender, String label) {
        sender.sendMessage(ChatColor.GOLD + "ItemRace 0.4.0 명령어");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " start" + ChatColor.WHITE + " - 게임 시작");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " stop" + ChatColor.WHITE + " - 게임 종료");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " status" + ChatColor.WHITE + " - 현재 상태 확인");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " score" + ChatColor.WHITE + " - 점수 확인");
    }
}
