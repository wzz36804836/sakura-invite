package site.sakuragirl.sakurainvite.service;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;
import site.sakuragirl.sakurainvite.SakuraInvitePlugin;
import site.sakuragirl.sakurainvite.db.Database;
import site.sakuragirl.sakurainvite.hook.EconomyHook;
import site.sakuragirl.sakurainvite.model.Models.*;
import site.sakuragirl.sakurainvite.task.TaskRegistry;

public final class InviteService {
  private record Key(TaskType type, String target) {}

  private final SakuraInvitePlugin plugin;
  private final Database db;
  private final TaskRegistry tasks;
  private final Set<UUID> active = new HashSet<>();
  private final Map<UUID, Map<Key, Integer>> pending = new HashMap<>();
  private final Set<UUID> checking = new HashSet<>();
  private final java.security.SecureRandom random = new java.security.SecureRandom();

  public InviteService(
      SakuraInvitePlugin plugin, Database db, TaskRegistry tasks, EconomyHook ignored) {
    this.plugin = plugin;
    this.db = db;
    this.tasks = tasks;
  }

  public String ip(Player p) {
    InetSocketAddress a = p.getAddress();
    return a == null || a.getAddress() == null ? null : a.getAddress().getHostAddress();
  }

  public String randomCode() {
    String prefix =
        plugin.getConfig().getString("invite-code.prefix", "SAKURATREE-").toUpperCase(Locale.ROOT);
    String alphabet =
        plugin
            .getConfig()
            .getString("invite-code.alphabet", "ABCDEFGHJKLMNPQRSTUVWXYZ23456789")
            .toUpperCase(Locale.ROOT);
    int n = plugin.getConfig().getInt("invite-code.random-length", 10);
    if (!prefix.matches("[A-Z0-9_-]*")
        || !alphabet.matches("[A-Z0-9]+")
        || n < 6
        || n > 24
        || prefix.length() + n > 32) throw new IllegalArgumentException("邀请码前缀、字符集或长度配置不合法");
    StringBuilder result = new StringBuilder(prefix);
    for (int i = 0; i < n; i++) result.append(alphabet.charAt(random.nextInt(alphabet.length())));
    return result.toString();
  }

  public CompletableFuture<String> use(Player p, String raw) {
    if (!raw.matches("[A-Za-z0-9_-]{3,32}"))
      return CompletableFuture.completedFuture("<red>邀请码格式不正确。</red>");
    int level = p.getLevel();
    UUID id = p.getUniqueId();
    String name = p.getName(), ip = ip(p);
    int count = plugin.getConfig().getInt("tasks.count", 3);
    boolean anti = plugin.getConfig().getBoolean("anti-abuse.enabled", true);
    int max =
        anti
            ? Math.max(0, plugin.getConfig().getInt("anti-abuse.max-bindings-per-inviter-ip", 1))
            : 0;
    boolean block =
        anti && plugin.getConfig().getBoolean("anti-abuse.block-same-ip-as-inviter", true);
    List<TaskDefinition> chosen = tasks.random(count);
    return db.findCode(raw)
        .thenCompose(
            code -> {
              if (code == null)
                return CompletableFuture.completedFuture("<red>邀请码不存在、未发布或已停用。</red>");
              if (code.type() == CodeType.OFFICIAL)
                return db.queueOfficial(code.code(), id, name, ip)
                    .thenApply(
                        result -> {
                          if (result.equals("OK")) {
                            plugin.runGlobal(() -> plugin.delivery().poll(p));
                            return "<green>奖励已加入待领取列表，系统会尝试发放。</green>";
                          }
                          return "<yellow>" + result + "。</yellow>";
                        });
              if (count < 1 || chosen.size() != count)
                return CompletableFuture.completedFuture("<red>有效任务数量不足。</red>");
              return db.bindPersonal(code, id, name, ip, chosen, max, block)
                  .thenApply(
                      result -> {
                        if (result.success())
                          plugin.runGlobal(
                              () -> {
                                if (p.isOnline()) {
                                  active.add(id);
                                  progress(p, TaskType.LEVEL, "", level);
                                }
                              });
                        return (result.success() ? "<green>" : "<yellow>") + result.message() + "。";
                      });
            });
  }

  public void progress(Player p, TaskType type, String target, int amount) {
    if (!db.ready() || !active.contains(p.getUniqueId())) return;
    Key key = new Key(type, target == null ? "" : target);
    Map<Key, Integer> values = pending.computeIfAbsent(p.getUniqueId(), ignored -> new HashMap<>());
    values.merge(
        key,
        amount,
        (a, b) -> type == TaskType.LEVEL ? Math.max(a, b) : (int) Math.min(1000000L, (long) a + b));
  }

  public void flush() {
    if (!db.ready() || pending.isEmpty()) return;
    Map<UUID, Map<Key, Integer>> batch = new HashMap<>(pending);
    pending.clear();
    for (var player : batch.entrySet())
      for (var value : player.getValue().entrySet()) {
        db.progress(
                player.getKey(),
                value.getKey().type(),
                value.getKey().target(),
                value.getValue(),
                tasks)
            .whenComplete(
                (result, error) ->
                    plugin.runGlobal(
                        () -> {
                          if (error != null) {
                            pending
                                .computeIfAbsent(player.getKey(), ignored -> new HashMap<>())
                                .merge(
                                    value.getKey(),
                                    value.getValue(),
                                    (a, b) ->
                                        value.getKey().type() == TaskType.LEVEL
                                            ? Math.max(a, b)
                                            : (int) Math.min(1000000L, (long) a + b));
                            plugin.logError("进度保存失败，将重试", error);
                            return;
                          }
                          if (result.justCompleted()) {
                            active.remove(player.getKey());
                            claim(result.relationId());
                          }
                        }));
      }
  }

  public void claim(long relation) {
    List<String> commands =
        List.copyOf(plugin.getConfig().getStringList("rewards.inviter-commands"));
    db.queuePersonal(relation, commands)
        .whenComplete(
            (queued, error) -> {
              if (error != null) plugin.logError("生成奖励待领取记录失败", error);
            });
  }

  public void retryClaim(Player p) {
    if (!db.ready() || !checking.add(p.getUniqueId())) return;
    UUID id = p.getUniqueId();
    db.dashboard(id, tasks)
        .whenComplete(
            (d, error) ->
                plugin.runGlobal(
                    () -> {
                      checking.remove(id);
                      if (error != null) {
                        plugin.logError("加载成长任务失败", error);
                        return;
                      }
                      if (!d.tasks().isEmpty()) {
                        if (d.tasks().stream().allMatch(TaskProgress::complete))
                          claim(d.tasks().getFirst().relationId());
                        else {
                          if (p.isOnline()) {
                            active.add(id);
                            progress(p, TaskType.LEVEL, "", p.getLevel());
                          } else active.remove(id);
                        }
                      }
                    }));
  }

  public void forget(UUID id) {
    active.remove(id);
  }
}
