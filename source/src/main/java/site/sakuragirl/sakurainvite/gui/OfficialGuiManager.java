package site.sakuragirl.sakurainvite.gui;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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
import site.sakuragirl.sakurainvite.util.*;

/** Official rewards are protected template copies, never deposited player assets. */
@SuppressWarnings("deprecation")
public final class OfficialGuiManager implements Listener {
  private static final MiniMessage MM = MiniMessage.miniMessage();
  private final SakuraInvitePlugin plugin;
  private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

  private static final class ListHolder implements InventoryHolder {
    final Map<Integer, String> codes = new HashMap<>();
    final int page;
    final boolean next;

    ListHolder(int page, boolean next) {
      this.page = page;
      this.next = next;
    }

    public Inventory getInventory() {
      return null;
    }
  }

  private static final class EditHolder implements InventoryHolder {
    final UUID owner;

    EditHolder(UUID owner) {
      this.owner = owner;
    }

    public Inventory getInventory() {
      return null;
    }
  }

  private enum Input {
    NONE,
    MONEY,
    COMMAND
  }

  private static final class Session {
    String code;
    Inventory inventory;
    BigDecimal money = BigDecimal.ZERO;
    List<String> commands = new ArrayList<>();
    volatile Input input = Input.NONE;
    boolean submitting;
  }

  public OfficialGuiManager(SakuraInvitePlugin plugin) {
    this.plugin = plugin;
  }

  public void openList(Player p) {
    openList(p, 0);
  }

  private boolean allowed(Player p) {
    if (p.hasPermission("sakurainvite.admin")) return true;
    plugin.message(p, "<red>没有管理权限。</red>");
    return false;
  }

  private void openList(Player p, int requested) {
    if (!allowed(p) || !plugin.database().ready()) return;
    plugin
        .database()
        .officialCodes()
        .thenAccept(
            codes ->
                plugin.runPlayer(
                    p,
                    () -> {
                      if (!allowed(p)) return;
                      int max = Math.max(0, (codes.size() - 1) / 45),
                          page = Math.max(0, Math.min(max, requested)),
                          start = page * 45;
                      ListHolder h = new ListHolder(page, start + 45 < codes.size());
                      Inventory inv =
                          Bukkit.createInventory(
                              h,
                              54,
                              MM.deserialize(
                                  "<light_purple>官方码管理 " + (page + 1) + "/" + (max + 1)));
                      fillControls(inv, 0);
                      for (int i = start; i < Math.min(start + 45, codes.size()); i++) {
                        OfficialCodeView c = codes.get(i);
                        int slot = i - start;
                        h.codes.put(slot, c.code());
                        inv.setItem(
                            slot,
                            item(
                                Material.PAPER,
                                "<light_purple>" + c.code(),
                                List.of(
                                    c.active() ? "<green>已发布" : "<yellow>草稿 / 已停用",
                                    "<gray>领取人数：" + c.useCount(),
                                    "<gray>金币：" + c.money().toPlainString(),
                                    "<gray>点击编辑；保存不自动发布")));
                      }
                      if (page > 0) inv.setItem(45, item(Material.ARROW, "<yellow>上一页", List.of()));
                      inv.setItem(
                          49,
                          item(
                              Material.EMERALD,
                              "<green>创建官方码",
                              List.of(
                                  "<gray>/invite admin createofficial <代码>",
                                  "<gray>配置后使用 publish 命令发布")));
                      if (h.next) inv.setItem(53, item(Material.ARROW, "<yellow>下一页", List.of()));
                      p.openInventory(inv);
                    }))
        .exceptionally(
            e -> {
              plugin.logError("读取官方码失败", e);
              return null;
            });
  }

