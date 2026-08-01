package kr.minq.itemrace;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Material;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

final class KoreanNameManager {

    private static final String ASSET_KEY = "minecraft/lang/ko_kr.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final ItemRacePlugin plugin;
    private final Map<String, String> namesByMaterialId = new HashMap<>();
    private Path languageFile;

    KoreanNameManager(ItemRacePlugin plugin) {
        this.plugin = plugin;
    }

    void load() {
        namesByMaterialId.clear();
        Path dataFolder = plugin.getDataFolder().toPath();
        languageFile = dataFolder.resolve("ko_kr.json");

        try {
            Files.createDirectories(dataFolder);
            if (!Files.isRegularFile(languageFile)) {
                importFromMinecraftAssets(languageFile);
            }
            if (!Files.isRegularFile(languageFile)) {
                Files.writeString(languageFile, "{}\n", StandardCharsets.UTF_8);
                plugin.getLogger().warning("ko_kr.json을 자동으로 찾지 못했습니다. plugins/ItemRace/ko_kr.json에 한글 언어 파일을 넣으면 한글 힌트가 적용됩니다.");
                return;
            }

            try (Reader reader = Files.newBufferedReader(languageFile, StandardCharsets.UTF_8)) {
                JsonObject language = JsonParser.parseReader(reader).getAsJsonObject();
                readLanguageObject(language);
            }
            plugin.getLogger().info("기본 한글 이름 " + namesByMaterialId.size() + "개를 불러왔습니다.");
        } catch (Exception exception) {
            plugin.getLogger().warning("ko_kr.json을 읽는 중 오류가 발생했습니다: " + exception.getMessage());
        }
    }

    String displayName(Material material) {
        String id = material.name().toLowerCase(Locale.ROOT);
        return namesByMaterialId.getOrDefault(id, formatMaterialName(material));
    }

    boolean isLoaded() {
        return !namesByMaterialId.isEmpty();
    }

    void writeCache(ResourcePackNameManager resourcePackNames) {
        JsonObject root = new JsonObject();
        JsonObject materials = new JsonObject();

        Arrays.stream(Material.values())
                .filter(Material::isItem)
                .filter(material -> !material.isAir())
                .filter(material -> !material.name().startsWith("LEGACY_"))
                .sorted(Comparator.comparing(Material::name))
                .forEach(material -> {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("resourcePackName", resourcePackNames.displayName(material));
                    entry.addProperty("koreanName", displayName(material));
                    materials.add(material.name(), entry);
                });

        root.addProperty("generatedBy", "ItemRace 0.6.0");
        root.addProperty("koreanNamesLoaded", isLoaded());
        root.addProperty("resourcePackNamesLoaded", resourcePackNames.isLoaded());
        root.add("materials", materials);

        Path cache = plugin.getDataFolder().toPath().resolve("cache.json");
        try (Writer writer = Files.newBufferedWriter(cache, StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        } catch (IOException exception) {
            plugin.getLogger().warning("cache.json을 저장하지 못했습니다: " + exception.getMessage());
        }
    }

    private void readLanguageObject(JsonObject language) {
        for (Map.Entry<String, JsonElement> entry : language.entrySet()) {
            if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString()) continue;

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

    private void importFromMinecraftAssets(Path destination) {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isBlank()) return;

        Path minecraft = Path.of(appData, ".minecraft");
        Path indexes = minecraft.resolve("assets/indexes");
        Path objects = minecraft.resolve("assets/objects");
        if (!Files.isDirectory(indexes) || !Files.isDirectory(objects)) return;

        try (Stream<Path> stream = Files.list(indexes)) {
            Optional<Path> imported = stream
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted((left, right) -> Long.compare(lastModified(right), lastModified(left)))
                    .map(index -> findLanguageObject(index, objects))
                    .flatMap(Optional::stream)
                    .findFirst();

            if (imported.isPresent()) {
                Files.copy(imported.get(), destination, StandardCopyOption.REPLACE_EXISTING);
                plugin.getLogger().info("마인크래프트 assets에서 ko_kr.json을 자동으로 가져왔습니다.");
            }
        } catch (Exception exception) {
            plugin.getLogger().warning("마인크래프트 assets에서 ko_kr.json을 가져오지 못했습니다: " + exception.getMessage());
        }
    }

    private Optional<Path> findLanguageObject(Path indexFile, Path objectsFolder) {
        try (Reader reader = Files.newBufferedReader(indexFile, StandardCharsets.UTF_8)) {
            JsonObject index = JsonParser.parseReader(reader).getAsJsonObject();
            JsonObject objects = index.getAsJsonObject("objects");
            if (objects == null || !objects.has(ASSET_KEY)) return Optional.empty();

            JsonObject language = objects.getAsJsonObject(ASSET_KEY);
            String hash = language.get("hash").getAsString();
            if (hash.length() < 2) return Optional.empty();
            Path object = objectsFolder.resolve(hash.substring(0, 2)).resolve(hash);
            return Files.isRegularFile(object) ? Optional.of(object) : Optional.empty();
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException exception) {
            return 0L;
        }
    }

    private String formatMaterialName(Material material) {
        String[] words = material.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (!result.isEmpty()) result.append(' ');
            if (!word.isEmpty()) result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }
}
