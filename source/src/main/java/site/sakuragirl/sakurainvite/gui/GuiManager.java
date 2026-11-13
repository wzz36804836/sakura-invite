package site.sakuragirl.sakurainvite.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import site.sakuragirl.sakurainvite.SakuraInvitePlugin;
import site.sakuragirl.sakurainvite.model.Models.Dashboard;
import site.sakuragirl.sakurainvite.model.Models.TaskProgress;
import site.sakuragirl.sakurainvite.service.InviteService;
import site.sakuragirl.sakurainvite.util.ItemCodec;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings("deprecation")
public final class GuiManager implements Listener {
    private static final MiniMessage MM=MiniMessage.miniMessage();
    private final SakuraInvitePlugin plugin;private final InviteService service;
    private final Map<UUID,CreateSession> sessions=new ConcurrentHashMap<>();
    public GuiManager(SakuraInvitePlugin plugin,InviteService service){this.plugin=plugin;this.service=service;}
    private static final class CenterHolder implements InventoryHolder {public Inventory getInventory(){return null;}}
    private static final class CreateHolder implements InventoryHolder {final UUID owner;CreateHolder(UUID owner){this.owner=owner;}public Inventory getInventory(){return null;}}
    private static final class CreateSession {Inventory inventory;BigDecimal money=BigDecimal.ZERO;boolean editing;boolean submitting;}

    public void openCenter(Player p){
        if(!plugin.database().ready()){plugin.message(p,plugin.getConfig().getString("messages.database-not-ready"));return;}
        plugin.database().dashboard(p.getUniqueId(),plugin.tasks()).thenAccept(data->plugin.runPlayer(p,()->renderCenter(p,data))).exceptionally(ex->{plugin.logError("读取邀请中心失败",ex);return null;});
    }
    private void renderCenter(Player p,Dashboard d){
        Inventory inv=Bukkit.createInventory(new CenterHolder(),45,MM.deserialize(plugin.getConfig().getString("gui.title","樱花邀请中心")));
        fill(inv);
        inv.setItem(10,item(Material.NAME_TAG,"<light_purple>我的邀请码",List.of(d.ownCode()==null?"<gray>尚未创建":"<yellow>"+d.ownCode(),"<gray>点击创建/查看")));
        List<String> taskLore=new ArrayList<>();if(d.tasks().isEmpty())taskLore.add("<gray>使用个人邀请码后生成");for(TaskProgress t:d.tasks())taskLore.add((t.complete()?"<green>✔ ":"<yellow>• ")+t.name()+" <white>"+Math.min(t.progress(),t.target())+"/"+t.target());
        inv.setItem(12,item(Material.WRITABLE_BOOK,"<aqua>邀请任务",taskLore));
        inv.setItem(14,item(Material.BOOK,"<gold>邀请记录",List.of("<gray>已邀请：<white>"+d.invitedCount(),"<gray>有效邀请：<green>"+d.validCount(),"<gray>点击查看最近记录")));
        inv.setItem(16,item(Material.NETHER_STAR,"<yellow>邀请排行榜",List.of(d.rank()>0?"<gray>你的排名：<white>#"+d.rank():"<gray>完成有效邀请后上榜","<gray>点击查看前十名")));
        inv.setItem(31,item(Material.PLAYER_HEAD,"<light_purple>邀请关系",List.of(d.inviterName()==null?"<gray>尚未绑定个人邀请码":"<gray>邀请人：<white>"+d.inviterName())));
        p.openInventory(inv);
    }
    public void openCreate(Player p){
        CreateSession existing=sessions.get(p.getUniqueId());if(existing!=null){p.openInventory(existing.inventory);return;}
        CreateSession s=new CreateSession();Inventory inv=Bukkit.createInventory(new CreateHolder(p.getUniqueId()),54,MM.deserialize(plugin.getConfig().getString("gui.create-title","设置新人奖励")));s.inventory=inv;sessions.put(p.getUniqueId(),s);
        decorateCreate(s);p.openInventory(inv);
    }
    private void decorateCreate(CreateSession s){
        s.inventory.setItem(45,item(Material.BARRIER,"<red>取消并返还物品",List.of()));
        s.inventory.setItem(49,item(Material.GOLD_INGOT,"<gold>设置金币奖励",List.of("<gray>当前：<yellow>"+s.money.toPlainString(),"<gray>点击后在聊天栏输入金额")));
        s.inventory.setItem(52,item(Material.LIME_DYE,"<green>确认创建邀请码",List.of("<gray>上方 45 格可直接放入奖励物品","<gray>支持原版与 CraftEngine 物品")));
        s.inventory.setItem(53,item(Material.PAPER,"<aqua>说明",List.of("<gray>新人完成全部任务后获得这些奖励","<gray>每位玩家只能创建一个个人邀请码")));
    }
    private void fill(Inventory inv){ItemStack pane=item(Material.PINK_STAINED_GLASS_PANE," ",List.of());for(int i=0;i<inv.getSize();i++)inv.setItem(i,pane);}
    private ItemStack item(Material material,String name,List<String> lore){ItemStack stack=new ItemStack(material);ItemMeta meta=stack.getItemMeta();meta.itemName(MM.deserialize(name));if(!lore.isEmpty())meta.lore(lore.stream().map(MM::deserialize).toList());stack.setItemMeta(meta);return stack;}