  public void openEditor(Player p, String raw) {
    if (!allowed(p) || !plugin.database().ready()) return;
    Session old = sessions.get(p.getUniqueId());
    if (old != null && old.submitting) {
      plugin.message(p, "<yellow>正在保存，请稍候。</yellow>");
      return;
    }
    plugin
        .database()
        .findAnyCode(raw)
        .thenAccept(
            code ->
                plugin.runPlayer(
                    p,
                    () -> {
                      if (!allowed(p)) return;
                      if (code == null || code.type() != CodeType.OFFICIAL) {
                        plugin.message(p, "<red>未找到官方码。</red>");
                        return;
                      }
                      p.closeInventory();
                      Session s = new Session();
                      s.code = code.code();
                      s.money = code.money();
                      s.commands = new ArrayList<>(code.commands());
                      s.inventory =
                          Bukkit.createInventory(
                              new EditHolder(p.getUniqueId()),
                              54,
                              MM.deserialize("<light_purple>官方码：" + code.code()));
                      try {
                        int i = 0;
                        for (ItemStack it : ItemCodec.decode(code.items()))
                          s.inventory.setItem(i++, it.clone());
                      } catch (Exception e) {
                        plugin.message(p, "<red>旧奖励无法读取，未打开编辑器。</red>");
                        plugin.logError("读取模板失败", e);
                        return;
                      }
                      sessions.put(p.getUniqueId(), s);
                      decorate(s);
                      p.openInventory(s.inventory);
                    }))
        .exceptionally(
            e -> {
              plugin.logError("读取官方码失败", e);
              return null;
            });
  }

  private void decorate(Session s) {
    fillControls(s.inventory, 45);
    s.inventory.setItem(45, item(Material.ARROW, "<yellow>返回（放弃修改）", List.of()));
    s.inventory.setItem(46, item(Material.RED_DYE, "<red>清空物品模板", List.of()));
    s.inventory.setItem(
        48,
        item(Material.GOLD_INGOT, "<gold>设置金币", List.of("<gray>当前：" + s.money.toPlainString())));
    s.inventory.setItem(
        49,
        item(
            Material.COMMAND_BLOCK,
            "<aqua>添加命令",
            List.of("<gray>当前 " + s.commands.size() + " 条；支持 %player_name% / %code%")));
    s.inventory.setItem(50, item(Material.BARRIER, "<red>清空命令", List.of()));
    s.inventory.setItem(
        52, item(Material.LIME_CONCRETE, "<green>保存奖励", List.of("<gray>保存后仍需 publish 发布草稿")));
    s.inventory.setItem(
        53,
        item(
            Material.PAPER,
            "<aqua>复制模板，不收取物品",
            List.of("<gray>上方左键复制光标物品或主手物品", "<gray>右键清空槽位；不能取出模板")));
  }

  @EventHandler
  public void click(InventoryClickEvent e) {
    if (!(e.getWhoClicked() instanceof Player p)) return;
    if (e.getView().getTopInventory().getHolder() instanceof ListHolder h) {
      e.setCancelled(true);
      if (!allowed(p) || e.getClickedInventory() != e.getView().getTopInventory()) return;
      String code = h.codes.get(e.getSlot());
      if (code != null) openEditor(p, code);
      else if (e.getSlot() == 45 && h.page > 0) openList(p, h.page - 1);
      else if (e.getSlot() == 53 && h.next) openList(p, h.page + 1);
      return;
    }
    if (!(e.getView().getTopInventory().getHolder() instanceof EditHolder h)) return;
    Session s = sessions.get(p.getUniqueId());
    if (!h.owner.equals(p.getUniqueId())
        || s == null
        || s.inventory != e.getView().getTopInventory()
        || s.submitting
        || !p.hasPermission("sakurainvite.admin")) {
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
      if (e.isRightClick()) s.inventory.setItem(slot, null);
      else if (e.isLeftClick()) {
        ItemStack source = e.getCursor();
        if (source == null || source.getType().isAir())
          source = p.getInventory().getItemInMainHand();
        s.inventory.setItem(slot, source.getType().isAir() ? null : source.clone());
      }
      return;
    }
    switch (slot) {
      case 45 -> {
        p.closeInventory();
        openList(p);
      }
      case 46 -> {
        for (int i = 0; i < 45; i++) s.inventory.setItem(i, null);
      }
      case 48 -> prompt(p, s, Input.MONEY, "请输入金币金额，cancel 取消。");
      case 49 -> prompt(p, s, Input.COMMAND, "请输入控制台命令（不带 /），cancel 取消。");
      case 50 -> {
        s.commands.clear();
        decorate(s);
      }
      case 52 -> save(p, s);
      default -> {}
    }
  }

