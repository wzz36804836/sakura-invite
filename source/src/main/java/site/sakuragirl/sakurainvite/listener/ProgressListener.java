package site.sakuragirl.sakurainvite.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.*;
import site.sakuragirl.sakurainvite.SakuraInvitePlugin;
import site.sakuragirl.sakurainvite.model.Models.TaskType;
import site.sakuragirl.sakurainvite.service.InviteService;

public final class ProgressListener implements Listener {
    private final SakuraInvitePlugin plugin;private final InviteService service;
    public ProgressListener(SakuraInvitePlugin plugin,InviteService service){this.plugin=plugin;this.service=service;}
    @EventHandler public void join(PlayerJoinEvent e){scheduleOnline(e.getPlayer());service.progress(e.getPlayer(),TaskType.LEVEL,"",e.getPlayer().getLevel());service.retryClaim(e.getPlayer());plugin.database().touchPlayer(e.getPlayer().getUniqueId(),e.getPlayer().getName(),service.ip(e.getPlayer()));}
    private void scheduleOnline(Player player){player.getScheduler().runAtFixedRate(plugin,task->service.progress(player,TaskType.ONLINE_MINUTES,"",1),null,1200L,1200L);}
    @EventHandler public void level(PlayerLevelChangeEvent e){service.progress(e.getPlayer(),TaskType.LEVEL,"",e.getNewLevel());}
    @EventHandler(ignoreCancelled=true) public void kill(EntityDeathEvent e){Player p=e.getEntity().getKiller();if(p!=null)service.progress(p,TaskType.KILL,e.getEntityType().name(),1);}
    @EventHandler(ignoreCancelled=true) public void mine(BlockBreakEvent e){service.progress(e.getPlayer(),TaskType.BLOCK_BREAK,e.getBlock().getType().name(),1);}
    @EventHandler(ignoreCancelled=true) public void plant(BlockPlaceEvent e){service.progress(e.getPlayer(),TaskType.PLANT,e.getBlockPlaced().getType().name(),1);}
    @EventHandler(ignoreCancelled=true) public void fish(PlayerFishEvent e){if(e.getState()==PlayerFishEvent.State.CAUGHT_FISH)service.progress(e.getPlayer(),TaskType.FISH,"",1);}
}
