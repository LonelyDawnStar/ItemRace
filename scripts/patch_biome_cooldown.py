from pathlib import Path

manager_path = Path("src/main/java/kr/minq/itemrace/BiomeTeleportManager.java")
main_path = Path("src/main/java/kr/minq/itemrace/ItemRacePlugin.java")

manager = manager_path.read_text(encoding="utf-8")

manager = manager.replace(
    "import org.bukkit.event.player.PlayerQuitEvent;",
    "import org.bukkit.event.player.PlayerCommandPreprocessEvent;\nimport org.bukkit.event.player.PlayerQuitEvent;"
)
manager = manager.replace(
    "    private static final long COOLDOWN_MILLIS = 5L * 60L * 1000L;",
    "    private static final long DEFAULT_COOLDOWN_MILLIS = 5L * 60L * 1000L;"
)
manager = manager.replace(
    "    private boolean enabled;\n    private boolean raceActive;",
    "    private boolean enabled;\n    private boolean raceActive;\n    private long cooldownMillis = DEFAULT_COOLDOWN_MILLIS;"
)

command_handler = '''
    @EventHandler
    public void onBiomeCooldownCommand(PlayerCommandPreprocessEvent event) {
        String[] parts = event.getMessage().trim().split("\\\\s+");
        if (parts.length < 2) return;
        String root = parts[0].toLowerCase();
        if (!root.equals("/ir") && !root.equals("/itemrace")) return;
        String sub = parts[1].toLowerCase();
        if (!sub.equals("biometpcooldown") && !sub.equals("btcooldown")) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        if (raceActive) {
            player.sendMessage(ChatColor.RED + "바이옴 TP 쿨타임은 레이스 시작 전에만 설정할 수 있습니다.");
            return;
        }
        if (parts.length < 3) {
            player.sendMessage(ChatColor.YELLOW + "현재 바이옴 TP 쿨타임: " + formatCooldown(cooldownMillis));
            player.sendMessage(ChatColor.GRAY + "설정: /ir biometpcooldown <초|reset>");
            return;
        }
        if (parts[2].equalsIgnoreCase("reset")) {
            cooldownMillis = DEFAULT_COOLDOWN_MILLIS;
            player.sendMessage(ChatColor.GREEN + "바이옴 TP 쿨타임을 기본값 5분으로 초기화했습니다.");
            return;
        }
        try {
            int seconds = Integer.parseInt(parts[2]);
            if (seconds < 1 || seconds > 86400) {
                player.sendMessage(ChatColor.RED + "쿨타임은 1~86400초 사이로 입력하세요.");
                return;
            }
            cooldownMillis = seconds * 1000L;
            player.sendMessage(ChatColor.GREEN + "바이옴 TP 쿨타임을 " + formatCooldown(cooldownMillis) + "으로 설정했습니다.");
        } catch (NumberFormatException exception) {
            player.sendMessage(ChatColor.RED + "사용법: /ir biometpcooldown <초|reset>");
        }
    }

    private String formatCooldown(long millis) {
        long totalSeconds = Math.max(0L, millis / 1000L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return minutes > 0L ? minutes + "분 " + seconds + "초" : seconds + "초";
    }
'''

marker = "    @EventHandler\n    public void onSwapHands(PlayerSwapHandItemsEvent event) {"
if "onBiomeCooldownCommand" not in manager:
    manager = manager.replace(marker, command_handler + "\n" + marker)

manager = manager.replace(
    'lore.add(ChatColor.DARK_GRAY + "쿨타임 5분 · 이동 후 관전 10초");',
    'lore.add(ChatColor.DARK_GRAY + "쿨타임 " + formatCooldown(cooldownMillis) + " · 이동 후 관전 10초");'
)
manager = manager.replace(
    "cooldownUntil.put(uuid, System.currentTimeMillis() + COOLDOWN_MILLIS);",
    "cooldownUntil.put(uuid, System.currentTimeMillis() + cooldownMillis);"
)

manager_path.write_text(manager, encoding="utf-8")

main = main_path.read_text(encoding="utf-8")
usage_marker = '        sender.sendMessage(ChatColor.YELLOW + "/" + label + " start" + ChatColor.WHITE + " - 게임 시작");'
usage_line = '        sender.sendMessage(ChatColor.YELLOW + "/" + label + " biometpcooldown <초|reset>" + ChatColor.WHITE + " - 시작 전 바이옴 TP 쿨타임 설정");\n'
if "biometpcooldown <초|reset>" not in main and usage_marker in main:
    main = main.replace(usage_marker, usage_line + usage_marker)
main_path.write_text(main, encoding="utf-8")
