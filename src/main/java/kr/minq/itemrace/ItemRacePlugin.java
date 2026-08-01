package kr.minq.itemrace;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
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

import java.time.Duration;
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

    private static final long ROUND_INTRO_TICKS = 60L;

    private final Random random = new Random();
    private final Map<UUID, Integer> scores = new HashMap<>();
    private final Map<UUID, Integer> roundBaselines = new HashMap<>();
    private final Set<UUID> participants = new HashSet<>();
    private final Set<Integer> revealedHintIndexes = new HashSet<>();

    private List<Material> selectableMaterials;
    private boolean running;
    private boolean resolvingRound;
    private Material currentTarget;
    private BukkitTask inventoryScanner;
    private BukkitTask hintDisplayTask;
    private BukkitTask roundIntroTask;
    private BossBar targetBossBar;
    private ResourcePackNameManager resourcePackNames;
    private KoreanNameManager koreanNames;

    @Override
    public void onEnable() {
        resourcePackNames = new ResourcePackNameManager(this);
        koreanNames = new KoreanNameManager(this);
        reloadNameData();

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
        hintDisplayTask = Bukkit.getScheduler().runTaskTimer(this, this::sendHintToParticipants, 10L, 10L);
        getLogger().info("ItemRace 0.7.0 enabled successfully. Selectable items: " + selectableMaterials.size());
    }

    @Override
    public void onDisable() {
        if (inventoryScanner != null) inventoryScanner.cancel();
        if (hintDisplayTask != null) hintDisplayTask.cancel();
        cancelRoundIntroTask();
        clearGameUi();
        running = false;
        resolvingRound = false;
        currentTarget = null;
        scores.clear();
        roundBaselines.clear();
        participants.clear();
        revealedHintIndexes.clear();
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
            case "skip", "next" -> skipRound(sender);
            case "status" -> showStatus(sender);
            case "score", "scores" -> showScores(sender);
            case "hint" -> handleHint(sender, args);
            case "reloadpack", "reloadnames" -> reloadNames(sender);
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
        resolvingRound = true;
        scores.clear();
        participants.clear();

        for (Player player : Bukkit.getOnlinePlayers()) {
            participants.add(player.getUniqueId());
            scores.put(player.getUniqueId(), 0);
        }

        chooseNextTarget();
        createGameUi();
        updateScoreboards();

        Bukkit.broadcastMessage(ChatColor.GOLD + "[ItemRace] " + ChatColor.YELLOW + "게임이 시작되었습니다!");
        showRoundIntro("첫 번째 목표");
        return true;
    }

    private boolean stopGame(CommandSender sender) {
        if (!running) {
            sender.sendMessage(ChatColor.RED + "현재 진행 중인 ItemRace가 없습니다.");
            return true;
        }

        cancelRoundIntroTask();
        running = false;
        resolvingRound = false;
        currentTarget = null;
        roundBaselines.clear();
        revealedHintIndexes.clear();
        clearGameUi();
        Bukkit.broadcastMessage(ChatColor.GOLD + "[ItemRace] " + ChatColor.RED + "게임이 종료되었습니다.");
        broadcastScores();
        participants.clear();
        return true;
    }

    private boolean skipRound(CommandSender sender) {
        if (!running || currentTarget == null) {
            sender.sendMessage(ChatColor.RED + "현재 진행 중인 게임이 없습니다.");
            return true;
        }

        String skippedDisplayName = displayTargetName();
        String skippedActualName = actualTargetName();
        cancelRoundIntroTask();
        resolvingRound = true;
        hideTargetBossBar();

        Bukkit.broadcastMessage(ChatColor.GOLD + "[ItemRace] " + ChatColor.YELLOW + "문제를 건너뛰었습니다.");
        Bukkit.broadcastMessage(ChatColor.GOLD + "[ItemRace] " + ChatColor.WHITE + "제시된 이름: "
                + ChatColor.AQUA + skippedDisplayName + ChatColor.GRAY + " / 실제 이름: "
                + ChatColor.AQUA + skippedActualName);

        chooseNextTarget();
        showRoundIntro("스킵 후 새 목표");
        return true;
    }

    private boolean showStatus(CommandSender sender) {
        if (!running || currentTarget == null) {
            sender.sendMessage(ChatColor.YELLOW + "ItemRace 상태: " + ChatColor.RED + "중지됨");
            return true;
        }
        sender.sendMessage(ChatColor.YELLOW + "ItemRace 상태: " + ChatColor.GREEN + "진행 중");
        sender.sendMessage(ChatColor.YELLOW + "현재 목표: " + ChatColor.AQUA + displayTargetName());
        sender.sendMessage(ChatColor.YELLOW + "현재 힌트: " + ChatColor.WHITE + renderHint());
        return true;
    }

    private boolean showScores(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "===== ItemRace 점수 =====");
        if (scores.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "아직 참가자나 점수가 없습니다.");
            return true;
        }
        sortedScores().forEach(entry -> sender.sendMessage(
                ChatColor.YELLOW + playerName(entry.getKey()) + ChatColor.WHITE + ": "
                        + ChatColor.AQUA + entry.getValue() + "점"
        ));
        return true;
    }

    private boolean handleHint(CommandSender sender, String[] args) {
        if (!running || currentTarget == null) {
            sender.sendMessage(ChatColor.RED + "현재 진행 중인 게임이 없습니다.");
            return true;
        }

        if (args.length == 1) {
            revealRandomHintCharacters(1);
        } else {
            String option = args[1].toLowerCase(Locale.ROOT);
            if (option.equals("reveal")) {
                revealAllHintCharacters();
            } else if (option.equals("reset")) {
                resetHint();
            } else {
                try {
                    int count = Integer.parseInt(option);
                    if (count < 1 || count > 100) {
                        sender.sendMessage(ChatColor.RED + "공개할 글자 수는 1~100 사이여야 합니다.");
                        return true;
                    }
                    revealRandomHintCharacters(count);
                } catch (NumberFormatException exception) {
                    sender.sendMessage(ChatColor.RED + "사용법: /itemrace hint [숫자|reveal|reset]");
                    return true;
                }
            }
        }

        sendHintToParticipants();
        sender.sendMessage(ChatColor.GREEN + "힌트: " + ChatColor.WHITE + renderHint());
        return true;
    }

    private boolean reloadNames(CommandSender sender) {
        reloadNameData();
        sender.sendMessage(ChatColor.GREEN + "리소스팩 이름과 한글 이름을 다시 불러왔습니다.");
        sender.sendMessage(ChatColor.GRAY + "리소스팩: " + (resourcePackNames.isLoaded() ? "로드됨" : "없음")
                + ", 한글 이름: " + (koreanNames.isLoaded() ? "로드됨" : "없음"));
        if (running) {
            resetHint();
            updateBossBarName();
            sendHintToParticipants();
            broadcastTarget("현재 목표");
        }
        return true;
    }

    private void reloadNameData() {
        resourcePackNames.load();
        koreanNames.load();
        koreanNames.writeCache(resourcePackNames);
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
        hideTargetBossBar();
        int score = scores.merge(winner.getUniqueId(), 1, Integer::sum);
        String displayedName = displayTargetName();
        String actualName = actualTargetName();
        updateScoreboards();

        Bukkit.broadcastMessage(ChatColor.GOLD + "[ItemRace] " + ChatColor.GREEN + winner.getName()
                + ChatColor.YELLOW + "님이 " + ChatColor.AQUA + displayedName
                + ChatColor.YELLOW + "을(를) 획득했습니다!");
        Bukkit.broadcastMessage(ChatColor.GOLD + "[ItemRace] " + ChatColor.WHITE + "실제 이름: "
                + ChatColor.AQUA + actualName);
        Bukkit.broadcastMessage(ChatColor.GOLD + "[ItemRace] " + ChatColor.WHITE + winner.getName()
                + "의 현재 점수: " + ChatColor.AQUA + score + "점");

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!running) return;
            chooseNextTarget();
            showRoundIntro("다음 목표");
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
        resetHint();
        captureRoundBaselines();
        updateBossBarName();
    }

    private void showRoundIntro(String subtitleText) {
        cancelRoundIntroTask();
        resolvingRound = true;
        hideTargetBossBar();
        updateBossBarName();

        Title title = Title.title(
                Component.text(displayTargetName()),
                Component.text(subtitleText),
                Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2000), Duration.ofMillis(500))
        );

        forEachParticipant(player -> player.showTitle(title));
        broadcastTarget(subtitleText);

        roundIntroTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            roundIntroTask = null;
            if (!running || currentTarget == null) return;
            showTargetBossBar();
            resolvingRound = false;
        }, ROUND_INTRO_TICKS);
    }

    private void cancelRoundIntroTask() {
        if (roundIntroTask != null) {
            roundIntroTask.cancel();
            roundIntroTask = null;
        }
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
                Component.text("현재 목표: " + displayTargetName()),
                1.0f,
                BossBar.Color.BLUE,
                BossBar.Overlay.PROGRESS
        );
    }

    private void updateBossBarName() {
        if (targetBossBar != null && currentTarget != null) {
            targetBossBar.name(Component.text("현재 목표: " + displayTargetName()));
        }
    }

    private void showTargetBossBar() {
        if (targetBossBar == null) return;
        forEachParticipant(player -> player.showBossBar(targetBossBar));
    }

    private void hideTargetBossBar() {
        if (targetBossBar == null) return;
        forEachParticipant(player -> player.hideBossBar(targetBossBar));
    }

    private void updateScoreboards() {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) return;
        List<Map.Entry<UUID, Integer>> ranking = sortedScores();

        for (UUID viewerId : participants) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer == null || !viewer.isOnline()) continue;

            Scoreboard board = manager.getNewScoreboard();
            Objective objective = board.registerNewObjective("itemrace", Criteria.DUMMY, Component.text("ItemRace"));
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);

            int line = 15;
            objective.getScore(ChatColor.YELLOW + "현재 점수").setScore(line--);
            objective.getScore(ChatColor.GRAY + "──────────").setScore(line--);

            int shown = 0;
            for (Map.Entry<UUID, Integer> entry : ranking) {
                if (shown >= 10 || line <= 0) break;
                String scoreLine = ChatColor.WHITE + trimName(playerName(entry.getKey()), 12)
                        + ChatColor.GRAY + " : " + ChatColor.AQUA + entry.getValue();
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
            if (targetBossBar != null) player.hideBossBar(targetBossBar);
            player.clearTitle();
            player.sendActionBar(Component.empty());
            if (manager != null) player.setScoreboard(manager.getMainScoreboard());
        }
        targetBossBar = null;
    }

    private void resetHint() {
        revealedHintIndexes.clear();
    }

    private void revealRandomHintCharacters(int count) {
        int[] codePoints = actualTargetName().codePoints().toArray();
        List<Integer> hidden = new ArrayList<>();
        for (int index = 0; index < codePoints.length; index++) {
            if (!Character.isWhitespace(codePoints[index]) && !revealedHintIndexes.contains(index)) hidden.add(index);
        }
        for (int i = 0; i < count && !hidden.isEmpty(); i++) {
            revealedHintIndexes.add(hidden.remove(random.nextInt(hidden.size())));
        }
    }

    private void revealAllHintCharacters() {
        int[] codePoints = actualTargetName().codePoints().toArray();
        for (int index = 0; index < codePoints.length; index++) {
            if (!Character.isWhitespace(codePoints[index])) revealedHintIndexes.add(index);
        }
    }

    private String renderHint() {
        if (currentTarget == null) return "";
        int[] codePoints = actualTargetName().codePoints().toArray();
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < codePoints.length; index++) {
            int codePoint = codePoints[index];
            if (Character.isWhitespace(codePoint)) {
                result.append("   ");
            } else {
                result.append(revealedHintIndexes.contains(index) ? new String(Character.toChars(codePoint)) : "□");
                result.append(' ');
            }
        }
        return result.toString().stripTrailing();
    }

    private void sendHintToParticipants() {
        if (!running || currentTarget == null) return;
        Component hint = Component.text("힌트: " + renderHint());
        forEachParticipant(player -> player.sendActionBar(hint));
    }

    private void forEachParticipant(java.util.function.Consumer<Player> action) {
        for (UUID uuid : participants) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) action.accept(player);
        }
    }

    private List<Map.Entry<UUID, Integer>> sortedScores() {
        List<Map.Entry<UUID, Integer>> ranking = new ArrayList<>(scores.entrySet());
        ranking.sort(Comparator.<Map.Entry<UUID, Integer>>comparingInt(Map.Entry::getValue)
                .reversed().thenComparing(entry -> playerName(entry.getKey()), String.CASE_INSENSITIVE_ORDER));
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
                + ChatColor.AQUA + displayTargetName());
    }

    private void broadcastScores() {
        Bukkit.broadcastMessage(ChatColor.GOLD + "===== ItemRace 최종 점수 =====");
        if (scores.isEmpty()) {
            Bukkit.broadcastMessage(ChatColor.GRAY + "획득 점수가 없습니다.");
            return;
        }
        sortedScores().forEach(entry -> Bukkit.broadcastMessage(
                ChatColor.YELLOW + playerName(entry.getKey()) + ChatColor.WHITE + ": "
                        + ChatColor.AQUA + entry.getValue() + "점"
        ));
    }

    private String displayTargetName() {
        return currentTarget == null ? "" : resourcePackNames.displayName(currentTarget);
    }

    private String actualTargetName() {
        return currentTarget == null ? "" : koreanNames.displayName(currentTarget);
    }

    private void sendUsage(CommandSender sender, String label) {
        sender.sendMessage(ChatColor.GOLD + "ItemRace 0.7.0 명령어");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " start" + ChatColor.WHITE + " - 게임 시작");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " stop" + ChatColor.WHITE + " - 게임 종료");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " skip" + ChatColor.WHITE + " - 현재 문제 건너뛰기");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " status" + ChatColor.WHITE + " - 현재 상태 확인");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " score" + ChatColor.WHITE + " - 점수 확인");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " hint [숫자|reveal|reset]" + ChatColor.WHITE + " - 힌트 관리");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " reloadpack" + ChatColor.WHITE + " - 이름 데이터 다시 읽기");
    }
}
