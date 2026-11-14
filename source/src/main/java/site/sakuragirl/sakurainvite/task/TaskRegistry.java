package site.sakuragirl.sakurainvite.task;

import java.io.File;
import java.util.*;
import java.util.logging.Level;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import site.sakuragirl.sakurainvite.SakuraInvitePlugin;
import site.sakuragirl.sakurainvite.model.Models.TaskDefinition;
import site.sakuragirl.sakurainvite.model.Models.TaskType;

public final class TaskRegistry {
  private final SakuraInvitePlugin plugin;
  private final Map<String, TaskDefinition> definitions =
      new java.util.concurrent.ConcurrentHashMap<>();

  public TaskRegistry(SakuraInvitePlugin plugin) {
    this.plugin = plugin;
  }

  public void reload() {
    File file = new File(plugin.getDataFolder(), "tasks.yml");
    if (!file.exists()) plugin.saveResource("tasks.yml", false);
    YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
    Map<String, TaskDefinition> loaded = new HashMap<>();
    ConfigurationSection root = yaml.getConfigurationSection("tasks");
    if (root == null) throw new IllegalArgumentException("tasks.yml 缺少 tasks 配置");
    for (String id : root.getKeys(false)) {
      ConfigurationSection section = root.getConfigurationSection(id);
      if (section == null || !section.getBoolean("enabled", true)) continue;
      try {
        TaskType type = TaskType.valueOf(section.getString("type", "").toUpperCase(Locale.ROOT));
        int target = section.getInt("target", 1);
        if (target < 1 || target > 1000000)
          throw new IllegalArgumentException("target 必须为 1 至 1000000");
        String name = section.getString("name", id);
        List<String> targets =
            section.getStringList("targets").stream().map(v -> v.toUpperCase(Locale.ROOT)).toList();
        for (String value : targets) {
          if (type == TaskType.KILL) org.bukkit.entity.EntityType.valueOf(value);
          else if (type == TaskType.BLOCK_BREAK || type == TaskType.PLANT)
            org.bukkit.Material.valueOf(value);
        }
        loaded.put(id, new TaskDefinition(id, type, target, targets, name));
      } catch (IllegalArgumentException ex) {
        plugin
            .getLogger()
            .log(Level.WARNING, "tasks.yml 中任务 " + id + " 无效，已跳过：" + ex.getMessage(), ex);
      }
    }
    int count = plugin.getConfig().getInt("tasks.count", 3);
    if (count < 1 || count > 10 || loaded.size() < count)
      throw new IllegalArgumentException("tasks.count 必须为 1 至 10，且不能大于有效任务数量");
    definitions.clear();
    definitions.putAll(loaded);
  }

  public List<TaskDefinition> random(int count) {
    List<TaskDefinition> list = new ArrayList<>(definitions.values());
    Collections.shuffle(list);
    return list.subList(0, Math.min(Math.max(0, count), list.size()));
  }

  public TaskDefinition get(String id) {
    return definitions.get(id);
  }

  public int size() {
    return definitions.size();
  }
}
