package kr.minq.itemrace;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Material;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class ResourcePackNameManager {

    private static final String KOREAN_LANGUAGE_PATH = "assets/minecraft/lang/ko_kr.json";
    private static final String ENGLISH_LANGUAGE_PATH = "assets/minecraft/lang/en_us.json";

    private final ItemRacePlugin plugin;
    private final Map<String, String> namesByMaterialId = new HashMap<>();
    private Path loadedPack;

    ResourcePackNameManager(ItemRacePlugin plugin) {
        this.plugin = plugin;
    }

    void load() {
        namesByMaterialId.clear();
        loadedPack = null;

        Path dataFolder = plugin.getDataFolder().toPath();
        try {
            Files.createDirectories(dataFolder);
        } catch (IOException exception) {
            plugin.getLogger().warning("플러그인 데이터 폴더를 만들지 못했습니다: " + exception.getMessage());
            return;
        }

        Optional<Path> pack = findResourcePack(dataFolder);
        if (pack.isEmpty()) {
            plugin.getLogger().warning("리소스팩 ZIP을 찾지 못했습니다. plugins/ItemRace/resourcepack.zip에 넣어 주세요.");
            return;
        }

        try (ZipFile zipFile = new ZipFile(pack.get().toFile(), StandardCharsets.UTF_8)) {
            ZipEntry languageEntry = zipFile.getEntry(KOREAN_LANGUAGE_PATH);
            if (languageEntry == null) {
                languageEntry = zipFile.getEntry(ENGLISH_LANGUAGE_PATH);
            }
            if (languageEntry == null) {
                plugin.getLogger().warning("리소스팩에서 ko_kr.json 또는 en_us.json을 찾지 못했습니다.");
                return;
            }

            try (Reader reader = new InputStreamReader(zipFile.getInputStream(languageEntry), StandardCharsets.UTF_8)) {
                JsonObject language = JsonParser.parseReader(reader).getAsJsonObject();
                for (Map.Entry<String, JsonElement> entry : language.entrySet()) {
                    if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString()) {
                        continue;
                    }

                    String key = entry.getKey();
                    String materialId = null;
                    if (key.startsWith("item.minecraft.")) {
                        materialId = key.substring("item.minecraft.".length());
                    } else if (key.startsWith("block.minecraft.")) {
                        materialId = key.substring("block.minecraft.".length());
                    }

                    if (materialId != null) {
                        namesByMaterialId.putIfAbsent(materialId, entry.getValue().getAsString());
                    }
                }
            }

            loadedPack = pack.get();
            plugin.getLogger().info("리소스팩 이름 " + namesByMaterialId.size() + "개를 불러왔습니다: " + loadedPack.getFileName());
        } catch (Exception exception) {
            plugin.getLogger().warning("리소스팩을 읽는 중 오류가 발생했습니다: " + exception.getMessage());
        }
    }

    String displayName(Material material) {
        String id = material.name().toLowerCase(Locale.ROOT);
        return namesByMaterialId.getOrDefault(id, formatMaterialName(material));
    }

    boolean isLoaded() {
        return loadedPack != null && !namesByMaterialId.isEmpty();
    }

    private Optional<Path> findResourcePack(Path dataFolder) {
        Path preferred = dataFolder.resolve("resourcepack.zip");
        if (Files.isRegularFile(preferred)) {
            return Optional.of(preferred);
        }

        try (Stream<Path> paths = Files.list(dataFolder)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
                    .findFirst();
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private String formatMaterialName(Material material) {
        String[] words = material.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (!result.isEmpty()) {
                result.append(' ');
            }
            if (!word.isEmpty()) {
                result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return result.toString();
    }
}
