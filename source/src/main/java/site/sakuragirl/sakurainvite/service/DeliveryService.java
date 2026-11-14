package site.sakuragirl.sakurainvite.service;

import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import site.sakuragirl.sakurainvite.SakuraInvitePlugin;
import site.sakuragirl.sakurainvite.db.Database.Delivery;
import site.sakuragirl.sakurainvite.hook.EconomyHook;
import site.sakuragirl.sakurainvite.util.*;

/** Durable outbox: pending jobs survive logout; ambiguous side effects require explicit review. */
public final class DeliveryService {
  private final SakuraInvitePlugin plugin;
  private final EconomyHook economy;
  private final Set<UUID> loading = new HashSet<>();
  private final Map<UUID, Long> notices = new HashMap<>();

  public DeliveryService(SakuraInvitePlugin plugin, EconomyHook economy) {
    this.plugin = plugin;
    this.economy = economy;
  }

  public void poll(Player player) {
    if (!plugin.database().ready() || !player.isOnline() || !loading.add(player.getUniqueId()))
      return;
    UUID id = player.getUniqueId();
    plugin
        .database()
        .deliveries(id)
        .whenComplete(
            (jobs, error) ->
                plugin.runGlobal(
                    () -> {
                      if (error != null) {
                        loading.remove(id);
                        plugin.logError("查询待领取奖励失败", error);
                        return;
                      }
                      if (!player.isOnline() || jobs.isEmpty()) {
                        loading.remove(id);
                        return;
                      }
                      deliver(player, jobs.getFirst());
                    }));
  }

  private List<ItemStack> items(Delivery job) throws Exception {
    Amounts.validate(job.money());
    return ItemCodec.decode(job.items());
  }

  private boolean fits(Player player, List<ItemStack> items) {
    Inventory trial = Bukkit.createInventory(null, 36);
    trial.setContents(
        Arrays.stream(player.getInventory().getStorageContents())
            .map(i -> i == null ? null : i.clone())
            .toArray(ItemStack[]::new));
    return trial.addItem(items.stream().map(ItemStack::clone).toArray(ItemStack[]::new)).isEmpty();
  }

  private void deliver(Player player, Delivery job) {
    UUID id = player.getUniqueId();
    List<ItemStack> items;
    try {
      items = items(job);
    } catch (Exception e) {
      review(job, "奖励快照无法解析：" + e.getMessage(), "PENDING", id);
      return;
    }
    if (!fits(player, items)) {
      loading.remove(id);
      long now = System.currentTimeMillis();
      if (now - notices.getOrDefault(id, 0L) > 60000) {
        plugin.message(player, "<yellow>你有待领取奖励，请清理背包后使用 /invite claim。</yellow>");
        notices.put(id, now);
      }
      return;
    }
    if (job.money().signum() > 0 && !economy.available()) {
      loading.remove(id);
      return;
    }
    plugin
        .database()
        .deliveryStatus(job.id(), "PENDING", "PROCESSING", "开始发放")
        .whenComplete(
            (acquired, error) ->
                plugin.runGlobal(
                    () -> {
                      if (error != null || !Boolean.TRUE.equals(acquired)) {
                        loading.remove(id);
                        if (error != null) plugin.logError("锁定奖励失败", error);
                        return;
                      }
                      if (!player.isOnline() || !fits(player, items)) {
                        plugin
                            .database()
                            .deliveryStatus(job.id(), "PROCESSING", "PENDING", "离线或背包不足，尚未发放")
                            .whenComplete((v, e) -> plugin.runGlobal(() -> loading.remove(id)));
                        return;
                      }
                      try {
                        if (job.money().signum() > 0) {
                          if (!economy.deposit(player, job.money())) {
                            plugin
                                .database()
                                .deliveryStatus(job.id(), "PROCESSING", "PENDING", "经济服务拒绝，尚未发放")
                                .whenComplete((v, e) -> plugin.runGlobal(() -> loading.remove(id)));
                            return;
                          }
                        }
                        for (ItemStack item : items) {
                          if (!player.getInventory().addItem(item.clone()).isEmpty())
                            throw new IllegalStateException("背包接收结果与预检查不符");
                        }
                        // Persist inventory changes through the server player save before marking
                        // delivered.
                        if (!items.isEmpty()) player.saveData();
                        for (String command : job.commands()) {

                          if (!Bukkit.dispatchCommand(
                              Bukkit.getConsoleSender(),
                              command
                                  .replace("%player_name%", player.getName())
                                  .replace("%code%", job.code())))
                            throw new IllegalStateException("控制台未识别命令：" + command);
                        }
                        plugin
                            .database()
                            .deliveryStatus(job.id(), "PROCESSING", "DELIVERED", "发放完成")
                            .whenComplete(
                                (ok, e) ->
                                    plugin.runGlobal(
                                        () -> {
                                          loading.remove(id);
                                          if (e != null || !Boolean.TRUE.equals(ok)) {
                                            plugin
                                                .getLogger()
                                                .severe("奖励已执行但最终状态未确认：" + job.id() + "；请核对，勿重复补发");
                                            return;
                                          }
                                          if (player.isOnline())
                                            plugin.message(player, "<green>邀请奖励已到账！</green>");
                                        }));
                      } catch (Exception e) {
                        // Even if a third-party economy call threw, its side effects are unknown.
                        review(job, "执行异常，可能已部分到账：" + e.getMessage(), "PROCESSING", id);
                        plugin.logError("奖励需要人工核对：" + job.id(), e);
                      }
                    }));
  }

  private void review(Delivery job, String detail, String from, UUID player) {
    plugin
        .database()
        .deliveryStatus(job.id(), from, "REVIEW", detail)
        .whenComplete((v, e) -> plugin.runGlobal(() -> loading.remove(player)));
    plugin.getLogger().warning("奖励待核对：" + job.id() + " " + detail);
  }

  public void forget(UUID id) {
    notices.remove(id);
  }
}
