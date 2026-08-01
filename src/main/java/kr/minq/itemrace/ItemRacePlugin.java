package kr.minq.itemrace;

import org.bukkit.plugin.java.JavaPlugin;

public final class ItemRacePlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("ItemRace 0.1.0 enabled successfully.");
    }

    @Override
    public void onDisable() {
        getLogger().info("ItemRace disabled.");
    }
}
