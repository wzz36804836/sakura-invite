package site.sakuragirl.sakurainvite.command;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.*;
import site.sakuragirl.sakurainvite.SakuraInvitePlugin;

public final class InviteCommand implements CommandExecutor, TabCompleter {
  private final SakuraInvitePlugin plugin;

  public InviteCommand(SakuraInvitePlugin plugin) {
    this.plugin = plugin;
  }

  private void msg(CommandSender s, String text) {
    plugin.message(s, text);
  }

  private void result(CommandSender s, CompletableFuture<Boolean> work, String yes, String no) {
    work.whenComplete(
        (ok, error) ->
            plugin.runSender(
                s,
                () -> {
                  if (error != null) {
                    plugin.logError("管理操作失败", error);
                    msg(s, "<red>操作失败，详情见控制台。</red>");
                  } else msg(s, (ok ? "<green>" : "<yellow>") + (ok ? yes : no));
                }));
  }

  @Override
  public boolean onCommand(
      @NotNull CommandSender s,
      @NotNull Command command,
      @NotNull String alias,
      @NotNull String[] args) {
    if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
      help(s);
      return true;
    }
    if (args[0].equalsIgnoreCase("reload")) {
      if (!s.hasPermission("sakurainvite.reload")) {
        msg(s, "<red>没有权限。</red>");
        return true;
      }
      try {
        plugin.reloadAll();
        msg(s, "<green>配置和任务已重载，数据库参数需重启生效。</green>");
      } catch (Exception e) {
        plugin.logError("配置校验失败", e);
        msg(s, "<red>配置未通过校验，请修正后重载：" + e.getMessage());
      }
      return true;
    }
    if (args[0].equalsIgnoreCase("admin")) return admin(s, args);
    if (!(s instanceof Player p)) {
      msg(s, "<red>该命令只能在游戏内使用。</red>");
      return true;
    }
    if (!plugin.database().ready()) {
      msg(p, "<yellow>数据库尚未就绪。</yellow>");
      return true;
    }
    switch (args[0].toLowerCase(Locale.ROOT)) {
      case "gui" -> plugin.gui().openCenter(p);
      case "create" -> plugin.gui().openCreate(p);
      case "fund" -> plugin.gui().openFund(p);
      case "info" -> plugin.gui().openInfo(p);
      case "withdraw" -> {
        result(
            p,
            plugin.database().withdrawUnused(p.getUniqueId(), p.getName()),
            "一份未预留奖励已退回待领取列表，使用 /invite claim 领取",
            "没有可退回的未预留库存；已绑定的奖励不可取回");
      }
      case "claim" -> {
        plugin.service().retryClaim(p);
        plugin.delivery().poll(p);
        msg(p, "<yellow>正在检查待领取奖励；请预留背包空间。</yellow>");
      }
      case "use" -> {
        if (!p.hasPermission("sakurainvite.use")) {
          msg(p, "<red>没有使用权限。</red>");
          break;
        }
        if (args.length != 2) {
          msg(p, "<yellow>/invite use <邀请码></yellow>");
          break;
        }
        plugin
            .service()
            .use(p, args[1])
            .whenComplete(
                (message, error) ->
                    plugin.runPlayer(
                        p,
                        () -> {
                          if (error != null) {
                            plugin.logError("绑定/兑换失败", error);
                            msg(p, "<red>操作失败，请稍后重试。</red>");
                          } else msg(p, message);
                        }));
      }
      case "records" -> plugin.showRecords(p);
      case "top", "rank" -> plugin.showTop(p);
      default -> help(p);
    }
    return true;
  }

  private boolean admin(CommandSender s, String[] a) {
    if (!s.hasPermission("sakurainvite.admin")) {
      msg(s, "<red>没有管理权限。</red>");
      return true;
    }
    if (a.length < 2) {
      adminHelp(s);
      return true;
    }
    String action = a[1].toLowerCase(Locale.ROOT);
    if (action.equals("funding")) {
      if (a.length == 2) {
        var rows = plugin.funding().reviews();
        msg(s, "<gold>资金异常日志（请先核对实际扣款、物品）：</gold>");
        if (rows.isEmpty()) msg(s, "<gray>无待核对预存。</gray>");
        rows.forEach(row -> msg(s, "<gray>" + row));
        return true;
      }
      if (a.length != 4 || !Set.of("commit", "refund", "items", "discard").contains(a[3])) {
        msg(s, "<yellow>/invite admin funding <编号> <commit|refund|items|discard></yellow>");
        return true;
      }
      if (!plugin.database().ready()) {
        msg(s, "<red>数据库未就绪。</red>");
        return true;
      }
      boolean ok = plugin.funding().resolve(a[2], a[3]);
      plugin
          .getLogger()
          .warning("资金日志人工处理：" + s.getName() + " " + a[2] + " " + a[3] + " result=" + ok);
      msg(s, ok ? "<green>处理已受理，请确认后续日志。</green>" : "<yellow>编号/状态无效或处理失败。</yellow>");
      return true;
    }
    if (!plugin.database().ready()) {
      msg(s, "<yellow>数据库尚未就绪。</yellow>");
      return true;
    }
    if (action.equals("deliveries")) {
      plugin
          .database()
          .reviewDeliveries()
          .whenComplete(
              (rows, error) ->
                  plugin.runSender(
                      s,
                      () -> {
                        if (error != null) {
                          plugin.logError("查询异常奖励失败", error);
                          return;
                        }
                        msg(s, "<gold>待核对奖励，最多 30 条：</gold>");
                        if (rows.isEmpty()) msg(s, "<gray>无异常奖励。</gray>");
                        rows.forEach(row -> msg(s, "<gray>" + row));
                      }));
      return true;
    }
    if (action.equals("resolve")) {
      if (a.length != 4
          || !Set.of("retry", "complete").contains(a[3])
          || !a[2].matches("[A-Za-z0-9-]{1,100}")) {
        msg(
            s,
            "<yellow>/invite admin resolve <编号> <retry|complete>（先核对到账，retry 会重新发全部奖励）</yellow>");
        return true;
      }
      plugin.getLogger().warning("奖励人工处理：" + s.getName() + " " + a[2] + " " + a[3]);
      result(
          s,
          plugin
              .database()
              .deliveryStatus(
                  a[2],
                  "REVIEW",
                  a[3].equals("retry") ? "PENDING" : "DELIVERED",
                  "管理员 " + s.getName() + " 确认：" + a[3]),
          "异常奖励状态已更新",
          "编号不存在或不是待核对状态");
      return true;
    }
    if (action.equals("official")) {
      if (s instanceof Player p) plugin.officialGui().openList(p);
      else msg(s, "<yellow>请在游戏内打开管理界面。</yellow>");
      return true;
    }
    if (a.length < 3 || !a[2].matches("[A-Za-z0-9_-]{3,32}")) {
      adminHelp(s);
      return true;
    }
    String code = a[2].toUpperCase(Locale.ROOT);
    switch (action) {
      case "createofficial" -> {
        if (!(s instanceof Player p)) {
          msg(s, "<yellow>请在游戏内创建并配置奖励。</yellow>");
          break;
        }
        plugin
            .database()
            .createOfficial(code)
            .whenComplete(
                (ok, error) ->
                    plugin.runPlayer(
                        p,
                        () -> {
                          if (error != null) {
                            plugin.logError("创建草稿失败", error);
                            msg(s, "<red>创建失败。</red>");
                          } else if (!ok) msg(s, "<yellow>代码已经存在。</yellow>");
                          else {
                            msg(s, "<green>已创建未发布草稿，请配置奖励后 publish。</green>");
                            plugin.officialGui().openEditor(p, code);
                          }
                        }));
      }
      case "editofficial" -> {
        if (s instanceof Player p) plugin.officialGui().openEditor(p, code);
        else msg(s, "<yellow>请在游戏内编辑。</yellow>");
      }
      case "publish" -> result(s, plugin.database().publish(code, true), "官方码已发布", "未找到官方码或奖励为空");
      case "deletecode", "disable" ->
          result(s, plugin.database().deleteCode(code), "邀请码已停用；已预留的奖励仍会结算", "未找到邀请码");
      case "limits" -> {
        if (a.length != 5) {
          msg(s, "<yellow>/invite admin limits <码> <总名额，0不限> <有效分钟，0永久></yellow>");
          break;
        }
        try {
          int count = Integer.parseInt(a[3]), minutes = Integer.parseInt(a[4]);
          if (count < 0 || count > 1000000 || minutes < 0 || minutes > 5256000)
            throw new IllegalArgumentException();
          long expiry = minutes == 0 ? 0 : System.currentTimeMillis() + minutes * 60000L;
          result(s, plugin.database().officialLimits(code, count, expiry), "官方码限制已更新", "未找到官方码");
        } catch (IllegalArgumentException e) {
          msg(s, "<red>名额范围 0–1000000，有效分钟范围 0–5256000。</red>");
        }
      }
      default -> adminHelp(s);
    }
    return true;
  }

  private void help(CommandSender s) {
    msg(s, "<light_purple>===== SakuraInvite 1.3 =====</light_purple>");
    msg(s, "<yellow>/invite gui <gray>邀请中心；/invite info 查看自己的代码和库存");
    msg(s, "<yellow>/invite create <gray>设置模板并从背包预存一份奖励");
    msg(s, "<yellow>/invite fund <gray>补充一份；/invite withdraw 退回一份未预留奖励");
    msg(s, "<yellow>/invite use <码> <gray>绑定个人码 / 兑换官方码");
    msg(s, "<yellow>/invite claim <gray>检查待领取奖励；/invite records 邀请记录");
    msg(s, "<yellow>/invite top <gray>有效邀请排行榜");
    if (s.hasPermission("sakurainvite.admin")) adminHelp(s);
  }

  private void adminHelp(CommandSender s) {
    msg(s, "<light_purple>/invite admin official | createofficial <码> | editofficial <码>");
    msg(s, "<light_purple>/invite admin publish <码> | deletecode <码>");
    msg(s, "<light_purple>/invite admin limits <码> <总名额> <有效分钟>");
    msg(s, "<light_purple>/invite admin deliveries | resolve <编号> <retry|complete>");
    msg(s, "<light_purple>/invite admin funding [编号 commit|refund|items|discard]");
    msg(s, "<gray>资金处理：commit=确认已扣款入库；refund=退物品和金币；items=仅退物品；discard=已人工处理/未扣取。务必先核对。");
    msg(s, "<light_purple>/invite reload <gray>重载配置，数据库参数需重启");
  }

  @Override
  public @Nullable List<String> onTabComplete(
      @NotNull CommandSender s,
      @NotNull Command command,
      @NotNull String alias,
      @NotNull String[] a) {
    List<String> values = new ArrayList<>();
    if (a.length == 1) {
      values.addAll(
          List.of(
              "help",
              "gui",
              "create",
              "fund",
              "info",
              "withdraw",
              "claim",
              "use",
              "records",
              "top"));
      if (s.hasPermission("sakurainvite.admin")) values.add("admin");
      if (s.hasPermission("sakurainvite.reload")) values.add("reload");
    } else if (a.length == 2
        && a[0].equalsIgnoreCase("admin")
        && s.hasPermission("sakurainvite.admin"))
      values =
          List.of(
              "official",
              "createofficial",
              "editofficial",
              "publish",
              "deletecode",
              "limits",
              "deliveries",
              "resolve",
              "funding");
    else if (a.length == 4 && a[1].equalsIgnoreCase("resolve"))
      values = List.of("retry", "complete");
    else if (a.length == 4 && a[1].equalsIgnoreCase("funding"))
      values = List.of("commit", "refund", "items", "discard");
    String prefix = a[a.length - 1].toLowerCase(Locale.ROOT);
    return values.stream().filter(v -> v.startsWith(prefix)).toList();
  }
}
