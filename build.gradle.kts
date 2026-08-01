plugins {
    java
}

group = "kr.minq"
version = "1.0.0"

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
                if (!source.contains(old)) {
                    throw GradleException("ItemRace source patch failed: $label")
                }
                source = source.replaceFirst(old, new)
            }

            source = source.replace("Sound.UI_TOAST_CHALLENGE", "Sound.ENTITY_PLAYER_LEVELUP")

            if (!source.contains("private BiomeTeleportManager biomeTeleportManager;")) {
                replaceOnce(
                    "manager fields",
                    "    private KoreanNameManager koreanNames;",
                    "    private KoreanNameManager koreanNames;\n    private BiomeTeleportManager biomeTeleportManager;\n    private final Set<UUID> selectedParticipants = new HashSet<>();\n    private boolean customParticipantSelection;"
                )

                replaceOnce(
                    "manager startup",
                    "        hintDisplayTask = Bukkit.getScheduler().runTaskTimer(this, this::sendHintToParticipants, 10L, 10L);",
                    "        hintDisplayTask = Bukkit.getScheduler().runTaskTimer(this, this::sendHintToParticipants, 10L, 10L);\n        biomeTeleportManager = new BiomeTeleportManager(this);\n        biomeTeleportManager.register();"
                )

                replaceOnce(
                    "manager shutdown",
                    "        if (hintDisplayTask != null) hintDisplayTask.cancel();",
                    "        if (hintDisplayTask != null) hintDisplayTask.cancel();\n        if (biomeTeleportManager != null) biomeTeleportManager.disable();"
                )

                replaceOnce(
                    "new commands",
                    "            case \"skip\", \"next\" -> skipRound(sender);",
                    "            case \"skip\", \"next\" -> skipRound(sender);\n            case \"join\" -> joinRace(sender);\n            case \"leave\" -> leaveRace(sender);\n            case \"players\" -> showPlayers(sender, args);\n            case \"biometp\" -> toggleBiomeTeleport(sender);"
                )

                replaceOnce(
                    "participant selection",
                    "        participants.clear();\n        history.clear();\n\n        for (Player player : Bukkit.getOnlinePlayers()) {\n            participants.add(player.getUniqueId());\n            scores.put(player.getUniqueId(), 0);\n        }\n\n        createGameUi();",
                    "        participants.clear();\n        history.clear();\n\n        List<Player> startingPlayers = new ArrayList<>();\n        if (customParticipantSelection) {\n            for (UUID uuid : selectedParticipants) {\n                Player player = Bukkit.getPlayer(uuid);\n                if (player != null && player.isOnline()) startingPlayers.add(player);\n            }\n        } else {\n            startingPlayers.addAll(Bukkit.getOnlinePlayers());\n        }\n        if (startingPlayers.isEmpty()) {\n            running = false;\n            resolvingRound = false;\n            sender.sendMessage(ChatColor.RED + \"참가 가능한 온라인 플레이어가 없습니다.\");\n            return true;\n        }\n        for (Player player : startingPlayers) {\n            participants.add(player.getUniqueId());\n            scores.put(player.getUniqueId(), 0);\n        }\n        if (biomeTeleportManager != null) biomeTeleportManager.setRaceActive(true);\n\n        createGameUi();"
                )

                replaceOnce(
                    "finish biome state",
                    "        running = false;\n        resolvingRound = false;",
                    "        running = false;\n        resolvingRound = false;\n        if (biomeTeleportManager != null) biomeTeleportManager.setRaceActive(false);"
                )

                replaceOnce(
                    "management methods",
                    "    private void sendUsage(CommandSender sender, String label) {",
                    "    private boolean joinRace(CommandSender sender) {\n        if (!(sender instanceof Player player)) {\n            sender.sendMessage(ChatColor.RED + \"플레이어만 참가할 수 있습니다.\");\n            return true;\n        }\n        if (running) {\n            sender.sendMessage(ChatColor.RED + \"레이스 진행 중에는 참가 명단을 바꿀 수 없습니다.\");\n            return true;\n        }\n        customParticipantSelection = true;\n        if (selectedParticipants.add(player.getUniqueId())) {\n            sender.sendMessage(ChatColor.GREEN + \"ItemRace 참가 명단에 등록되었습니다.\");\n        } else {\n            sender.sendMessage(ChatColor.YELLOW + \"이미 참가 명단에 있습니다.\");\n        }\n        return true;\n    }\n\n    private boolean leaveRace(CommandSender sender) {\n        if (!(sender instanceof Player player)) {\n            sender.sendMessage(ChatColor.RED + \"플레이어만 참가 취소할 수 있습니다.\");\n            return true;\n        }\n        if (running) {\n            sender.sendMessage(ChatColor.RED + \"레이스 진행 중에는 참가 명단을 바꿀 수 없습니다.\");\n            return true;\n        }\n        customParticipantSelection = true;\n        if (selectedParticipants.remove(player.getUniqueId())) {\n            sender.sendMessage(ChatColor.YELLOW + \"ItemRace 참가 명단에서 빠졌습니다.\");\n        } else {\n            sender.sendMessage(ChatColor.GRAY + \"현재 참가 명단에 없습니다.\");\n        }\n        return true;\n    }\n\n    private boolean showPlayers(CommandSender sender, String[] args) {\n        if (args.length >= 2 && args[1].equalsIgnoreCase(\"reset\")) {\n            if (running) {\n                sender.sendMessage(ChatColor.RED + \"레이스 진행 중에는 참가 설정을 초기화할 수 없습니다.\");\n                return true;\n            }\n            customParticipantSelection = false;\n            selectedParticipants.clear();\n            sender.sendMessage(ChatColor.GREEN + \"참가 설정을 초기화했습니다. 시작 시 접속자 전원이 참가합니다.\");\n            return true;\n        }\n        sender.sendMessage(ChatColor.GOLD + \"===== ItemRace 참가자 =====\");\n        if (!customParticipantSelection) {\n            sender.sendMessage(ChatColor.GRAY + \"자동 모드: 시작 시 접속자 전원 참가\");\n            return true;\n        }\n        if (selectedParticipants.isEmpty()) {\n            sender.sendMessage(ChatColor.RED + \"등록된 참가자가 없습니다.\");\n            return true;\n        }\n        for (UUID uuid : selectedParticipants) {\n            sender.sendMessage(ChatColor.YELLOW + \"- \" + playerName(uuid)\n                    + (Bukkit.getPlayer(uuid) == null ? ChatColor.GRAY + \" (오프라인)\" : ChatColor.GREEN + \" (온라인)\"));\n        }\n        return true;\n    }\n\n    private boolean toggleBiomeTeleport(CommandSender sender) {\n        if (biomeTeleportManager == null) {\n            sender.sendMessage(ChatColor.RED + \"바이옴 텔레포트 관리자를 불러오지 못했습니다.\");\n            return true;\n        }\n        return biomeTeleportManager.toggle(sender);\n    }\n\n    boolean isItemRaceParticipant(UUID uuid) {\n        return running && participants.contains(uuid);\n    }\n\n    private void sendUsage(CommandSender sender, String label) {"
                )

                replaceOnce(
                    "usage lines",
                    "        sender.sendMessage(ChatColor.YELLOW + \"/\" + label + \" start\" + ChatColor.WHITE + \" - 게임 시작\");",
                    "        sender.sendMessage(ChatColor.YELLOW + \"/\" + label + \" join|leave\" + ChatColor.WHITE + \" - 참가 등록 또는 취소\");\n        sender.sendMessage(ChatColor.YELLOW + \"/\" + label + \" players [reset]\" + ChatColor.WHITE + \" - 참가자 확인 또는 자동 참가로 초기화\");\n        sender.sendMessage(ChatColor.YELLOW + \"/\" + label + \" biometp\" + ChatColor.WHITE + \" - 시작 전 바이옴 이동 기능 켜기/끄기\");\n        sender.sendMessage(ChatColor.YELLOW + \"/\" + label + \" start\" + ChatColor.WHITE + \" - 게임 시작\");"
                )
            }

            if (!source.contains("boolean isItemRaceParticipant(UUID uuid)")) {
                replaceOnce(
                    "participant bridge fallback",
                    "    private void sendUsage(CommandSender sender, String label) {",
                    "    boolean isItemRaceParticipant(UUID uuid) {\n        return running && participants.contains(uuid);\n    }\n\n    private void sendUsage(CommandSender sender, String label) {"
                )
            }

            itemRaceSource.writeText(source, Charsets.UTF_8)
        }
    }

    processResources {
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") {
            expand("version" to project.version)
        }
    }

    jar {
        archiveBaseName.set("ItemRace")
        archiveVersion.set(project.version.toString())
    }
}
