package site.sakuragirl.sakurainvite.gui;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import site.sakuragirl.sakurainvite.SakuraInvitePlugin;
import site.sakuragirl.sakurainvite.model.Models.*;
import site.sakuragirl.sakurainvite.service.InviteService;
import site.sakuragirl.sakurainvite.util.*;

/**
 * All displayed reward items are protected copies. Assets leave the player only at confirmation.
 */
@SuppressWarnings("deprecation")
public final class GuiManager implements Listener {
  private static final MiniMessage MM = MiniMessage.miniMessage();

  private enum Kind {
    CENTER,
    INFO,
    CREATE,
    FUND
  }

  private static final class Holder implements InventoryHolder {
    final Kind kind;
    final UUID owner;

    Holder(Kind kind, UUID owner) {
      this.kind = kind;
      this.owner = owner;
    }

    public Inventory getInventory() {
      return null;
    }
  }

  private static final class Session {
    Inventory inventory;
    BigDecimal money = BigDecimal.ZERO;
    String code;
    boolean funding;
    boolean submitting;
    volatile boolean input;
  }

  private final SakuraInvitePlugin plugin;
  private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

  public GuiManager(SakuraInvitePlugin plugin, InviteService ignored) {
    this.plugin = plugin;
  }

  private boolean allowed(Player p) {
    if (p.hasPermission("sakurainvite.create") && plugin.database().ready()) return true;
    plugin.message(p, "<red>没有创建权限或数据库尚未就绪。</red>");
    return false;
  }

  public void openCenter(Player p) {
    if (!plugin.database().ready()) {
      plugin.message(p, "<yellow>数据库尚未就绪。</yellow>");
      return;
    }
    plugin
        .database()
        .dashboard(p.getUniqueId(), plugin.tasks())
        .thenAccept(
            d ->
                plugin.runPlayer(
                    p,
                    () -> {
                      Inventory inv =
                          Bukkit.createInventory(
                              new Holder(Kind.CENTER, p.getUniqueId()),
                              45,
                              MM.deserialize(plugin.getConfig().getString("gui.title", "樱花邀请中心")));
                      fill(inv, 0);
                      inv.setItem(
                          10,
                          item(
                              Material.NAME_TAG,
                              "<light_purple>我的邀请码",
                              List.of(
                                  d.ownCode() == null ? "<gray>尚未创建" : "<yellow>" + d.ownCode(),
                                  "<gray>点击查看详情 / 创建")));
                      List<String> lore = new ArrayList<>();
                      for (TaskProgress t : d.tasks())
                        lore.add(
                            (t.complete() ? "<green>✔ " : "<yellow>• ")
                                + t.name()
                                + " "
                                + Math.min(t.progress(), t.target())
                                + "/"
                                + t.target());
                      if (lore.isEmpty()) lore.add("<gray>使用个人码后分配任务");
                      inv.setItem(12, item(Material.WRITABLE_BOOK, "<aqua>成长任务", lore));
                      inv.setItem(
                          14,
                          item(
                              Material.BOOK,
                              "<gold>邀请记录",
                              List.of(
                                  "<gray>已邀请：" + d.invitedCount(),
                                  "<green>有效邀请：" + d.validCount(),
                                  "<gray>点击查看聊天记录")));
                      inv.setItem(
                          16,
                          item(
                              Material.NETHER_STAR,
                              "<yellow>排行榜",
                              List.of(d.rank() == 0 ? "<gray>尚未上榜" : "<gray>你的排名 #" + d.rank())));
                      inv.setItem(22, item(Material.CLOCK, "<aqua>刷新进度", List.of()));
                      inv.setItem(
                          31,
                          item(
                              Material.PLAYER_HEAD,
                              "<light_purple>邀请关系",
                              List.of(
                                  d.inviterName() == null
                                      ? "<gray>尚未绑定"
                                      : "<gray>邀请人：" + d.inviterName())));
                      inv.setItem(
                          40, item(Material.CHEST, "<green>领取待发奖励", List.of("<gray>背包不足或离线时保留奖励")));
                      p.openInventory(inv);
                    }))
        .exceptionally(
            e -> {
              plugin.logError("读取邀请中心失败", e);
              return null;
            });
  }

