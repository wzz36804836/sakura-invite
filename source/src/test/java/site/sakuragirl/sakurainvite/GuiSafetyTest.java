package site.sakuragirl.sakurainvite;

import static org.mockito.Mockito.*;

import java.util.*;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.*;
import org.junit.jupiter.api.Test;
import site.sakuragirl.sakurainvite.gui.GuiManager;

class GuiSafetyTest {
  @SuppressWarnings({"unchecked", "rawtypes"})
  private InventoryHolder holder(String kind, UUID owner) throws Exception {
    Class c = Class.forName(GuiManager.class.getName() + "$Kind");
    var ctor =
        Class.forName(GuiManager.class.getName() + "$Holder").getDeclaredConstructor(c, UUID.class);
    ctor.setAccessible(true);
    return (InventoryHolder) ctor.newInstance(Enum.valueOf(c, kind), owner);
  }

  private void session(GuiManager gui, UUID owner, Inventory top, boolean submitting)
      throws Exception {
    Class<?> type = Class.forName(GuiManager.class.getName() + "$Session");
    var ctor = type.getDeclaredConstructor();
    ctor.setAccessible(true);
    Object s = ctor.newInstance();
    var field = type.getDeclaredField("inventory");
    field.setAccessible(true);
    field.set(s, top);
    field = type.getDeclaredField("submitting");
    field.setAccessible(true);
    field.set(s, submitting);
    field = GuiManager.class.getDeclaredField("sessions");
    field.setAccessible(true);
    @SuppressWarnings("unchecked")
    Map<UUID, Object> sessions = (Map<UUID, Object>) field.get(gui);
    sessions.put(owner, s);
  }

  private record Fixture(
      SakuraInvitePlugin plugin,
      GuiManager gui,
      Player player,
      Inventory top,
      Inventory bottom,
      InventoryClickEvent event) {}

  private Fixture fixture(String kind, boolean submitting) throws Exception {
    SakuraInvitePlugin plugin = mock(SakuraInvitePlugin.class);
    GuiManager gui = new GuiManager(plugin, null);
    Player player = mock(Player.class);
    UUID uuid = UUID.randomUUID();
    when(player.getUniqueId()).thenReturn(uuid);
    when(player.hasPermission("sakurainvite.create")).thenReturn(true);
    Inventory top = mock(Inventory.class), bottom = mock(Inventory.class);
    when(top.getHolder()).thenReturn(holder(kind, uuid));
    InventoryView view = mock(InventoryView.class);
    when(view.getTopInventory()).thenReturn(top);
    InventoryClickEvent event = mock(InventoryClickEvent.class);
    when(event.getView()).thenReturn(view);
    when(event.getWhoClicked()).thenReturn(player);
    when(event.getClickedInventory()).thenReturn(top);
    if (!kind.equals("CENTER")) session(gui, uuid, top, submitting);
    return new Fixture(plugin, gui, player, top, bottom, event);
  }

  @Test
  void menuCannotBypassRevokedCreatePermission() throws Exception {
    var f = fixture("CENTER", false);
    when(f.event().getSlot()).thenReturn(10);
    when(f.player().hasPermission("sakurainvite.create")).thenReturn(false);
    f.gui().click(f.event());
    verify(f.event()).setCancelled(true);
    verify(f.plugin(), never()).funding();
    verify(f.player(), never()).getInventory();
  }

  @Test
  void shiftFromBackpackCannotReachControlSlots() throws Exception {
    var f = fixture("CREATE", false);
    when(f.event().getClickedInventory()).thenReturn(f.bottom());
    when(f.event().isShiftClick()).thenReturn(true);
    f.gui().click(f.event());
    verify(f.event()).setCancelled(true);
    verify(f.plugin(), never()).funding();
  }

  @Test
  void submissionInFlightRejectsRepeatedConfirmAndItemMoves() throws Exception {
    var f = fixture("CREATE", true);
    when(f.event().getSlot()).thenReturn(52);
    f.gui().click(f.event());
    verify(f.event()).setCancelled(true);
    verify(f.plugin(), never()).funding();
    verify(f.player(), never()).getInventory();
  }

  @Test
  void rewardSelectionOnlyCopiesTemplateAndNeverConsumesCursor() throws Exception {
    var f = fixture("CREATE", false);
    ItemStack cursor = mock(ItemStack.class), copy = mock(ItemStack.class);
    Material material = mock(Material.class);
    when(material.isAir()).thenReturn(false);
    when(cursor.getType()).thenReturn(material);
    when(cursor.clone()).thenReturn(copy);
    when(f.event().getSlot()).thenReturn(0);
    when(f.event().isLeftClick()).thenReturn(true);
    when(f.event().getCursor()).thenReturn(cursor);
    f.gui().click(f.event());
    verify(f.event()).setCancelled(true);
    verify(f.top()).setItem(0, copy);
    verify(cursor, never()).setAmount(anyInt());
    verify(f.player(), never()).getInventory();
  }
}