    @EventHandler(ignoreCancelled=true) public void click(InventoryClickEvent e){
        if(e.getInventory().getHolder() instanceof CenterHolder){e.setCancelled(true);if(!(e.getWhoClicked() instanceof Player p)||e.getClickedInventory()!=e.getView().getTopInventory())return;switch(e.getSlot()){case 10->{p.closeInventory();openCreate(p);}case 14->{p.closeInventory();plugin.showRecords(p);}case 16->{p.closeInventory();plugin.showTop(p);}default->{}}return;}
        if(!(e.getInventory().getHolder() instanceof CreateHolder h))return;if(!(e.getWhoClicked() instanceof Player p)||!h.owner.equals(p.getUniqueId())){e.setCancelled(true);return;}
        CreateSession s=sessions.get(h.owner);if(s==null)return;
        if(e.getClickedInventory()==e.getView().getTopInventory()&&e.getSlot()>=45){e.setCancelled(true);if(e.getSlot()==45)cancel(p,s);else if(e.getSlot()==49){s.editing=true;p.closeInventory();plugin.message(p,"<yellow>请在聊天栏输入金币金额，输入 cancel 取消设置。</yellow>");}else if(e.getSlot()==52)submit(p,s);}
        if(e.getAction()==InventoryAction.COLLECT_TO_CURSOR)e.setCancelled(true);
    }
    @EventHandler public void drag(InventoryDragEvent e){if(e.getInventory().getHolder() instanceof CreateHolder&&e.getRawSlots().stream().anyMatch(i->i>=45&&i<54))e.setCancelled(true);}
    @EventHandler public void close(InventoryCloseEvent e){if(!(e.getInventory().getHolder() instanceof CreateHolder h))return;CreateSession s=sessions.get(h.owner);if(s!=null&&!s.editing&&!s.submitting)cancel((Player)e.getPlayer(),s);}
    @EventHandler public void chat(AsyncPlayerChatEvent e){CreateSession s=sessions.get(e.getPlayer().getUniqueId());if(s==null||!s.editing)return;e.setCancelled(true);String text=e.getMessage().trim();plugin.runPlayer(e.getPlayer(),()->{s.editing=false;if(!text.equalsIgnoreCase("cancel")){try{BigDecimal n=new BigDecimal(text);if(n.signum()<0||n.scale()>2)throw new NumberFormatException();s.money=n;plugin.message(e.getPlayer(),"<green>金币奖励已设置为 "+n.toPlainString()+"。</green>");}catch(NumberFormatException ex){plugin.message(e.getPlayer(),"<red>金额无效，只能输入不小于 0、最多两位小数的数字。</red>");}}decorateCreate(s);e.getPlayer().openInventory(s.inventory);});}
    @EventHandler public void quit(PlayerQuitEvent e){CreateSession s=sessions.remove(e.getPlayer().getUniqueId());if(s!=null)returnItems(e.getPlayer(),s);}
    private void submit(Player p,CreateSession s){
        List<ItemStack> rewards=new ArrayList<>();for(int i=0;i<45;i++){ItemStack it=s.inventory.getItem(i);if(it!=null&&!it.getType().isAir())rewards.add(it.clone());}
        if(rewards.isEmpty()&&s.money.signum()==0){plugin.message(p,"<red>请至少设置一种物品或金币奖励。</red>");return;}
        try{s.submitting=true;byte[] encoded=ItemCodec.encode(rewards);service.create(p,s.money,encoded).thenAccept(msg->plugin.runPlayer(p,()->{boolean ok=msg.contains("创建成功");plugin.message(p,msg);if(ok){sessions.remove(p.getUniqueId());p.closeInventory();}else{s.submitting=false;}})).exceptionally(ex->{plugin.logError("创建邀请码失败",ex);plugin.runPlayer(p,()->{s.submitting=false;plugin.message(p,"<red>创建失败，请稍后重试。</red>");});return null;});}catch(Exception ex){s.submitting=false;plugin.message(p,"<red>奖励物品无法保存，请联系管理员。</red>");plugin.logError("序列化奖励失败",ex);}
    }
    private void cancel(Player p,CreateSession s){if(sessions.remove(p.getUniqueId(),s)){returnItems(p,s);p.closeInventory();plugin.message(p,"<gray>已取消创建，奖励物品已返还。</gray>");}}
    private void returnItems(Player p,CreateSession s){for(int i=0;i<45;i++){ItemStack it=s.inventory.getItem(i);if(it==null||it.getType().isAir())continue;p.getInventory().addItem(it).values().forEach(left->p.getWorld().dropItemNaturally(p.getLocation(),left));s.inventory.setItem(i,null);}}
    public void shutdown(){for(Map.Entry<UUID,CreateSession> e:sessions.entrySet()){Player p=Bukkit.getPlayer(e.getKey());if(p!=null)returnItems(p,e.getValue());}sessions.clear();}
}
