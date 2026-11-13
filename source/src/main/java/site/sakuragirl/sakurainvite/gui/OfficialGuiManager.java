package site.sakuragirl.sakurainvite.gui;

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
import site.sakuragirl.sakurainvite.model.Models.InviteCode;
import site.sakuragirl.sakurainvite.model.Models.OfficialCodeView;
import site.sakuragirl.sakurainvite.util.ItemCodec;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings("deprecation")
public final class OfficialGuiManager implements Listener {
    private static final MiniMessage MM=MiniMessage.miniMessage();
    private final SakuraInvitePlugin plugin;private final Map<UUID,EditSession> sessions=new ConcurrentHashMap<>();
    public OfficialGuiManager(SakuraInvitePlugin plugin){this.plugin=plugin;}
    private static final class ListHolder implements InventoryHolder{final Map<Integer,String> codes=new HashMap<>();final int page;final boolean hasNext;ListHolder(int page,boolean hasNext){this.page=page;this.hasNext=hasNext;}public Inventory getInventory(){return null;}}
    private static final class EditHolder implements InventoryHolder{final UUID owner;EditHolder(UUID owner){this.owner=owner;}public Inventory getInventory(){return null;}}
    private enum InputMode{NONE,MONEY,COMMAND}
    private static final class EditSession{String code;BigDecimal money;byte[] existingItems;List<String> commands;Inventory inventory;InputMode input=InputMode.NONE;boolean submitting;boolean clearItems;}

