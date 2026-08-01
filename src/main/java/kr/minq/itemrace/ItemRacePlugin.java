package kr.minq.itemrace;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
import java.util.function.Consumer;

public final class ItemRacePlugin extends JavaPlugin implements CommandExecutor {

    private static final long ROUND_INTRO_TICKS = 60L;
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final Random random = new Random();
    private final Map<UUID, Integer> scores = new HashMap<>();
    private final Map<UUID, Integer> roundBaselines = new HashMap<>();
    private final Set<UUID> participants = new HashSet<>();
    private final Set<Integer> revealedHintIndexes = new HashSet<>();
    private final List<RoundRecord> history = new ArrayList<>();

    private List<Material> selectableMaterials;
    private boolean running;
    private boolean resolvingRound;
    private Material currentTarget;

    // 0은 제한 없음이다.
    private int goalScore;
    private int roundTimeSeconds;
    private int remainingRoundSeconds;

    private BukkitTask inventoryScanner;
    private BukkitTask hintDisplayTask;
    private BukkitTask roundIntroTask;
    private BukkitTask roundTimerTask;
    private BukkitTask countdownTask;
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
        getLogger().info("ItemRace 0.9.0 enabled successfully. Selectable items: " + selectableMaterials.size());
    }

    @Override
    public void onDisable() {
        if (inventoryScanner != null) inventoryScanner.cancel();
        if (hintDisplayTask != null) hintDisplayTask.cancel();
        cancelCountdown();
        cancelRoundIntroTask();
        cancelRoundTimer();
        clearGameUi();
        running = false;
        resolvingRound = false;
        currentTarget = null;
        scores.clear();
        roundBaselines.clear();
        participants.clear();
        revealedHintIndexes.clear();
        history.clear();
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
            case "goal" -> setGoalScore(sender, args);
            case "time", "timer" -> setRoundTime(sender, args);
            case "status" -> showStatus(sender);
            case "score", "scores" -> showScores(sender);
            case "history", "log" -> showHistory(sender);
            case "hint" -> handleHint(sender, args);
            case "reloadpack", "reloadnames" -> reloadNames(sender);
            default -> {
                sendUsage(sender, label);
                yield true;
            }
        };
    }

    private boolean setGoalScore(CommandSender sender, String[] args) {
        if (running) {
            sender.sendMessage(ChatColor.RED + "목표 점수는 레이스 시작 전에만 설정할 수 있습니다.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "현재 목표 점수: " + formatLimit(goalScore, "점"));
            sender.sendMessage(ChatColor.GRAY + "설정: /ir goal <점수|off>");
            return true;
        }
        Integer value = parseLimitValue(args[1]);
        if (value == null) {
            sender.sendMessage(ChatColor.RED + "점수는 1 이상의 정수 또는 off로 입력하세요.");
            return true;
        }
        goalScore = value;
        sender.sendMessage(goalScore == 0
                ? ChatColor.GREEN + "목표 점수를 해제했습니다."
                : ChatColor.GREEN + "목표 점수를 " + goalScore + "점으로 설정했습니다.");
        return true;
    }

    private boolean setRoundTime(CommandSender sender, String[] args) {
        if (running) {
            sender.sendMessage(ChatColor.RED + "제한시간은 레이스 시작 전에만 설정할 수 있습니다.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "현재 문제 제한시간: " + formatLimit(roundTimeSeconds, "초"));
            sender.sendMessage(ChatColor.GRAY + "설정: /ir time <초|off>");
            return true;
        }
        Integer value = parseLimitValue(args[1]);
        if (value == null) {
            sender.sendMessage(ChatColor.RED + "시간은 1 이상의 정수(초) 또는 off로 입력하세요.");
            return true;
        }
        roundTimeSeconds = value;
        sender.sendMessage(roundTimeSeconds == 0
                ? ChatColor.GREEN + "문제 제한시간을 해제했습니다."
                : ChatColor.GREEN + "문제 제한시간을 " + roundTimeSeconds + "초로 설정했습니다.");
        return true;
    }

    private Integer parseLimitValue(String input) {
        if (input.equalsIgnoreCase("off") || input.equalsIgnoreCase("none") || input.equals("0")) return 0;
        try {
            int value = Integer.parseInt(input);
            return value >= 1 ? value : null;
        } catch (NumberFormatException exception) {
            return null;
        }
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
        currentTarget = null;
        scores.clear();
        participants.clear();
        history.clear();

        for (Player player : Bukkit.getOnlinePlayers()) {
            participants.add(player.getUniqueId());
            scores.put(player.getUniqueId(), 0);
        }

        createGameUi();
        updateScoreboards();
        Bukkit.broadcastMessage(ChatColor.GOLD + "[ItemRace] " + ChatColor.YELLOW + "레이스를 준비합니다!");
        Bukkit.broadcastMessage(ChatColor.GOLD + "[ItemRace] " + ChatColor.WHITE + "목표 점수: "
                + ChatColor.AQUA + formatLimit(goalScore, "점") + ChatColor.GRAY + " / 문제 제한시간: "
                + ChatColor.AQUA + formatLimit(roundTimeSeconds, "초"));
        startCountdown();
        return true;
    }

    private void startCountdown() {
        cancelCountdown();
        countdownTask = new BukkitRunnable() {
            private int count = 3;

            @Override
            public void run() {
                if (!running) {
                    cancel();
                    countdownTask = null;
                    return;
                }

                if (count > 0) {
                    Title title = Title.title(
                            Component.text(count),
                            Component.text("ItemRace 시작 준비"),
                            Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(700), Duration.ofMillis(200))
                    );
                    forEachParticipant(player -> {
                        player.showTitle(title);
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f + (3 - count) * 0.15f);
                    });
                    count--;
                    return;
                }

                Title startTitle = Title.title(
                        Component.text("START!"),
                        Component.text("첫 번째 목표가 공개됩니다"),
                        Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(800), Duration.ofMillis(200))
                );
                forEachParticipant(player -> {
                    player.showTitle(startTitle);
                    player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
                });
                cancel();
                countdownTask = null;
                Bukkit.getScheduler().runTaskLater(ItemRacePlugin.this, () -> {
                    if (!running) return;
                    chooseNextTarget();
                    showRoundIntro("첫 번째 목표");
                }, 20L);
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    private boolean stopGame(CommandSender sender) {
        if (!running) {
            sender.sendMessage(ChatColor.RED + "현재 진행 중인 ItemRace가 없습니다.");
            return true;
        }
        finishGame(null, "관리자가 게임을 종료했습니다.");
        return true;
    }

    private boolean skipRound(CommandSender sender) {
        if (!running || currentTarget == null) {
            sender.sendMessage(ChatColor.RED + "현재 진행 중인 문제가 없습니다.");
            return true;
        }
        String displayed = displayTargetName();
        String actual = actualTargetName();
        history.add(new RoundRecord(displayed, actual, "스킵", "-"));
        cancelRoundIntroTask();
        cancelRoundTimer();
        resolvingRound = true;
        hideTargetBossBar();
        playSoundToParticipants(Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f);

        Bukkit.broadcastMessage(ChatColor.GOLD + "[ItemRace] " + ChatColor.YELLOW + "문제를 건너뛰었습니다.");
        Bukkit.broadcastMessage(ChatColor.GOLD + "[ItemRace] " + ChatColor.WHITE + "제시: "
                + ChatColor.AQUA + displayed + ChatColor.GRAY + " / 정답: " + ChatColor.AQUA + actual);

        chooseNextTarget();
        showRoundIntro("스킵 후 새 목표");
        return true;
    }

    private boolean showStatus(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "ItemRace 상태: "
                + (running ? ChatColor.GREEN + "진행 중" : ChatColor.RED + "중지됨"));
        sender.sendMessage(ChatColor.YELLOW + "목표 점수: " + ChatColor.AQUA + formatLimit(goalScore, "점"));
        sender.sendMessage(ChatColor.YELLOW + "문제 제한시간: " + ChatColor.AQUA + formatLimit(roundTimeSeconds, "초"));
        if (running && currentTarget != null) {
            sender.sendMessage(ChatColor.YELLOW + "현재 목표: " + ChatColor.AQUA + displayTargetName());
            sender.sendMessage(ChatColor.YELLOW + "현재 힌트: " + ChatColor.WHITE + renderHint());
            if (roundTimeSeconds > 0 && !resolvingRound) {
                sender.sendMessage(ChatColor.YELLOW + "남은 시간: " + ChatColor.AQUA + remainingRoundSeconds + "초");
            }
        }
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

    private boolean showHistory(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "===== ItemRace 문제 기록 =====");
        if (history.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "아직 완료된 문제가 없습니다.");
            return true;
        }
        int start = Math.max(0, history.size() - 10);
        for (int i = start; i < history.size(); i++) {
            RoundRecord record = history.get(i);
            sender.sendMessage(ChatColor.YELLOW + String.valueOf(i + 1) + ". "
                    + ChatColor.AQUA + record.displayName() + ChatColor.GRAY + " → "
                    + ChatColor.WHITE + record.actualName() + ChatColor.DARK_GRAY + " ["
                    + record.result() + (record.playerName().equals("-") ? "" : ": " + record.playerName()) + "]");
        }
        if (history.size() > 10) sender.sendMessage(ChatColor.GRAY + "최근 10개만 표시했습니다.");
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
        if (running && currentTarget != null) {
            resetHint();
            updateBossBar();
            sendHintToParticipants();
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
        cancelRoundTimer();
        hideTargetBossBar();
        String displayed = displayTargetName();
        String actual = actualTargetName();
        int score = scores.merge(winner.getUniqueId(), 1, Integer::sum);
        history.add(new RoundRecord(displayed, actual, "정답", winner.getName()));
        updateScoreboards();
        playSoundToParticipants(Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);

        Bukkit.broadcastMessage(ChatColor.GOLD + "[ItemRace] " + ChatColor.GREEN + winner.getName()
                + ChatColor.YELLOW + "님이 정답을 맞혔습니다!");
        Bukkit.broadcastMessage(ChatColor.GOLD + "[ItemRace] " + ChatColor.WHITE + "제시: "
                + ChatColor.AQUA + displayed + ChatColor.GRAY + " / 실제 이름: " + ChatColor.AQUA + actual);
        Bukkit.broadcastMessage(ChatColor.GOLD + "[ItemRace] " + ChatColor.WHITE + winner.getName()
                + "의 현재 점수: " + ChatColor.AQUA + score + "점");

        if (goalScore > 0 && score >= goalScore) {
            finishGame(winner, winner.getName() + "님이 목표 점수에 도달했습니다!");
            return;
        }

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!running) return;
            chooseNextTarget();
            showRoundIntro("다음 목표");
        }, 40L);
    }

    private void finishGame(Player winner, String reason) {
        cancelCountdown();
        cancelRoundIntroTask();
        cancelRoundTimer();
        running = false;
        resolvingRound = false;
        currentTarget = null;
        roundBaselines.clear();
        revealedHintIndexes.clear();
        hideTargetBossBar();

        Bukkit.broadcastMessage(ChatColor.GOLD + "[ItemRace] " + ChatColor.RED + reason);
        broadcastScores();
        saveGameLog(reason);

        if (winner != null) {
            Title victoryTitle = Title.title(
                    Component.text("WINNER"),
                    Component.text(winner.getName() + " · " + scores.getOrDefault(winner.getUniqueId(), 0) + "점"),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(3000), Duration.ofMillis(700))
            );
            forEachParticipant(player -> {
                player.showTitle(victoryTitle);
                player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE, 1.0f, 1.0f);
            });
            launchWinnerFireworks(winner);
        }

        Bukkit.getScheduler().runTaskLater(this, () -> {
            clearGameUi();
            participants.clear();
        }, winner == null ? 1L : 80L);
    }

    private void saveGameLog(String reason) {
        Path logs = getDataFolder().toPath().resolve("logs");
        try {
            Files.createDirectories(logs);
            StringBuilder text = new StringBuilder();
            text.append("ItemRace 게임 기록\n");
            text.append("종료 사유: ").append(reason).append("\n");
            text.append("목표 점수: ").append(formatLimit(goalScore, "점")).append("\n");
            text.append("문제 제한시간: ").append(formatLimit(roundTimeSeconds, "초")).append("\n\n");
            for (int i = 0; i < history.size(); i++) {
                RoundRecord record = history.get(i);
                text.append(i + 1).append(". 제시: ").append(record.displayName())
                        .append(" | 정답: ").append(record.actualName())
                        .append(" | 결과: ").append(record.result())
                        .append(" | 플레이어: ").append(record.playerName()).append("\n");
            }
            text.append("\n최종 점수\n");
            for (Map.Entry<UUID, Integer> entry : sortedScores()) {
                text.append(playerName(entry.getKey())).append(": ").append(entry.getValue()).append("점\n");
            }
            Path file = logs.resolve(LocalDateTime.now().format(LOG_TIME) + ".txt");
            Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
            getLogger().info("게임 기록을 저장했습니다: " + file.getFileName());
        } catch (IOException exception) {
            getLogger().warning("게임 기록 저장 실패: " + exception.getMessage());
        }
    }

    private void launchWinnerFireworks(Player winner) {
        for (int i = 0; i < 3; i++) {
            int delay = i * 12;
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!winner.isOnline()) return;
                Firework firework = winner.getWorld().spawn(winner.getLocation().add(0, 1, 0), Firework.class);
                FireworkMeta meta = firework.getFireworkMeta();
                meta.addEffect(FireworkEffect.builder()
                        .with(FireworkEffect.Type.BALL_LARGE)
                        .withColor(Color.AQUA, Color.YELLOW)
                        .withFade(Color.WHITE)
                        .trail(true)
                        .flicker(true)
                        .build());
                meta.setPower(1);
                firework.setFireworkMeta(meta);
            }, delay);
        }
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
        updateBossBar();
    }

    private void showRoundIntro(String subtitleText) {
        cancelRoundIntroTask();
        cancelRoundTimer();
        resolvingRound = true;
        hideTargetBossBar();
        updateBossBar();

        Title title = Title.title(
                Component.text(displayTargetName()),
                Component.text(subtitleText),
                Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2000), Duration.ofMillis(500))
        );
        forEachParticipant(player -> {
            player.showTitle(title);
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.4f);
        });

        roundIntroTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            roundIntroTask = null;
            if (!running || currentTarget == null) return;
            showTargetBossBar();
            resolvingRound = false;
            captureRoundBaselines();
            startRoundTimer();
            updateScoreboards();
        }, ROUND_INTRO_TICKS);
    }

    private void startRoundTimer() {
        cancelRoundTimer();
        if (roundTimeSeconds <= 0) {
            remainingRoundSeconds = 0;
            updateBossBar();
            return;
        }
        remainingRoundSeconds = roundTimeSeconds;
        updateBossBar();
        roundTimerTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!running || resolvingRound || currentTarget == null) return;
            remainingRoundSeconds--;
            updateBossBar();
            updateScoreboards();
            if (remainingRoundSeconds <= 0) handleRoundTimeout();
        }, 20L, 20L);
    }

    private void handleRoundTimeout() {
        if (!running || resolvingRound || currentTarget == null) return;
        resolvingRound = true;
        cancelRoundTimer();
        hideTargetBossBar();
        String displayed = displayTargetName();
        String actual = actualTargetName();
        history.add(new RoundRecord(displayed, actual, "시간 초과", "-"));
        playSoundToParticipants(Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.6f);

        Bukkit.broadcastMessage(ChatColor.GOLD + "[ItemRace] " + ChatColor.RED + "시간 초과!");
        Bukkit.broadcastMessage(ChatColor.GOLD + "[ItemRace] " + ChatColor.WHITE + "제시: "
                + ChatColor.AQUA + displayed + ChatColor.GRAY + " / 정답: " + ChatColor.AQUA + actual);

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!running) return;
            chooseNextTarget();
            showRoundIntro("시간 초과 후 새 목표");
        }, 40L);
    }

    private void cancelCountdown() {
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
    }

    private void cancelRoundIntroTask() {
        if (roundIntroTask != null) {
            roundIntroTask.cancel();
            roundIntroTask = null;
        }
    }

    private void cancelRoundTimer() {
        if (roundTimerTask != null) {
            roundTimerTask.cancel();
            roundTimerTask = null;
        }
    }

    private void captureRoundBaselines() {
        roundBaselines.clear();
        if (currentTarget == null) return;
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
        targetBossBar = BossBar.bossBar(Component.text("ItemRace"), 1.0f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
    }

    private void updateBossBar() {
        if (targetBossBar == null || currentTarget == null) return;
        if (roundTimeSeconds > 0) {
            float progress = Math.max(0.0f, Math.min(1.0f, remainingRoundSeconds / (float) roundTimeSeconds));
            targetBossBar.progress(progress);
            targetBossBar.name(Component.text("현재 목표: " + displayTargetName() + " · " + remainingRoundSeconds + "초"));
            if (progress <= 0.25f) targetBossBar.color(BossBar.Color.RED);
            else if (progress <= 0.5f) targetBossBar.color(BossBar.Color.YELLOW);
            else targetBossBar.color(BossBar.Color.BLUE);
        } else {
            targetBossBar.progress(1.0f);
            targetBossBar.color(BossBar.Color.BLUE);
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
            if (goalScore > 0) objective.getScore(ChatColor.YELLOW + "목표: " + ChatColor.AQUA + goalScore + "점").setScore(line--);
            if (roundTimeSeconds > 0 && running) {
                String time = resolvingRound ? "준비 중" : remainingRoundSeconds + "초";
                objective.getScore(ChatColor.YELLOW + "남은 시간: " + ChatColor.AQUA + time).setScore(line--);
            }
            objective.getScore(ChatColor.GRAY + "──────────").setScore(line--);
            int shown = 0;
            for (Map.Entry<UUID, Integer> entry : ranking) {
                if (shown >= 10 || line <= 0) break;
                objective.getScore(ChatColor.WHITE + trimName(playerName(entry.getKey()), 12)
                        + ChatColor.GRAY + " : " + ChatColor.AQUA + entry.getValue()).setScore(line--);
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
            if (Character.isWhitespace(codePoint)) result.append("   ");
            else {
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

    private void playSoundToParticipants(Sound sound, float volume, float pitch) {
        forEachParticipant(player -> player.playSound(player.getLocation(), sound, volume, pitch));
    }

    private void forEachParticipant(Consumer<Player> action) {
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

    private String formatLimit(int value, String suffix) {
        return value <= 0 ? "없음" : value + suffix;
    }

    private void broadcastScores() {
        Bukkit.broadcastMessage(ChatColor.GOLD + "===== ItemRace 최종 점수 =====");
        if (scores.isEmpty()) {
            Bukkit.broadcastMessage(ChatColor.GRAY + "획득 점수가 없습니다.");
            return;
        }
        int rank = 1;
        for (Map.Entry<UUID, Integer> entry : sortedScores()) {
            String medal = rank == 1 ? "🥇 " : rank == 2 ? "🥈 " : rank == 3 ? "🥉 " : "";
            Bukkit.broadcastMessage(ChatColor.YELLOW + medal + playerName(entry.getKey())
                    + ChatColor.WHITE + ": " + ChatColor.AQUA + entry.getValue() + "점");
            rank++;
        }
    }

    private String displayTargetName() {
        return currentTarget == null ? "" : resourcePackNames.displayName(currentTarget);
    }

    private String actualTargetName() {
        return currentTarget == null ? "" : koreanNames.displayName(currentTarget);
    }

    private void sendUsage(CommandSender sender, String label) {
        sender.sendMessage(ChatColor.GOLD + "ItemRace 0.9.0 명령어");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " goal <점수|off>" + ChatColor.WHITE + " - 시작 전 목표 점수 설정");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " time <초|off>" + ChatColor.WHITE + " - 시작 전 제한시간 설정");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " start" + ChatColor.WHITE + " - 게임 시작");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " stop" + ChatColor.WHITE + " - 게임 종료");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " skip" + ChatColor.WHITE + " - 현재 문제 건너뛰기");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " history" + ChatColor.WHITE + " - 최근 문제 기록");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " status" + ChatColor.WHITE + " - 현재 상태 확인");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " score" + ChatColor.WHITE + " - 점수 확인");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " hint [숫자|reveal|reset]" + ChatColor.WHITE + " - 힌트 관리");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " reloadpack" + ChatColor.WHITE + " - 이름 데이터 다시 읽기");
    }

    private record RoundRecord(String displayName, String actualName, String result, String playerName) {
    }
}
