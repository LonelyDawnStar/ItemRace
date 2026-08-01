package kr.minq.itemrace;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public final class ItemRacePlugin extends JavaPlugin implements CommandExecutor {

    private final Random random = new Random();
    private List<Material> selectableMaterials;
    private boolean running;
    private Material currentTarget;

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
        getLogger().info("ItemRace 0.2.0 enabled successfully. Selectable items: " + selectableMaterials.size());
    }

    @Override
    public void onDisable() {
        running = false;
        currentTarget = null;
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
        chooseNextTarget();

        Bukkit.broadcastMessage(ChatColor.GOLD + "[ItemRace] " + ChatColor.YELLOW + "게임이 시작되었습니다!");
        Bukkit.broadcastMessage(ChatColor.GOLD + "[ItemRace] " + ChatColor.WHITE + "첫 목표: "
                + ChatColor.AQUA + formatMaterialName(currentTarget));
        return true;
    }

    private boolean stopGame(CommandSender sender) {
        if (!running) {
            sender.sendMessage(ChatColor.RED + "현재 진행 중인 ItemRace가 없습니다.");
            return true;
        }

        running = false;
        currentTarget = null;
        Bukkit.broadcastMessage(ChatColor.GOLD + "[ItemRace] " + ChatColor.RED + "게임이 종료되었습니다.");
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

    private void chooseNextTarget() {
        currentTarget = selectableMaterials.get(random.nextInt(selectableMaterials.size()));
    }

    private String formatMaterialName(Material material) {
        return material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private void sendUsage(CommandSender sender, String label) {
        sender.sendMessage(ChatColor.GOLD + "ItemRace 0.2.0 명령어");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " start" + ChatColor.WHITE + " - 게임 시작");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " stop" + ChatColor.WHITE + " - 게임 종료");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " status" + ChatColor.WHITE + " - 현재 상태 확인");
    }

    public boolean isRunning() {
        return running;
    }

    public Material getCurrentTarget() {
        return currentTarget;
    }
}