  public void openInfo(Player p) {
    if (!plugin.database().ready()) return;
    plugin
        .database()
        .ownCode(p.getUniqueId())
        .thenAccept(
            code -> {
              if (code == null) {
                plugin.runPlayer(
                    p, () -> plugin.message(p, "<yellow>尚未创建，请 /invite create。</yellow>"));
                return;
              }
              plugin
                  .database()
                  .stock(code.code())
                  .thenAccept(
                      stock ->
                          plugin.runPlayer(
                              p,
                              () -> {
                                Inventory inv =
                                    Bukkit.createInventory(
                                        new Holder(Kind.INFO, p.getUniqueId()),
                                        54,
                                        MM.deserialize("<light_purple>邀请码奖励详情"));
                                try {
                                  int i = 0;
                                  for (ItemStack it : ItemCodec.decode(code.items()))
                                    inv.setItem(i++, it.clone());
                                } catch (Exception e) {
                                  plugin.message(p, "<red>奖励读取失败，请联系管理员。</red>");
                                  return;
                                }
                                fill(inv, 45);
                                inv.setItem(45, item(Material.ARROW, "<yellow>返回", List.of()));
                                inv.setItem(
                                    48,
                                    item(
                                        Material.NAME_TAG,
                                        "<yellow>" + code.code(),
                                        List.of(
                                            "<gray>点击在聊天中显示可复制的代码",
                                            code.active() ? "<green>已启用" : "<red>已停用")));
                                inv.setItem(
                                    49,
                                    item(
                                        Material.GOLD_INGOT,
                                        "<gold>补充一份奖励",
                                        List.of(
                                            "<gray>每份金币：" + code.money().toPlainString(),
                                            "<gray>可供新绑定：" + stock + " 份",
                                            "<gray>上方为每份所需物品模板")));
                                inv.setItem(50, item(Material.CHEST, "<green>领取待发奖励", List.of()));
                                inv.setItem(
                                    51,
                                    item(
                                        Material.HOPPER,
                                        "<yellow>退回一份未预留奖励",
                                        List.of("<gray>已绑定预留的奖励不可取回")));
                                p.openInventory(inv);
                              }));
            })
        .exceptionally(
            e -> {
              plugin.logError("读取邀请码详情失败", e);
              return null;
            });
  }

  public void openCreate(Player p) {
    if (!allowed(p)) return;
    Session existing = sessions.get(p.getUniqueId());
    if (existing != null) {
      p.openInventory(existing.inventory);
      return;
    }
    plugin
        .database()
        .ownCode(p.getUniqueId())
        .thenAccept(
            code ->
                plugin.runPlayer(
                    p,
                    () -> {
                      if (!allowed(p)) return;
                      if (code != null) {
                        openInfo(p);
                        return;
                      }
                      openEditor(p, null);
                    }));
  }

  public void openFund(Player p) {
    if (!allowed(p)) return;
    plugin
        .database()
        .ownCode(p.getUniqueId())
        .thenAccept(
            code ->
                plugin.runPlayer(
                    p,
                    () -> {
                      if (!allowed(p)) return;
                      if (code == null) {
                        plugin.message(p, "<yellow>请先 /invite create。</yellow>");
                        return;
                      }
                      openEditor(p, code);
                    }));
  }

  private void openEditor(Player p, InviteCode code) {
    p.closeInventory();
    Session s = new Session();
    s.funding = code != null;
    s.code = code == null ? plugin.service().randomCode() : code.code();
    s.money = code == null ? BigDecimal.ZERO : code.money();
    s.inventory =
        Bukkit.createInventory(
            new Holder(s.funding ? Kind.FUND : Kind.CREATE, p.getUniqueId()),
            54,
            MM.deserialize(s.funding ? "<light_purple>补充一份新人奖励" : "<light_purple>创建邀请码 · 每份奖励"));
    if (code != null)
      try {
        int i = 0;
        for (ItemStack it : ItemCodec.decode(code.items())) s.inventory.setItem(i++, it.clone());
      } catch (Exception e) {
        plugin.message(p, "<red>旧奖励无法解析，请联系管理员。</red>");
        return;
      }
    sessions.put(p.getUniqueId(), s);
    decorate(s);
    p.openInventory(s.inventory);
  }

