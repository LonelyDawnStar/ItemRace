package kr.minq.itemrace;

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

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public final class ItemRacePlugin extends JavaPlugin implements CommandExecutor {

    private final Random random = new Random();
    private final Map<UUID, Integer> scores = new HashMap<>();
    private final Map<UUID, Integer> roundBaselines = new HashMap<>();

    private List<Material> selectableMaterials;
    private boolean running;
    private boolean resolvingRound;
    private Material currentTarget;
    private BukkitTask inventoryScanner;

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
        getLogger().info("ItemRace 0.3.0 enabled successfully. Selectable items: " + selectableMaterials.size());
    }

    @Override
    public void onDisable() {
        if (inventoryScanner != null) inventoryScanner.cancel();
        running = false;
        resolvingRound = false;
        currentTarget = null;
        scores.clear();
        roundBaselines.clear();
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

        running = true;
        resolvingRound = false;
        scores.clear();
        chooseNextTarget();

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
        Bukkit.broadcastMessage(ChatColor.GOLD + "[ItemRace] " + ChatColor.RED + "게임이 종료되었습니다.");
        broadcastScores();
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
            sender.sendMessage(ChatColor.GRAY + "아직 점수를 얻은 플레이어가 없습니다.");
            return true;
        }
        scores.entrySet().stream()
                .sorted(Map.Entry.<UUID, Integer>comparingByValue().reversed())
                .forEach(entry -> {
                    String name = Bukkit.getOfflinePlayer(entry.getKey()).getName();
                    sender.sendMessage(ChatColor.YELLOW + (name == null ? entry.getKey().toString() : name)
                            + ChatColor.WHITE + ": " + ChatColor.AQUA + entry.getValue() + "점");
                });
        return true;
    }

    private void scanInventories() {
        if (!running || resolvingRound || currentTarget == null) return;

        for (Player player : Bukkit.getOnlinePlayers()) {
            int now = countMaterial(player, currentTarget);
            int baseline = roundBaselines.getOrDefault(player.getUniqueId(), 0);
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

        Bukkit.broadcastMessage(ChatColor.GOLD + "[ItemRace] " + ChatColor.GREEN + winner.getName()
                + ChatColor.YELLOW + "님이 " + ChatColor.AQUA + solvedName
                + ChatColor.YELLOW + "을(를) 획득했습니다!");
        Bukkit.broadcastMessage(ChatColor.GOLD + "[ItemRace] " + ChatColor.WHITE + winner.getName()
                + "의 현재 점수: " + ChatColor.AQUA + score + "점");

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!running) return;
            chooseNextTarget();
            resolvingRound = false;
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
        for (Player player : Bukkit.getOnlinePlayers()) {
            roundBaselines.put(player.getUniqueId(), countMaterial(player, currentTarget));
        }
    }

    private int countMaterial(Player player, Material material) {
        int total = 0;
        for (var stack : player.getInventory().getContents()) {
            if (stack != null && stack.getType() == material) total += stack.getAmount();
        }
        return total;
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
        scores.entrySet().stream()
                .sorted(Map.Entry.<UUID, Integer>comparingByValue().reversed())
                .forEach(entry -> {
                    String name = Bukkit.getOfflinePlayer(entry.getKey()).getName();
                    Bukkit.broadcastMessage(ChatColor.YELLOW + (name == null ? entry.getKey().toString() : name)
                            + ChatColor.WHITE + ": " + ChatColor.AQUA + entry.getValue() + "점");
                });
    }

    private String formatMaterialName(Material material) {
        return material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private void sendUsage(CommandSender sender, String label) {
        sender.sendMessage(ChatColor.GOLD + "ItemRace 0.3.0 명령어");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " start" + ChatColor.WHITE + " - 게임 시작");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " stop" + ChatColor.WHITE + " - 게임 종료");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " status" + ChatColor.WHITE + " - 현재 상태 확인");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " score" + ChatColor.WHITE + " - 점수 확인");
    }
}
