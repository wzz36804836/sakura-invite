package site.sakuragirl.sakurainvite.task;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import site.sakuragirl.sakurainvite.SakuraInvitePlugin;
import site.sakuragirl.sakurainvite.model.Models.TaskDefinition;
import site.sakuragirl.sakurainvite.model.Models.TaskType;

import java.io.File;
import java.util.*;
import java.util.logging.Level;

public final class TaskRegistry {
    private final SakuraInvitePlugin plugin;
    private final Map<String, TaskDefinition> definitions = new java.util.concurrent.ConcurrentHashMap<>();

    public TaskRegistry(SakuraInvitePlugin plugin) { this.plugin = plugin; }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "tasks.yml");
        if (!file.exists()) plugin.saveResource("tasks.yml", false);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        definitions.clear();
        ConfigurationSection root = yaml.getConfigurationSection("tasks");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null || !section.getBoolean("enabled", true)) continue;
            try {
                TaskType type = TaskType.valueOf(section.getString("type", "").toUpperCase(Locale.ROOT));
                int target = Math.max(1, section.getInt("target", 1));
                String name = section.getString("name", id);
                definitions.put(id, new TaskDefinition(id, type, target, section.getStringList("targets"), name));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().log(Level.WARNING, "tasks.yml 中任务 " + id + " 的 type 无效，已跳过。", ex);
            }
        }
    }

    public List<TaskDefinition> random(int count) {
        List<TaskDefinition> list = new ArrayList<>(definitions.values());
        Collections.shuffle(list);
        return list.subList(0, Math.min(Math.max(0, count), list.size()));
    }

    public TaskDefinition get(String id) { return definitions.get(id); }
    public int size() { return definitions.size(); }
}