    public void openList(Player player){openList(player,0);}
    private void openList(Player player,int page){
        if(!player.hasPermission("sakurainvite.admin")){plugin.message(player,"<red>没有权限。</red>");return;}
        plugin.database().officialCodes().thenAccept(codes->plugin.runPlayer(player,()->renderList(player,codes,page))).exceptionally(ex->{plugin.logError("读取官方邀请码失败",ex);return null;});
    }
    private void renderList(Player player,List<OfficialCodeView> codes,int requestedPage){
        int maxPage=Math.max(0,(codes.size()-1)/45);int page=Math.max(0,Math.min(requestedPage,maxPage));int start=page*45;ListHolder holder=new ListHolder(page,start+45<codes.size());Inventory inv=Bukkit.createInventory(holder,54,MM.deserialize("<gradient:#ff8fbd:#ffd5e5>官方邀请码管理</gradient> <gray>"+(page+1)+"/"+(maxPage+1)+"</gray>"));fill(inv);
        for(int index=start;index<Math.min(start+45,codes.size());index++){int slot=index-start;OfficialCodeView code=codes.get(index);int itemCount=countItems(code.items());List<String> lore=new ArrayList<>(List.of("<gray>领取人数：<green>"+code.useCount(),"<gray>金币奖励：<yellow>"+code.money().toPlainString(),"<gray>物品奖励：<aqua>"+itemCount+" 件","<gray>执行命令：<white>"+code.commands().size()+" 条","","<yellow>左键编辑奖励"));inv.setItem(slot,item(Material.PAPER,"<light_purple>"+code.code(),lore));holder.codes.put(slot,code.code());}
        if(page>0)inv.setItem(45,item(Material.ARROW,"<yellow>上一页",List.of()));
        inv.setItem(49,item(Material.EMERALD,"<green>创建官方邀请码",List.of("<gray>使用命令：","<white>/invite admin createofficial <代码>")));
        inv.setItem(50,item(Material.BOOK,"<aqua>统计说明",List.of("<gray>每个官方码可被不同玩家使用","<gray>同一玩家对同一个官方码只能领取一次")));
        if(holder.hasNext)inv.setItem(53,item(Material.ARROW,"<yellow>下一页",List.of()));
        player.openInventory(inv);
    }
    public void openEditor(Player player,String code){
        plugin.database().findCode(code).thenAccept(found->{if(found==null||found.type()!=site.sakuragirl.sakurainvite.model.Models.CodeType.OFFICIAL){plugin.runPlayer(player,()->plugin.message(player,"<red>未找到这个官方邀请码。</red>"));return;}plugin.runPlayer(player,()->renderEditor(player,found));}).exceptionally(ex->{plugin.logError("读取官方邀请码奖励失败",ex);return null;});
    }
    private void renderEditor(Player player,InviteCode code){
        EditSession old=sessions.remove(player.getUniqueId());if(old!=null){returnDeposits(player,old);player.closeInventory();}
        EditSession s=new EditSession();s.code=code.code();s.money=code.money();s.existingItems=code.items();s.commands=new ArrayList<>(code.commands());s.inventory=Bukkit.createInventory(new EditHolder(player.getUniqueId()),54,MM.deserialize("<light_purple>编辑官方码：</light_purple><white>"+code.code()+"</white>"));sessions.put(player.getUniqueId(),s);decorate(s);player.openInventory(s.inventory);
    }
    private void decorate(EditSession s){
        s.inventory.setItem(45,item(Material.ARROW,"<yellow>返回列表",List.of("<gray>不保存并返还本次放入的物品")));
        s.inventory.setItem(46,item(s.clearItems?Material.LIME_DYE:Material.RED_DYE,s.clearItems?"<green>将清空旧物品奖励":"<red>清空旧物品奖励",List.of("<gray>当前旧奖励：<white>"+countItems(s.existingItems)+" 件","<gray>点击切换；上方放入新物品会替换旧奖励")));
        s.inventory.setItem(48,item(Material.GOLD_INGOT,"<gold>设置金币奖励",List.of("<gray>当前：<yellow>"+s.money.toPlainString(),"<gray>官方奖励由服务器发放，不扣管理员金币")));
        List<String> commandLore=new ArrayList<>();commandLore.add("<gray>点击后在聊天栏输入一条控制台命令");commandLore.add("<gray>支持 %player_name% 和 %code%");for(int i=0;i<Math.min(5,s.commands.size());i++)commandLore.add("<white>"+(i+1)+". "+s.commands.get(i));if(s.commands.size()>5)commandLore.add("<gray>……共 "+s.commands.size()+" 条");
        s.inventory.setItem(49,item(Material.COMMAND_BLOCK,"<aqua>添加执行命令",commandLore));
        s.inventory.setItem(50,item(Material.BARRIER,"<red>清空全部命令",List.of("<gray>当前共有 "+s.commands.size()+" 条命令")));
        s.inventory.setItem(52,item(Material.LIME_CONCRETE,"<green>保存官方邀请码奖励",List.of("<gray>保存金币、物品和命令设置")));
        s.inventory.setItem(53,item(Material.PAPER,"<light_purple>"+s.code,List.of("<gray>上方 45 格可放入新的奖励模板","<gray>保存后放入的模板物品会返还")));
    }
    @EventHandler public void click(InventoryClickEvent e){
        if(e.getInventory().getHolder() instanceof ListHolder holder){e.setCancelled(true);if(!(e.getWhoClicked() instanceof Player p)||e.getClickedInventory()!=e.getView().getTopInventory())return;String code=holder.codes.get(e.getSlot());if(code!=null){p.closeInventory();openEditor(p,code);}else if(e.getSlot()==45&&holder.page>0)openList(p,holder.page-1);else if(e.getSlot()==53&&holder.hasNext)openList(p,holder.page+1);return;}
        if(!(e.getInventory().getHolder() instanceof EditHolder holder)||!(e.getWhoClicked() instanceof Player p)){return;}if(!holder.owner.equals(p.getUniqueId())){e.setCancelled(true);return;}EditSession s=sessions.get(holder.owner);if(s==null)return;
        if(e.getClickedInventory()==e.getView().getTopInventory()&&e.getSlot()>=45){e.setCancelled(true);switch(e.getSlot()){case 45->cancel(p,s,true);case 46->{s.clearItems=!s.clearItems;decorate(s);}case 48->prompt(p,s,InputMode.MONEY,"<yellow>请在聊天栏输入官方码金币奖励，输入 cancel 取消。</yellow>");case 49->prompt(p,s,InputMode.COMMAND,"<yellow>请输入一条不带 / 的控制台命令，输入 cancel 取消。</yellow>");case 50->{s.commands.clear();decorate(s);plugin.message(p,"<gray>已清空命令，点击保存后生效。</gray>");}case 52->save(p,s);default->{}}}
        if(e.getAction()==InventoryAction.COLLECT_TO_CURSOR)e.setCancelled(true);
    }
    @EventHandler public void drag(InventoryDragEvent e){if(e.getInventory().getHolder() instanceof EditHolder&&e.getRawSlots().stream().anyMatch(i->i>=45&&i<54))e.setCancelled(true);}
    @EventHandler public void close(InventoryCloseEvent e){if(!(e.getInventory().getHolder() instanceof EditHolder holder))return;EditSession s=sessions.get(holder.owner);if(s!=null&&s.input==InputMode.NONE&&!s.submitting)cancel((Player)e.getPlayer(),s,false);}
    @EventHandler public void chat(AsyncPlayerChatEvent e){EditSession s=sessions.get(e.getPlayer().getUniqueId());if(s==null||s.input==InputMode.NONE)return;e.setCancelled(true);String value=e.getMessage().trim();InputMode mode=s.input;plugin.runPlayer(e.getPlayer(),()->{s.input=InputMode.NONE;if(!value.equalsIgnoreCase("cancel")){if(mode==InputMode.MONEY){try{BigDecimal money=new BigDecimal(value);if(money.signum()<0||money.scale()>2)throw new NumberFormatException();s.money=money;plugin.message(e.getPlayer(),"<green>官方金币奖励已设置。</green>");}catch(NumberFormatException ex){plugin.message(e.getPlayer(),"<red>金额无效，只允许非负数且最多两位小数。</red>");}}else if(!value.isBlank()){s.commands.add(value.startsWith("/")?value.substring(1):value);plugin.message(e.getPlayer(),"<green>命令已添加。</green>");}}decorate(s);e.getPlayer().openInventory(s.inventory);});}
    @EventHandler public void quit(PlayerQuitEvent e){EditSession s=sessions.remove(e.getPlayer().getUniqueId());if(s!=null)returnDeposits(e.getPlayer(),s);}
    private void prompt(Player p,EditSession s,InputMode mode,String message){s.input=mode;p.closeInventory();plugin.message(p,message);}
    private void save(Player p,EditSession s){
        List<ItemStack> deposited=deposits(s);boolean replace=!deposited.isEmpty()||s.clearItems;byte[] data=null;try{if(replace)data=ItemCodec.encode(deposited);}catch(Exception ex){plugin.logError("保存官方邀请码物品失败",ex);plugin.message(p,"<red>物品无法序列化。</red>");return;}
        s.submitting=true;plugin.database().updateOfficial(s.code,s.money,data,replace,List.copyOf(s.commands)).thenAccept(ok->plugin.runPlayer(p,()->{s.submitting=false;if(!ok){plugin.message(p,"<red>保存失败，邀请码可能已停用。</red>");return;}sessions.remove(p.getUniqueId());returnDeposits(p,s);p.closeInventory();plugin.message(p,"<green>官方邀请码 <yellow>"+s.code+"</yellow> 的奖励已保存。</green>");openList(p);})).exceptionally(ex->{plugin.logError("更新官方邀请码失败",ex);plugin.runPlayer(p,()->{s.submitting=false;plugin.message(p,"<red>保存失败，请稍后重试。</red>");});return null;});
    }
    private void cancel(Player p,EditSession s,boolean openList){if(sessions.remove(p.getUniqueId(),s)){returnDeposits(p,s);p.closeInventory();if(openList)openList(p);}}
    private List<ItemStack> deposits(EditSession s){List<ItemStack> out=new ArrayList<>();for(int i=0;i<45;i++){ItemStack stack=s.inventory.getItem(i);if(stack!=null&&!stack.getType().isAir())out.add(stack.clone());}return out;}
    private void returnDeposits(Player p,EditSession s){for(int i=0;i<45;i++){ItemStack stack=s.inventory.getItem(i);if(stack==null||stack.getType().isAir())continue;p.getInventory().addItem(stack).values().forEach(left->p.getWorld().dropItemNaturally(p.getLocation(),left));s.inventory.setItem(i,null);}}
    private int countItems(byte[] data){try{return ItemCodec.decode(data).stream().mapToInt(ItemStack::getAmount).sum();}catch(Exception ignored){return 0;}}
    private void fill(Inventory inv){ItemStack pane=item(Material.PINK_STAINED_GLASS_PANE," ",List.of());for(int i=0;i<inv.getSize();i++)inv.setItem(i,pane);}
    private ItemStack item(Material type,String name,List<String> lore){ItemStack stack=new ItemStack(type);ItemMeta meta=stack.getItemMeta();meta.itemName(MM.deserialize(name));if(!lore.isEmpty())meta.lore(lore.stream().map(MM::deserialize).toList());stack.setItemMeta(meta);return stack;}
    public void shutdown(){for(Map.Entry<UUID,EditSession> entry:sessions.entrySet()){Player p=Bukkit.getPlayer(entry.getKey());if(p!=null)returnDeposits(p,entry.getValue());}sessions.clear();}
}
