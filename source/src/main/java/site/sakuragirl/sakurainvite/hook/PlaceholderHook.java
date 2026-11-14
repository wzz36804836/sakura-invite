package site.sakuragirl.sakurainvite.hook;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import site.sakuragirl.sakurainvite.SakuraInvitePlugin;
import site.sakuragirl.sakurainvite.model.Models.Dashboard;

public final class PlaceholderHook extends PlaceholderExpansion {
  private record Cached(Dashboard data, long time) {}

  private final SakuraInvitePlugin plugin;
  private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();
  private final Set<UUID> loading = ConcurrentHashMap.newKeySet();

  public PlaceholderHook(SakuraInvitePlugin plugin) {
    this.plugin = plugin;
  }

  @Override
  public @NotNull String getIdentifier() {
    return "sakurainvite";
  }

  @Override
  public @NotNull String getAuthor() {
    return "Sakura_Girl";
  }

  @Override
  public @NotNull String getVersion() {
    return plugin.getPluginMeta().getVersion();
  }

  @Override
  public boolean persist() {
    return true;
  }

  @Override
  public String onRequest(OfflinePlayer p, @NotNull String id) {
    if (p == null || !plugin.database().ready()) return "";
    UUID uuid = p.getUniqueId();
    Cached entry = cache.get(uuid);
    long now = System.currentTimeMillis();
    if ((entry == null || now - entry.time() > 5000) && loading.add(uuid)) {
      plugin
          .database()
          .dashboard(uuid, plugin.tasks())
          .whenComplete(
              (d, e) -> {
                if (e == null) cache.put(uuid, new Cached(d, System.currentTimeMillis()));
                loading.remove(uuid);
              });
    }
    if (cache.size() > 2048) cache.entrySet().removeIf(e -> now - e.getValue().time() > 600000);
    if (entry == null) return "...";
    Dashboard d = entry.data();
    String key = id.toLowerCase(Locale.ROOT);
    return switch (key) {
      case "code" -> d.ownCode() == null ? "" : d.ownCode();
      case "inviter" -> d.inviterName() == null ? "" : d.inviterName();
      case "invitees" -> String.valueOf(d.invitedCount());
      case "invitee_names" -> String.join(", ", d.inviteeNames());
      case "valid_count" -> String.valueOf(d.validCount());
      case "rank" -> d.rank() == 0 ? "未上榜" : String.valueOf(d.rank());
      case "task_progress" ->
          d.tasks().isEmpty()
              ? "0/0"
              : d.tasks().stream().filter(t -> t.complete()).count() + "/" + d.tasks().size();
      default -> {
        if (!key.matches("invitee_[0-9]{1,2}")) yield null;
        int index = Integer.parseInt(key.substring(8)) - 1;
        yield index >= 0 && index < 20 && index < d.inviteeNames().size()
            ? d.inviteeNames().get(index)
            : "";
      }
    };
  }
}
