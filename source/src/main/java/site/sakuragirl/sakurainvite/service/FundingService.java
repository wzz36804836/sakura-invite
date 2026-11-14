package site.sakuragirl.sakurainvite.service;

import static java.nio.file.StandardOpenOption.*;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import org.bukkit.entity.Player;
import site.sakuragirl.sakurainvite.SakuraInvitePlugin;
import site.sakuragirl.sakurainvite.db.Database.Funding;
import site.sakuragirl.sakurainvite.hook.EconomyHook;
import site.sakuragirl.sakurainvite.util.Amounts;

/**
 * Local write-ahead journal bridges player assets and the SQL transaction. Interrupted asset debits
 * are reviewed, never blindly repeated/refunded.
 */
public final class FundingService {
  private record Entry(
      String id,
      String player,
      String name,
      String ip,
      String code,
      String money,
      String items,
      boolean create,
      String phase) {
    Entry phase(String value) {
      return new Entry(id, player, name, ip, code, money, items, create, value);
    }

    Funding funding() {
      return new Funding(
          id,
          UUID.fromString(player),
          name,
          ip,
          code,
          Amounts.parse(money),
          Base64.getDecoder().decode(items),
          create);
    }
  }

  private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
  private final SakuraInvitePlugin plugin;
  private final EconomyHook economy;
  private final Path folder;
  private final Map<String, Entry> entries = new LinkedHashMap<>();
  private final Set<String> running = new HashSet<>();

  public FundingService(SakuraInvitePlugin plugin, EconomyHook economy) {
    this.plugin = plugin;
    this.economy = economy;
    folder = plugin.getDataFolder().toPath().resolve("funding-journal");
    try {
      Files.createDirectories(folder);
      try (var files = Files.list(folder)) {
        for (Path file :
            files
                .filter(p -> p.getFileName().toString().matches("[0-9a-f-]{36}\\.json"))
                .toList()) {
          if (Files.size(file) > 4 * 1024 * 1024)
            throw new IllegalStateException("资金日志过大：" + file.getFileName());
          Entry e = JSON.fromJson(Files.readString(file), Entry.class);
          UUID.fromString(e.id());
          if (!file.getFileName().toString().equals(e.id() + ".json")
              || !Set.of("REVIEW", "DEBITED", "REFUND", "REFUND_ITEMS").contains(e.phase())
              || !e.code().matches("[A-Za-z0-9_-]{3,32}"))
            throw new IllegalStateException("资金日志字段异常：" + file.getFileName());
          e.funding();
          entries.put(e.id(), e);
        }
      }
    } catch (Exception e) {
      throw new IllegalStateException("无法读取资金日志，请勿删除旧日志", e);
    }
  }

  private void persist(Entry e) throws Exception {
    Path temp = folder.resolve(e.id() + ".tmp"), target = folder.resolve(e.id() + ".json");
    byte[] bytes = JSON.toJson(e).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    try (FileChannel channel = FileChannel.open(temp, CREATE, TRUNCATE_EXISTING, WRITE)) {
      ByteBuffer data = ByteBuffer.wrap(bytes);
      while (data.hasRemaining()) channel.write(data);
      channel.force(true);
    }
    try {
      Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException unsupported) {
      Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
    }
    entries.put(e.id(), e);
  }

  public boolean begin(
      Player p, String code, BigDecimal amount, byte[] items, boolean create, Runnable detach) {
    if (!plugin.database().ready() || !p.hasPermission("sakurainvite.create")) {
      plugin.message(p, "<red>数据库未就绪或没有创建权限。</red>");
      return false;
    }
    amount = Amounts.validate(amount);
    if (amount.signum() > 0 && (!economy.available() || !economy.has(p, amount))) {
      plugin.message(p, "<red>经济服务不可用或余额不足。</red>");
      return false;
    }
    Entry e =
        new Entry(
            UUID.randomUUID().toString(),
            p.getUniqueId().toString(),
            p.getName(),
            plugin.service().ip(p),
            code,
            amount.toPlainString(),
            Base64.getEncoder().encodeToString(items),
            create,
            "REVIEW");
    boolean journaled = false;
    try {
      // Any interruption after this record requires checking actual assets, not an automatic second
      // debit.
      persist(e);
      journaled = true;
      detach.run();
      p.saveData();
      if (amount.signum() > 0 && !economy.withdraw(p, amount)) {
        e = e.phase("REFUND_ITEMS");
        persist(e);
        submit(e);
        return true;
      }
      e = e.phase("DEBITED");
      persist(e);
      submit(e);
      return true;
    } catch (Exception error) {
      if (journaled) {
        plugin.message(p, "<red>预存操作中断，已记录待核对编号：" + e.id());
        plugin.logError("预存需要人工核对：" + e.id(), error);
        return true;
      }
      plugin.logError("无法创建预存日志，未收取奖励", error);
      plugin.message(p, "<red>无法保存预存日志，操作已停止。</red>");
      return false;
    }
  }

  private void submit(Entry e) {
    if (!plugin.database().ready() || !running.add(e.id())) return;
    boolean refund = e.phase().equals("REFUND") || e.phase().equals("REFUND_ITEMS");
    plugin
        .database()
        .commitFunding(e.funding(), refund, !e.phase().equals("REFUND_ITEMS"))
        .whenComplete(
            (result, error) ->
                plugin.runGlobal(
                    () -> {
                      running.remove(e.id());
                      if (error != null) {
                        plugin.logError("预存尚未提交，将保留日志重试：" + e.id(), error);
                        return;
                      }
                      try {
                        Files.deleteIfExists(folder.resolve(e.id() + ".json"));
                        entries.remove(e.id());
                      } catch (Exception ex) {
                        plugin.logError("清理已提交日志失败（可安全重试）", ex);
                      }
                      Player player = org.bukkit.Bukkit.getPlayer(UUID.fromString(e.player()));
                      if (player != null) {
                        String msg =
                            switch (result) {
                              case "CREATED" -> "已创建邀请码并预存一份奖励：" + e.code();
                              case "FUNDED" -> "已补充一份奖励：" + e.code();
                              case "LEGACY_FUNDED" -> "已为一位旧版待结算玩家补足奖励";
                              default -> "未能预存，物品/金币已转入待领取退款";
                            };
                        plugin.message(
                            player,
                            "<green>" + msg + "。使用 /invite info 查看，/invite claim 领取待发奖励。</green>");
                      }
                    }));
  }

  public void recover() {
    for (Entry e : new ArrayList<>(entries.values())) if (!e.phase().equals("REVIEW")) submit(e);
  }

  public List<String> reviews() {
    return entries.values().stream()
        .filter(e -> e.phase().equals("REVIEW"))
        .map(e -> e.id() + " | " + e.name() + " | " + e.money() + " 金币 | " + e.code())
        .toList();
  }

  public boolean resolve(String id, String action) {
    Entry e = entries.get(id);
    if (e == null || !e.phase().equals("REVIEW") || running.contains(id)) return false;
    String phase =
        switch (action) {
          case "commit" -> "DEBITED";
          case "refund" -> "REFUND";
          case "items" -> "REFUND_ITEMS";
          case "discard" -> "DISCARD";
          default -> null;
        };
    if (phase == null) return false;
    try {
      if (phase.equals("DISCARD")) {
        Files.deleteIfExists(folder.resolve(id + ".json"));
        entries.remove(id);
      } else {
        e = e.phase(phase);
        persist(e);
        submit(e);
      }
      return true;
    } catch (Exception ex) {
      plugin.logError("处理预存日志失败", ex);
      return false;
    }
  }
}
