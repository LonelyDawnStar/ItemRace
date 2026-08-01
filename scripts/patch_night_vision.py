from pathlib import Path

path = Path("src/main/java/kr/minq/itemrace/ItemRacePlugin.java")
source = path.read_text(encoding="utf-8")


def replace_once(label: str, old: str, new: str) -> None:
    global source
    if old not in source:
        raise RuntimeError(f"Night vision patch failed: {label}")
    source = source.replace(old, new, 1)


if "import org.bukkit.potion.PotionEffect;" not in source:
    replace_once(
        "potion imports",
        "import org.bukkit.plugin.java.JavaPlugin;",
        "import org.bukkit.plugin.java.JavaPlugin;\nimport org.bukkit.potion.PotionEffect;\nimport org.bukkit.potion.PotionEffectType;",
    )

if "private boolean nightVisionEnabled;" not in source:
    replace_once(
        "night vision fields",
        "    private KoreanNameManager koreanNames;",
        "    private KoreanNameManager koreanNames;\n"
        "    private boolean nightVisionEnabled;\n"
        "    private BukkitTask nightVisionTask;\n"
        "    private final Set<UUID> nightVisionApplied = new HashSet<>();",
    )

if "this::updateNightVision" not in source:
    replace_once(
        "night vision scheduler",
        "        hintDisplayTask = Bukkit.getScheduler().runTaskTimer(this, this::sendHintToParticipants, 10L, 10L);",
        "        hintDisplayTask = Bukkit.getScheduler().runTaskTimer(this, this::sendHintToParticipants, 10L, 10L);\n"
        "        nightVisionTask = Bukkit.getScheduler().runTaskTimer(this, this::updateNightVision, 0L, 100L);",
    )

if "if (nightVisionTask != null) nightVisionTask.cancel();" not in source:
    replace_once(
        "night vision shutdown",
        "        if (hintDisplayTask != null) hintDisplayTask.cancel();",
        "        if (hintDisplayTask != null) hintDisplayTask.cancel();\n"
        "        if (nightVisionTask != null) nightVisionTask.cancel();\n"
        "        clearNightVision();",
    )

if 'case "nv" -> toggleNightVision(sender);' not in source:
    replace_once(
        "night vision command",
        '            case "status" -> showStatus(sender);',
        '            case "status" -> showStatus(sender);\n'
        '            case "nv" -> toggleNightVision(sender);',
    )

if "야간 투시:" not in source:
    replace_once(
        "night vision status",
        '        sender.sendMessage(ChatColor.YELLOW + "문제 제한시간: " + ChatColor.AQUA + formatLimit(roundTimeSeconds, "초"));',
        '        sender.sendMessage(ChatColor.YELLOW + "문제 제한시간: " + ChatColor.AQUA + formatLimit(roundTimeSeconds, "초"));\n'
        '        sender.sendMessage(ChatColor.YELLOW + "야간 투시: " + (nightVisionEnabled ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF"));',
    )

if "private boolean toggleNightVision(CommandSender sender)" not in source:
    methods = r'''
    private boolean toggleNightVision(CommandSender sender) {
        if (running) {
            sender.sendMessage(ChatColor.RED + "야간 투시는 레이스 시작 전에만 설정할 수 있습니다.");
            return true;
        }
        nightVisionEnabled = !nightVisionEnabled;
        if (!nightVisionEnabled) clearNightVision();
        sender.sendMessage(nightVisionEnabled
                ? ChatColor.GREEN + "레이스 야간 투시를 활성화했습니다."
                : ChatColor.YELLOW + "레이스 야간 투시를 비활성화했습니다.");
        return true;
    }

    private void updateNightVision() {
        if (!running || !nightVisionEnabled) {
            clearNightVision();
            return;
        }
        for (UUID uuid : participants) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) continue;
            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.NIGHT_VISION,
                    220,
                    0,
                    false,
                    false,
                    true
            ), true);
            nightVisionApplied.add(uuid);
        }
    }

    private void clearNightVision() {
        for (UUID uuid : new HashSet<>(nightVisionApplied)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                player.removePotionEffect(PotionEffectType.NIGHT_VISION);
            }
        }
        nightVisionApplied.clear();
    }

'''
    replace_once(
        "night vision methods",
        "    private void sendUsage(CommandSender sender, String label) {",
        methods + "    private void sendUsage(CommandSender sender, String label) {",
    )

if '" nv"' not in source:
    replace_once(
        "night vision usage",
        '        sender.sendMessage(ChatColor.YELLOW + "/" + label + " start" + ChatColor.WHITE + " - 게임 시작");',
        '        sender.sendMessage(ChatColor.YELLOW + "/" + label + " nv" + ChatColor.WHITE + " - 레이스 야간 투시 켜기/끄기");\n'
        '        sender.sendMessage(ChatColor.YELLOW + "/" + label + " start" + ChatColor.WHITE + " - 게임 시작");',
    )

path.write_text(source, encoding="utf-8")
