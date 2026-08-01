plugins {
    java
}

group = "kr.minq"
version = "1.1.0"

repositories {
    mavenCentral()
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.87-stable")
    compileOnly("com.google.code.gson:gson:2.11.0")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

val itemRaceSource = file("src/main/java/kr/minq/itemrace/ItemRacePlugin.java")

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(25)

        doFirst {
            if (!itemRaceSource.isFile) return@doFirst

            var source = itemRaceSource.readText(Charsets.UTF_8)

            fun replaceOnce(label: String, old: String, new: String) {
                if (!source.contains(old)) throw GradleException("ItemRace source patch failed: $label")
                source = source.replaceFirst(old, new)
            }

            source = source.replace("Sound.UI_TOAST_CHALLENGE", "Sound.ENTITY_PLAYER_LEVELUP")

            if (!source.contains("private BiomeTeleportManager biomeTeleportManager;")) {
                replaceOnce("manager fields", "    private KoreanNameManager koreanNames;", "    private KoreanNameManager koreanNames;\n    private BiomeTeleportManager biomeTeleportManager;\n    private final Set<UUID> selectedParticipants = new HashSet<>();\n    private boolean customParticipantSelection;")
                replaceOnce("manager startup", "        hintDisplayTask = Bukkit.getScheduler().runTaskTimer(this, this::sendHintToParticipants, 10L, 10L);", "        hintDisplayTask = Bukkit.getScheduler().runTaskTimer(this, this::sendHintToParticipants, 10L, 10L);\n        biomeTeleportManager = new BiomeTeleportManager(this);\n        biomeTeleportManager.register();")
                replaceOnce("manager shutdown", "        if (hintDisplayTask != null) hintDisplayTask.cancel();", "        if (hintDisplayTask != null) hintDisplayTask.cancel();\n        if (biomeTeleportManager != null) biomeTeleportManager.disable();")
                replaceOnce("base commands", "            case \"skip\", \"next\" -> skipRound(sender);", "            case \"skip\", \"next\" -> skipRound(sender);\n            case \"join\" -> joinRace(sender);\n            case \"leave\" -> leaveRace(sender);\n            case \"players\" -> showPlayers(sender, args);\n            case \"biometp\" -> toggleBiomeTeleport(sender);")
                replaceOnce("participant selection", "        participants.clear();\n        history.clear();\n\n        for (Player player : Bukkit.getOnlinePlayers()) {\n            participants.add(player.getUniqueId());\n            scores.put(player.getUniqueId(), 0);\n        }\n\n        createGameUi();", "        participants.clear();\n        history.clear();\n\n        List<Player> startingPlayers = new ArrayList<>();\n        if (customParticipantSelection) {\n            for (UUID uuid : selectedParticipants) {\n                Player player = Bukkit.getPlayer(uuid);\n                if (player != null && player.isOnline()) startingPlayers.add(player);\n            }\n        } else {\n            startingPlayers.addAll(Bukkit.getOnlinePlayers());\n        }\n        if (startingPlayers.isEmpty()) {\n            running = false;\n            resolvingRound = false;\n            sender.sendMessage(ChatColor.RED + \"참가 가능한 온라인 플레이어가 없습니다.\");\n            return true;\n        }\n        for (Player player : startingPlayers) {\n            participants.add(player.getUniqueId());\n            scores.put(player.getUniqueId(), 0);\n        }\n        if (biomeTeleportManager != null) biomeTeleportManager.setRaceActive(true);\n\n        createGameUi();")
                replaceOnce("finish biome state", "        running = false;\n        resolvingRound = false;", "        running = false;\n        resolvingRound = false;\n        if (biomeTeleportManager != null) biomeTeleportManager.setRaceActive(false);")
                replaceOnce("participant methods", "    private void sendUsage(CommandSender sender, String label) {", "    private boolean joinRace(CommandSender sender) {\n        if (!(sender instanceof Player player)) { sender.sendMessage(ChatColor.RED + \"플레이어만 참가할 수 있습니다.\"); return true; }\n        if (running) { sender.sendMessage(ChatColor.RED + \"레이스 진행 중에는 참가 명단을 바꿀 수 없습니다.\"); return true; }\n        customParticipantSelection = true;\n        sender.sendMessage(selectedParticipants.add(player.getUniqueId()) ? ChatColor.GREEN + \"ItemRace 참가 명단에 등록되었습니다.\" : ChatColor.YELLOW + \"이미 참가 명단에 있습니다.\");\n        return true;\n    }\n\n    private boolean leaveRace(CommandSender sender) {\n        if (!(sender instanceof Player player)) { sender.sendMessage(ChatColor.RED + \"플레이어만 참가 취소할 수 있습니다.\"); return true; }\n        if (running) { sender.sendMessage(ChatColor.RED + \"레이스 진행 중에는 참가 명단을 바꿀 수 없습니다.\"); return true; }\n        customParticipantSelection = true;\n        sender.sendMessage(selectedParticipants.remove(player.getUniqueId()) ? ChatColor.YELLOW + \"ItemRace 참가 명단에서 빠졌습니다.\" : ChatColor.GRAY + \"현재 참가 명단에 없습니다.\");\n        return true;\n    }\n\n    private boolean showPlayers(CommandSender sender, String[] args) {\n        if (args.length >= 2 && args[1].equalsIgnoreCase(\"reset\")) {\n            if (running) { sender.sendMessage(ChatColor.RED + \"레이스 진행 중에는 참가 설정을 초기화할 수 없습니다.\"); return true; }\n            customParticipantSelection = false; selectedParticipants.clear();\n            sender.sendMessage(ChatColor.GREEN + \"참가 설정을 초기화했습니다. 시작 시 접속자 전원이 참가합니다.\"); return true;\n        }\n        sender.sendMessage(ChatColor.GOLD + \"===== ItemRace 참가자 =====\");\n        if (!customParticipantSelection) { sender.sendMessage(ChatColor.GRAY + \"자동 모드: 시작 시 접속자 전원 참가\"); return true; }\n        if (selectedParticipants.isEmpty()) { sender.sendMessage(ChatColor.RED + \"등록된 참가자가 없습니다.\"); return true; }\n        for (UUID uuid : selectedParticipants) sender.sendMessage(ChatColor.YELLOW + \"- \" + playerName(uuid) + (Bukkit.getPlayer(uuid) == null ? ChatColor.GRAY + \" (오프라인)\" : ChatColor.GREEN + \" (온라인)\"));\n        return true;\n    }\n\n    private boolean toggleBiomeTeleport(CommandSender sender) {\n        if (biomeTeleportManager == null) { sender.sendMessage(ChatColor.RED + \"바이옴 텔레포트 관리자를 불러오지 못했습니다.\"); return true; }\n        return biomeTeleportManager.toggle(sender);\n    }\n\n    boolean isItemRaceParticipant(UUID uuid) { return running && participants.contains(uuid); }\n\n    private void sendUsage(CommandSender sender, String label) {")
            }

            if (!source.contains("case \"end\" -> endRace(sender);")) {
                replaceOnce("1.1 commands", "            case \"stop\" -> stopGame(sender);", "            case \"stop\" -> stopGame(sender);\n            case \"end\" -> endRace(sender);\n            case \"settings\" -> showSettings(sender);\n            case \"biometpcooldown\" -> setBiomeTeleportCooldown(sender, args);")
            }

            if (!source.contains("private boolean endRace(CommandSender sender)")) {
                replaceOnce("1.1 methods", "    private void sendUsage(CommandSender sender, String label) {", "    private boolean setBiomeTeleportCooldown(CommandSender sender, String[] args) {\n        if (biomeTeleportManager == null) { sender.sendMessage(ChatColor.RED + \"바이옴 텔레포트 관리자를 불러오지 못했습니다.\"); return true; }\n        return biomeTeleportManager.setCooldown(sender, args);\n    }\n\n    private boolean showSettings(CommandSender sender) {\n        sender.sendMessage(ChatColor.GOLD + \"===== ItemRace 설정 =====\");\n        sender.sendMessage(ChatColor.YELLOW + \"레이스 상태: \" + (running ? ChatColor.GREEN + \"진행 중\" : ChatColor.GRAY + \"대기 중\"));\n        sender.sendMessage(ChatColor.YELLOW + \"목표 점수: \" + ChatColor.AQUA + formatLimit(goalScore, \"점\"));\n        sender.sendMessage(ChatColor.YELLOW + \"문제 제한시간: \" + ChatColor.AQUA + formatLimit(roundTimeSeconds, \"초\"));\n        sender.sendMessage(ChatColor.YELLOW + \"바이옴 TP: \" + (biomeTeleportManager != null && biomeTeleportManager.isEnabled() ? ChatColor.GREEN + \"ON\" : ChatColor.RED + \"OFF\"));\n        if (biomeTeleportManager != null) sender.sendMessage(ChatColor.YELLOW + \"바이옴 TP 쿨타임: \" + ChatColor.AQUA + biomeTeleportManager.getCooldownSeconds() + \"초\");\n        sender.sendMessage(ChatColor.YELLOW + \"참가 방식: \" + ChatColor.AQUA + (customParticipantSelection ? \"수동 선택\" : \"접속자 전원\"));\n        return true;\n    }\n\n    private boolean endRace(CommandSender sender) {\n        if (!running) { sender.sendMessage(ChatColor.RED + \"현재 진행 중인 ItemRace가 없습니다.\"); return true; }\n        int topScore = scores.values().stream().mapToInt(Integer::intValue).max().orElse(0);\n        List<Player> winners = new ArrayList<>();\n        if (topScore > 0) {\n            for (Map.Entry<UUID, Integer> entry : scores.entrySet()) {\n                if (entry.getValue() != topScore) continue;\n                Player player = Bukkit.getPlayer(entry.getKey());\n                if (player != null && player.isOnline()) winners.add(player);\n            }\n        }\n        cancelCountdown(); cancelRoundIntroTask(); cancelRoundTimer();\n        running = false; resolvingRound = false; currentTarget = null;\n        roundBaselines.clear(); revealedHintIndexes.clear(); hideTargetBossBar();\n        if (biomeTeleportManager != null) biomeTeleportManager.setRaceActive(false);\n        Bukkit.broadcastMessage(ChatColor.GOLD + \"[ItemRace] \" + ChatColor.YELLOW + \"관리자가 레이스를 종료했습니다.\");\n        broadcastScores(); saveGameLog(\"관리자가 /ir end로 레이스를 종료했습니다.\");\n        if (winners.isEmpty()) {\n            Bukkit.broadcastMessage(ChatColor.GRAY + \"1점 이상 획득한 플레이어가 없어 우승자가 없습니다.\");\n        } else {\n            String names = winners.stream().map(Player::getName).sorted(String.CASE_INSENSITIVE_ORDER).collect(java.util.stream.Collectors.joining(\", \"));\n            boolean shared = winners.size() > 1;\n            Title title = Title.title(Component.text(shared ? \"공동 우승!\" : \"WINNER\"), Component.text(names + \" · \" + topScore + \"점\"), Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(3000), Duration.ofMillis(700)));\n            forEachParticipant(player -> { player.showTitle(title); player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f); });\n            for (Player winner : winners) launchWinnerFireworks(winner);\n            Bukkit.broadcastMessage(ChatColor.GOLD + (shared ? \"공동 우승: \" : \"우승: \") + ChatColor.AQUA + names);\n        }\n        Bukkit.getScheduler().runTaskLater(this, () -> { clearGameUi(); participants.clear(); }, winners.isEmpty() ? 20L : 80L);\n        return true;\n    }\n\n    private void sendUsage(CommandSender sender, String label) {")
            }

            val oldShow = "    private void showTargetBossBar() {\n        if (targetBossBar == null) return;\n        forEachParticipant(player -> player.showBossBar(targetBossBar));\n    }"
            val newShow = "    private void showTargetBossBar() {\n        if (targetBossBar == null) return;\n        forEachParticipant(player -> player.showBossBar(targetBossBar));\n        if (biomeTeleportManager != null) biomeTeleportManager.showStatusBars();\n    }"
            if (source.contains(oldShow)) source = source.replace(oldShow, newShow)

            val oldHide = "    private void hideTargetBossBar() {\n        if (targetBossBar == null) return;\n        forEachParticipant(player -> player.hideBossBar(targetBossBar));\n    }"
            val newHide = "    private void hideTargetBossBar() {\n        if (targetBossBar != null) forEachParticipant(player -> player.hideBossBar(targetBossBar));\n        if (biomeTeleportManager != null) biomeTeleportManager.hideStatusBars();\n    }"
            if (source.contains(oldHide)) source = source.replace(oldHide, newHide)

            if (!source.contains("biometpcooldown <초|reset>")) {
                source = source.replace("        sender.sendMessage(ChatColor.YELLOW + \"/\" + label + \" start\" + ChatColor.WHITE + \" - 게임 시작\");", "        sender.sendMessage(ChatColor.YELLOW + \"/\" + label + \" join|leave\" + ChatColor.WHITE + \" - 참가 등록 또는 취소\");\n        sender.sendMessage(ChatColor.YELLOW + \"/\" + label + \" players [reset]\" + ChatColor.WHITE + \" - 참가자 확인 또는 자동 참가로 초기화\");\n        sender.sendMessage(ChatColor.YELLOW + \"/\" + label + \" biometp\" + ChatColor.WHITE + \" - 바이옴 TP 켜기/끄기\");\n        sender.sendMessage(ChatColor.YELLOW + \"/\" + label + \" biometpcooldown <초|reset>\" + ChatColor.WHITE + \" - 바이옴 TP 쿨타임 설정\");\n        sender.sendMessage(ChatColor.YELLOW + \"/\" + label + \" settings\" + ChatColor.WHITE + \" - 현재 설정 확인\");\n        sender.sendMessage(ChatColor.YELLOW + \"/\" + label + \" end\" + ChatColor.WHITE + \" - 현재 점수로 레이스 종료\");\n        sender.sendMessage(ChatColor.YELLOW + \"/\" + label + \" start\" + ChatColor.WHITE + \" - 게임 시작\");")
            }

            source = source.replace("ItemRace 0.9.0", "ItemRace 1.1.0")
            itemRaceSource.writeText(source, Charsets.UTF_8)
        }
    }

    processResources {
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") { expand("version" to project.version) }
    }

    jar {
        archiveBaseName.set("ItemRace")
        archiveVersion.set(project.version.toString())
    }
}