  private void decorate(Session s) {
    fill(s.inventory, 45);
    s.inventory.setItem(45, item(Material.ARROW, "<yellow>取消", List.of("<gray>模板不收物品，取消无需返还")));
    s.inventory.setItem(
        49,
        item(
            Material.GOLD_INGOT,
            s.funding ? "<gold>每份金币（固定）" : "<gold>设置每份金币",
            List.of("<gray>当前：" + s.money.toPlainString())));
    s.inventory.setItem(
        52,
        item(
            Material.LIME_DYE,
            "<green>确认并从背包预存一份",
            List.of("<gray>确认时扣除上方物品与金币", "<gray>绑定时预留一份，用完需再补充")));
    s.inventory.setItem(
        53,
        item(
            Material.PAPER,
            "<aqua>上方均为模板复制品",
            List.of(
                s.funding ? "<gray>补充时使用原来的每份奖励" : "<gray>左键复制光标 / 主手物品，右键清空",
                "<gray>确认前将光标物品放回背包")));
  }

  @EventHandler
  public void click(InventoryClickEvent e) {
    if (!(e.getWhoClicked() instanceof Player p)
        || !(e.getView().getTopInventory().getHolder() instanceof Holder h)) return;
    if (!h.owner.equals(p.getUniqueId())) {
      e.setCancelled(true);
      return;
    }
    if (h.kind == Kind.CENTER || h.kind == Kind.INFO) {
      e.setCancelled(true);
      if (e.getClickedInventory() != e.getView().getTopInventory()) return;
      int slot = e.getSlot();
      if (h.kind == Kind.CENTER)
        switch (slot) {
          case 10 -> openCreate(p);
          case 14 -> plugin.showRecords(p);
          case 16 -> plugin.showTop(p);
          case 22 -> openCenter(p);
          case 40 -> plugin.delivery().poll(p);
          default -> {}
        }
      else
        switch (slot) {
          case 45 -> openCenter(p);
          case 48 -> copyCode(p);
          case 49 -> openFund(p);
          case 50 -> plugin.delivery().poll(p);
          case 51 ->
              plugin
                  .database()
                  .withdrawUnused(p.getUniqueId(), p.getName())
                  .whenComplete(
                      (ok, error) ->
                          plugin.runPlayer(
                              p,
                              () -> {
                                plugin.message(
                                    p,
                                    error == null && Boolean.TRUE.equals(ok)
                                        ? "<green>一份奖励已转入待领取退款。</green>"
                                        : "<yellow>没有可退回库存，或操作失败。</yellow>");
                                openInfo(p);
                              }));
          default -> {}
        }
      return;
    }
    Session s = sessions.get(p.getUniqueId());
    if (s == null
        || s.inventory != e.getView().getTopInventory()
        || s.submitting
        || !p.hasPermission("sakurainvite.create")) {
      e.setCancelled(true);
      return;
    }
    if (e.getClickedInventory() != e.getView().getTopInventory()) {
      if (e.isShiftClick() || e.getAction() == InventoryAction.COLLECT_TO_CURSOR)
        e.setCancelled(true);
      return;
    }
    e.setCancelled(true);
    int slot = e.getSlot();
    if (slot < 45) {
      if (!s.funding) {
        if (e.isRightClick()) s.inventory.setItem(slot, null);
        else if (e.isLeftClick()) {
          ItemStack source = e.getCursor();
          if (source == null || source.getType().isAir())
            source = p.getInventory().getItemInMainHand();
          s.inventory.setItem(slot, source.getType().isAir() ? null : source.clone());
        }
      }
      return;
    }
    if (slot == 45) p.closeInventory();
    else if (slot == 49 && !s.funding) {
      s.input = true;
      p.closeInventory();
      plugin.message(p, "<yellow>请输入每份金币金额（最多 10 亿、两位小数），cancel 取消。</yellow>");
    } else if (slot == 52) submit(p, s);
  }

