package site.sakuragirl.sakurainvite;

import java.util.concurrent.CompletionException;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import site.sakuragirl.sakurainvite.command.InviteCommand;
import site.sakuragirl.sakurainvite.db.Database;
import site.sakuragirl.sakurainvite.gui.*;
import site.sakuragirl.sakurainvite.hook.*;
import site.sakuragirl.sakurainvite.listener.ProgressListener;
import site.sakuragirl.sakurainvite.service.*;
import site.sakuragirl.sakurainvite.task.TaskRegistry;

public final class SakuraInvitePlugin extends JavaPlugin {
  private static final MiniMessage MM = MiniMessage.miniMessage();
  private Database database;
  private TaskRegistry tasks;
  private EconomyHook economy;
  private InviteService service;
  private FundingService funding;
  private DeliveryService delivery;
  private GuiManager gui;
  private OfficialGuiManager officialGui;
  private Runnable unregisterPlaceholders = () -> {};
  private long ticks;
  private final java.util.Map<java.util.UUID, Integer> onlineTicks = new java.util.HashMap<>();

  @Override
  public void onEnable() {
    saveDefaultConfig();
    if (!new java.io.File(getDataFolder(), "tasks.yml").exists()) saveResource("tasks.yml", false);
    tasks = new TaskRegistry(this);
    tasks.reload();
    database = new Database(this);
    economy = new EconomyHook(this);
    economy.hook();
    service = new InviteService(this, database, tasks, economy);
    service.randomCode();
    funding = new FundingService(this, economy);
    delivery = new DeliveryService(this, economy);
    gui = new GuiManager(this, service);
    officialGui = new OfficialGuiManager(this);
    Bukkit.getPluginManager().registerEvents(gui, this);
    Bukkit.getPluginManager().registerEvents(officialGui, this);
    Bukkit.getPluginManager().registerEvents(new ProgressListener(this, service), this);
    InviteCommand command = new InviteCommand(this);
    getCommand("invite").setExecutor(command);
    getCommand("invite").setTabCompleter(command);
    if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
      PlaceholderHook placeholders = new PlaceholderHook(this);
      placeholders.register();
      unregisterPlaceholders = placeholders::unregister;
    }
    database
        .connect()
        .whenComplete(
            (v, error) ->
                runGlobal(
                    () -> {
                      if (error != null) {
                        logError("数据库初始化失败，邀请功能保持关闭；请检查配置及大小写冲突", error);
                        return;
                      }
                      getLogger()
                          .info(database.storageName() + " 初始化完成。旧版未预留奖励的关系需邀请人 /invite fund 补足。");
                      funding.recover();
                      for (Player player : Bukkit.getOnlinePlayers()) service.retryClaim(player);
                    }));
    Bukkit.getScheduler()
        .runTaskTimer(
            this,
            () -> {
              if (!database.ready()) return;
              ticks += 20;
              long cooldown =
                  Math.max(
                          1,
                          Math.min(
                              30, getConfig().getLong("tasks.progress-save-cooldown-seconds", 2)))
                      * 20;
              if (ticks % cooldown == 0) service.flush();
              if (ticks % 40 == 0)
                for (Player player : Bukkit.getOnlinePlayers()) delivery.poll(player);
              if (ticks % 200 == 0) {
                funding.recover();
                for (Player player : Bukkit.getOnlinePlayers()) service.retryClaim(player);
              }
              onlineTicks.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
              for (Player player : Bukkit.getOnlinePlayers()) {
                int elapsed = onlineTicks.merge(player.getUniqueId(), 20, Integer::sum);
                if (elapsed >= 1200) {
                  onlineTicks.put(player.getUniqueId(), elapsed - 1200);
                  service.progress(
                      player,
                      site.sakuragirl.sakurainvite.model.Models.TaskType.ONLINE_MINUTES,
                      "",
                      1);
                }
              }
            },
            20,
            20);
    getLogger()
        .info("SakuraInvite v" + getPluginMeta().getVersion() + " 已启用（仅 Paper 1.21.11，不支持 Folia）。");
  }

  @Override
  public void onDisable() {
    if (service != null) service.flush();
    if (gui != null) gui.shutdown();
    if (officialGui != null) officialGui.shutdown();
    unregisterPlaceholders.run();
    if (database != null) database.close();
  }

  public void reloadAll() {
    reloadConfig();
    tasks.reload();
    service.randomCode();
    economy.hook();
  }

  public void message(CommandSender target, String text) {
    target.sendMessage(MM.deserialize(getConfig().getString("messages.prefix", "") + text));
  }

  public void runGlobal(Runnable action) {
    if (!isEnabled()) return;
    if (Bukkit.isPrimaryThread()) action.run();
    else Bukkit.getScheduler().runTask(this, action);
  }

  public void runPlayer(Player player, Runnable action) {
    runGlobal(
        () -> {
          if (player.isOnline()) action.run();
        });
  }

  public void runSender(CommandSender sender, Runnable action) {
    if (sender instanceof Player p) runPlayer(p, action);
    else runGlobal(action);
  }

  public void logError(String message, Throwable error) {
    Throwable root =
        error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    getLogger().log(Level.SEVERE, message, root);
  }

  public void showRecords(Player p) {
    if (!database.ready()) return;
    database
        .records(p.getUniqueId(), 10)
        .thenAccept(
            rows ->
                runPlayer(
                    p,
                    () -> {
                      message(p, "<light_purple>最近 10 条邀请记录：</light_purple>");
                      if (rows.isEmpty()) message(p, "<gray>暂无记录。</gray>");
                      rows.forEach(
                          r ->
                              message(
                                  p,
                                  (r.completed() ? "<green>✔ " : "<yellow>⌛ ")
                                      + r.inviteeName()
                                      + " <gray>使用 "
                                      + r.code()
                                      + "</gray>"));
                    }))
        .exceptionally(
            e -> {
              logError("读取记录失败", e);
              return null;
            });
  }

  public void showTop(Player p) {
    if (!database.ready()) return;
    database
        .top(10)
        .thenAccept(
            rows ->
                runPlayer(
                    p,
                    () -> {
                      message(p, "<gold>有效邀请排行榜（同分并列）：</gold>");
                      if (rows.isEmpty()) message(p, "<gray>暂无记录。</gray>");
                      rows.forEach(
                          r ->
                              message(
                                  p,
                                  "<yellow>#"
                                      + r.rank()
                                      + " <white>"
                                      + r.name()
                                      + " <green>"
                                      + r.validCount()
                                      + " 次"));
                    }))
        .exceptionally(
            e -> {
              logError("读取排行榜失败", e);
              return null;
            });
  }

  public Database database() {
    return database;
  }

  public TaskRegistry tasks() {
    return tasks;
  }

  public InviteService service() {
    return service;
  }

  public FundingService funding() {
    return funding;
  }

  public DeliveryService delivery() {
    return delivery;
  }

  public GuiManager gui() {
    return gui;
  }

  public OfficialGuiManager officialGui() {
    return officialGui;
  }
}
