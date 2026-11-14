package site.sakuragirl.sakurainvite.listener;

import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.persistence.PersistentDataType;
import site.sakuragirl.sakurainvite.SakuraInvitePlugin;
import site.sakuragirl.sakurainvite.model.Models.TaskType;
import site.sakuragirl.sakurainvite.service.InviteService;

/** Paper listeners. Mining placed blocks is excluded; PLANT counts mature crop harvests. */
public final class ProgressListener implements Listener {
  private final SakuraInvitePlugin plugin;
  private final InviteService service;
  private final NamespacedKey placed;

  public ProgressListener(SakuraInvitePlugin plugin, InviteService service) {
    this.plugin = plugin;
    this.service = service;
    placed = new NamespacedKey(plugin, "placed_blocks");
  }

  @EventHandler
  public void join(PlayerJoinEvent e) {
    service.retryClaim(e.getPlayer());
    if (plugin.database().ready())
      plugin
          .database()
          .touchPlayer(
              e.getPlayer().getUniqueId(), e.getPlayer().getName(), service.ip(e.getPlayer()));
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    service.flush();
    service.forget(e.getPlayer().getUniqueId());
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void level(PlayerLevelChangeEvent e) {
    service.progress(e.getPlayer(), TaskType.LEVEL, "", e.getNewLevel());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void kill(EntityDeathEvent e) {
    Player p = e.getEntity().getKiller();
    if (p != null) service.progress(p, TaskType.KILL, e.getEntityType().name(), 1);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void mine(BlockBreakEvent e) {
    Block block = e.getBlock();
    boolean playerPlaced = tracked(block);
    mark(block, false);
    if (!playerPlaced)
      service.progress(e.getPlayer(), TaskType.BLOCK_BREAK, block.getType().name(), 1);
    if (block.getBlockData() instanceof Ageable crop && crop.getAge() == crop.getMaximumAge())
      service.progress(e.getPlayer(), TaskType.PLANT, block.getType().name(), 1);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void place(BlockPlaceEvent e) {
    mark(e.getBlockPlaced(), true);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void fish(PlayerFishEvent e) {
    if (e.getState() == PlayerFishEvent.State.CAUGHT_FISH)
      service.progress(e.getPlayer(), TaskType.FISH, "", 1);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void piston(BlockPistonExtendEvent e) {
    move(e.getBlocks(), e.getDirection());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void retract(BlockPistonRetractEvent e) {
    move(e.getBlocks(), e.getDirection());
  }

  private void move(List<Block> blocks, org.bukkit.block.BlockFace direction) {
    List<Block> targets = new ArrayList<>();
    for (Block b : blocks) if (tracked(b)) targets.add(b.getRelative(direction));
    for (Block b : blocks) mark(b, false);
    for (Block b : targets) mark(b, true);
  }

  private long position(Block b) {
    return ((long) (b.getY() - b.getWorld().getMinHeight()) << 8)
        | ((b.getZ() & 15) << 4)
        | (b.getX() & 15);
  }

  private boolean tracked(Block b) {
    long target = position(b);
    long[] values =
        b.getChunk().getPersistentDataContainer().get(placed, PersistentDataType.LONG_ARRAY);
    if (values == null) return false;
    for (long v : values) if (v == target) return true;
    return false;
  }

  private void mark(Block b, boolean value) {
    var pdc = b.getChunk().getPersistentDataContainer();
    long[] old = pdc.get(placed, PersistentDataType.LONG_ARRAY);
    Set<Long> values = new HashSet<>();
    if (old != null) for (long v : old) values.add(v);
    boolean changed = value ? values.add(position(b)) : values.remove(position(b));
    if (!changed) return;
    if (values.isEmpty()) pdc.remove(placed);
    else
      pdc.set(
          placed,
          PersistentDataType.LONG_ARRAY,
          values.stream().mapToLong(Long::longValue).toArray());
  }
}