  @EventHandler
  public void drag(InventoryDragEvent e) {
    if (e.getView().getTopInventory().getHolder() instanceof EditHolder
        || e.getView().getTopInventory().getHolder() instanceof ListHolder) e.setCancelled(true);
  }

  @EventHandler
  public void close(InventoryCloseEvent e) {
    Session s = sessions.get(e.getPlayer().getUniqueId());
    if (s != null && s.inventory == e.getInventory() && s.input == Input.NONE && !s.submitting)
      sessions.remove(e.getPlayer().getUniqueId(), s);
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    sessions.remove(e.getPlayer().getUniqueId());
  }

  private void prompt(Player p, Session s, Input input, String text) {
    s.input = input;
    p.closeInventory();
    plugin.message(p, "<yellow>" + text);
  }

  @EventHandler
  public void chat(AsyncPlayerChatEvent e) {
    Session s = sessions.get(e.getPlayer().getUniqueId());
    if (s == null || s.input == Input.NONE) return;
    e.setCancelled(true);
    String raw = e.getMessage().trim();
    plugin.runPlayer(
        e.getPlayer(),
        () -> {
          if (sessions.get(e.getPlayer().getUniqueId()) != s
              || s.input == Input.NONE
              || !allowed(e.getPlayer())) return;
          Input mode = s.input;
          s.input = Input.NONE;
          try {
            if (!raw.equalsIgnoreCase("cancel")) {
              if (mode == Input.MONEY) s.money = Amounts.parse(raw);
              else {
                if (raw.isBlank()
                    || raw.length() > 512
                    || raw.contains("\n")
                    || raw.contains("\r")
                    || s.commands.size() >= 20)
                  throw new IllegalArgumentException("命令最多 20 条，每条 512 字符");
                s.commands.add(raw.startsWith("/") ? raw.substring(1) : raw);
              }
            }
          } catch (IllegalArgumentException error) {
            plugin.message(e.getPlayer(), "<red>" + error.getMessage());
          }
          decorate(s);
          e.getPlayer().openInventory(s.inventory);
        });
  }

  private void save(Player p, Session s) {
    if (s.submitting || !allowed(p)) return;
    try {
      List<ItemStack> items = new ArrayList<>();
      for (int i = 0; i < 45; i++) {
        ItemStack it = s.inventory.getItem(i);
        if (it != null && !it.getType().isAir()) items.add(it.clone());
      }
      if (items.isEmpty() && s.money.signum() == 0 && s.commands.isEmpty()) {
        plugin.message(p, "<red>至少设置一种奖励。</red>");
        return;
      }
      byte[] data = items.isEmpty() ? null : ItemCodec.encode(items);
      s.submitting = true;
      plugin
          .database()
          .updateOfficial(s.code, Amounts.validate(s.money), data, true, List.copyOf(s.commands))
          .whenComplete(
              (ok, error) ->
                  plugin.runGlobal(
                      () -> {
                        s.submitting = false;
                        if (sessions.get(p.getUniqueId()) != s) return;
                        if (error != null || !ok) {
                          plugin.message(p, "<red>保存失败，请稍后重试。</red>");
                          if (error != null) plugin.logError("保存失败", error);
                          return;
                        }
                        sessions.remove(p.getUniqueId(), s);
                        p.closeInventory();
                        plugin.message(
                            p,
                            "<green>奖励已保存。草稿需 /invite admin publish " + s.code + " 后生效。</green>");
                        openList(p);
                      }));
    } catch (Exception error) {
      plugin.message(p, "<red>奖励数据无效：" + error.getMessage());
    }
  }

  private void fillControls(Inventory inv, int from) {
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