  private void copyCode(Player p) {
    plugin
        .database()
        .ownCode(p.getUniqueId())
        .thenAccept(
            c ->
                plugin.runPlayer(
                    p,
                    () -> {
                      if (c != null)
                        p.sendMessage(
                            Component.text("邀请码：" + c.code() + "（点击复制）")
                                .clickEvent(ClickEvent.copyToClipboard(c.code())));
                    }));
  }

  private void submit(Player p, Session s) {
    if (s.submitting || !allowed(p)) return;
    if (!p.getItemOnCursor().getType().isAir()) {
      plugin.message(p, "<yellow>请先将光标物品放回背包。</yellow>");
      return;
    }
    try {
      List<ItemStack> rewards = new ArrayList<>();
      for (int i = 0; i < 45; i++) {
        ItemStack it = s.inventory.getItem(i);
        if (it != null && !it.getType().isAir()) rewards.add(it.clone());
      }
      if (rewards.isEmpty() && s.money.signum() == 0) {
        plugin.message(p, "<red>请设置至少一种奖励。</red>");
        return;
      }
      Inventory after = Bukkit.createInventory(null, 36);
      after.setContents(
          Arrays.stream(p.getInventory().getStorageContents())
              .map(i -> i == null ? null : i.clone())
              .toArray(ItemStack[]::new));
      if (!after
          .removeItem(rewards.stream().map(ItemStack::clone).toArray(ItemStack[]::new))
          .isEmpty()) {
        plugin.message(p, "<red>背包中的奖励物品不足，请按模板补齐。</red>");
        return;
      }
      byte[] data = ItemCodec.encode(rewards);
      s.submitting = true;
      boolean accepted =
          plugin
              .funding()
              .begin(
                  p,
                  s.code,
                  s.money,
                  data,
                  !s.funding,
                  () -> {
                    p.getInventory().setStorageContents(after.getContents());
                    sessions.remove(p.getUniqueId(), s);
                    p.closeInventory();
                  });
      if (!accepted) s.submitting = false;
    } catch (Exception error) {
      s.submitting = false;
      plugin.logError("预存奖励失败", error);
      plugin.message(p, "<red>奖励数据无效，未提交。</red>");
    }
  }

  @EventHandler
  public void drag(InventoryDragEvent e) {
    if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true);
  }

  @EventHandler
  public void close(InventoryCloseEvent e) {
    Session s = sessions.get(e.getPlayer().getUniqueId());
    if (s != null && s.inventory == e.getInventory() && !s.input && !s.submitting)
      sessions.remove(e.getPlayer().getUniqueId(), s);
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    sessions.remove(e.getPlayer().getUniqueId());
    plugin.delivery().forget(e.getPlayer().getUniqueId());
  }

  @EventHandler
  public void chat(AsyncPlayerChatEvent e) {
    Session s = sessions.get(e.getPlayer().getUniqueId());
    if (s == null || !s.input) return;
    e.setCancelled(true);
    String raw = e.getMessage().trim();
    plugin.runPlayer(
        e.getPlayer(),
        () -> {
          if (sessions.get(e.getPlayer().getUniqueId()) != s || !s.input) return;
          s.input = false;
          if (!allowed(e.getPlayer())) {
            sessions.remove(e.getPlayer().getUniqueId(), s);
            return;
          }
          try {
            if (!raw.equalsIgnoreCase("cancel")) s.money = Amounts.parse(raw);
          } catch (IllegalArgumentException error) {
            plugin.message(e.getPlayer(), "<red>" + error.getMessage());
          }
          decorate(s);
          e.getPlayer().openInventory(s.inventory);
        });
  }

  private void fill(Inventory inv, int from) {
    for (int i = from; i < inv.getSize(); i++)
      inv.setItem(i, item(Material.PINK_STAINED_GLASS_PANE, " ", List.of()));
  }

  private ItemStack item(Material material, String title, List<String> lore) {
    ItemStack stack = new ItemStack(material);
    ItemMeta meta = stack.getItemMeta();
    meta.itemName(MM.deserialize(title));
    meta.lore(lore.stream().map(MM::deserialize).toList());
    stack.setItemMeta(meta);
    return stack;
  }

  public void shutdown() {
    sessions.clear();
  }
}
