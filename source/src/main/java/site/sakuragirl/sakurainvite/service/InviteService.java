package site.sakuragirl.sakurainvite.service;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import site.sakuragirl.sakurainvite.SakuraInvitePlugin;
import site.sakuragirl.sakurainvite.db.Database;
import site.sakuragirl.sakurainvite.hook.EconomyHook;
import site.sakuragirl.sakurainvite.model.Models.*;
import site.sakuragirl.sakurainvite.task.TaskRegistry;
import site.sakuragirl.sakurainvite.util.ItemCodec;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class InviteService {
    private final SakuraInvitePlugin plugin; private final Database db; private final TaskRegistry tasks; private final EconomyHook economy;
    public InviteService(SakuraInvitePlugin plugin,Database db,TaskRegistry tasks,EconomyHook economy){this.plugin=plugin;this.db=db;this.tasks=tasks;this.economy=economy;}
    public String ip(Player p){InetSocketAddress a=p.getAddress();return a==null||a.getAddress()==null?null:a.getAddress().getHostAddress();}

    public CompletableFuture<String> create(Player p,BigDecimal money,byte[] items){
        if(money.signum()>0&&!economy.available())return CompletableFuture.completedFuture("<red>经济服务未连接，无法设置金币奖励。</red>");
        if(money.signum()>0&&!economy.has(p,money))return CompletableFuture.completedFuture("<red>余额不足，需要 "+money.toPlainString()+" 金币。</red>");
        if(money.signum()>0&&!economy.withdraw(p,money))return CompletableFuture.completedFuture("<red>金币扣款失败，请稍后重试。</red>");
        String code=randomCode(); InviteCode value=new InviteCode(code,CodeType.PERSONAL,p.getUniqueId(),p.getName(),money,items,List.of(),true,ip(p));
        CompletableFuture<String> result=new CompletableFuture<>();
        db.createPersonal(value).whenComplete((ok,error)->plugin.runPlayer(p,()->{
            if(error!=null||!ok){if(money.signum()>0)economy.deposit(p,money);if(error!=null)plugin.logError("创建个人邀请码失败",error);result.complete("<red>创建失败；你可能已经拥有邀请码，已退还托管金币。</red>");}
            else result.complete("<green>创建成功！已托管 <yellow>"+money.toPlainString()+"</yellow> 金币。你的专属邀请码：</green>"+
                    "<click:copy_to_clipboard:'"+code+"'><hover:show_text:'<green>点击复制邀请码</green>'>"+
                    "<yellow><underlined>"+code+"</underlined></yellow></hover></click>"+
                    " <gray>（点击复制）</gray>");
        }));return result;
    }
    private String randomCode(){String prefix=plugin.getConfig().getString("invite-code.prefix","SI-");String alphabet=plugin.getConfig().getString("invite-code.alphabet","ABCDEFGHJKLMNPQRSTUVWXYZ23456789");int n=plugin.getConfig().getInt("invite-code.random-length",8);StringBuilder b=new StringBuilder(prefix);java.security.SecureRandom r=new java.security.SecureRandom();for(int i=0;i<n;i++)b.append(alphabet.charAt(r.nextInt(alphabet.length())));return b.toString();}

    public CompletableFuture<String> use(Player p,String raw){
        int currentLevel=p.getLevel();UUID playerId=p.getUniqueId();String playerName=p.getName();String playerIp=ip(p);
        return db.findCode(raw).thenCompose(code->{
            if(code==null)return CompletableFuture.completedFuture("<red>邀请码不存在或已停用。</red>");
            if(code.type()==CodeType.OFFICIAL)return useOfficial(p,playerId,playerName,playerIp,code);
            if(tasks.size()<plugin.getConfig().getInt("tasks.count",3))return CompletableFuture.completedFuture("<red>可用任务数量不足，请联系管理员检查 tasks.yml。</red>");
            int max=plugin.getConfig().getBoolean("anti-abuse.enabled",true)?plugin.getConfig().getInt("anti-abuse.max-bindings-per-inviter-ip",1):0;
            boolean same=plugin.getConfig().getBoolean("anti-abuse.enabled",true)&&plugin.getConfig().getBoolean("anti-abuse.block-same-ip-as-inviter",true);
            return db.bindPersonal(code,playerId,playerName,playerIp,tasks.random(plugin.getConfig().getInt("tasks.count",3)),max,same).thenApply(r->{if(r.success())plugin.runPlayer(p,()->progress(p,TaskType.LEVEL,"",currentLevel));return r.success()?"<green>"+r.message()+"。</green>":"<red>"+r.message()+"。</red>";});
        });
    }

    private CompletableFuture<String> useOfficial(Player p,UUID playerId,String playerName,String playerIp,InviteCode code){
        if(code.money().signum()>0&&!economy.available())return CompletableFuture.completedFuture("<red>经济服务未连接，暂时无法领取这个官方邀请码。</red>");
        CompletableFuture<String> result=new CompletableFuture<>();
        db.claimOfficial(code.code(),playerId,playerName,playerIp).whenComplete((ok,error)->{
            if(error!=null){result.completeExceptionally(error);return;}if(!ok){result.complete("<red>你已经领取过这个官方邀请码。</red>");return;}
            plugin.runPlayer(p,()->{
                List<ItemStack> rewardItems;try{rewardItems=ItemCodec.decode(code.items());}catch(Exception ex){plugin.logError("官方邀请码物品读取失败",ex);db.unclaimOfficial(code.code(),playerId);result.complete("<red>奖励物品读取失败，领取记录已撤销。</red>");return;}
                if(!p.isOnline()||(code.money().signum()>0&&!economy.deposit(p,code.money()))){db.unclaimOfficial(code.code(),playerId);result.complete("<red>奖励发放失败，本次领取记录已撤销，请稍后重试。</red>");return;}
                for(ItemStack item:rewardItems){Map<Integer,ItemStack> overflow=p.getInventory().addItem(item);overflow.values().forEach(i->p.getWorld().dropItemNaturally(p.getLocation(),i));}
                runCommands(code.commands(),playerName,code.code());result.complete("<green>官方邀请码领取成功，奖励已经发放！</green>");
            });
        });return result;
    }

    public void progress(Player player,TaskType type,String key,int amount){
        if(!db.ready())return;
        db.progress(player.getUniqueId(),type,key==null?"":key,amount,tasks).thenAccept(result->{if(result.justCompleted())claim(result.relationId());}).exceptionally(ex->{plugin.logError("保存任务进度失败",ex);return null;});
    }

    public void claim(long relationId){
        db.claimBundle(relationId).thenAccept(bundle->{if(bundle==null)return;plugin.runGlobal(()->{Player player=Bukkit.getPlayer(bundle.inviteeUuid());if(player==null){db.unclaim(bundle.relationId());return;}plugin.runPlayer(player,()->grant(bundle,player));});}).exceptionally(ex->{plugin.logError("读取奖励失败",ex);return null;});
    }
    private void grant(Database.ClaimBundle bundle,Player p){
        InviteCode code=bundle.code(); OfflinePlayer receiver=p;
        List<ItemStack> rewardItems;try{rewardItems=ItemCodec.decode(code.items());}catch(Exception e){plugin.logError("反序列化个人邀请码奖励失败",e);db.unclaim(bundle.relationId());plugin.message(p,"<red>奖励物品读取失败，请联系管理员。</red>");return;}
        if(code.money().signum()>0&&!economy.deposit(receiver,code.money())){
            db.unclaim(bundle.relationId());plugin.message(p,"<red>金币奖励发放失败（经济插件未就绪），稍后登录会重试。</red>");return;
        }
        for(ItemStack item:rewardItems){Map<Integer,ItemStack> overflow=p.getInventory().addItem(item);overflow.values().forEach(i->p.getWorld().dropItemNaturally(p.getLocation(),i));}
        plugin.message(p,"<green>全部成长任务已完成，邀请奖励已经发放！</green>");
        runCommands(plugin.getConfig().getStringList("rewards.inviter-commands"),code.creatorName(),code.code());
        plugin.runGlobal(()->{Player inviter=Bukkit.getPlayer(code.creatorUuid());if(inviter!=null)plugin.runPlayer(inviter,()->plugin.message(inviter,"<green>你邀请的玩家 <yellow>"+bundle.inviteeName()+"</yellow> 已完成全部任务！</green>"));});
    }
    private void runCommands(List<String> commands,String player,String code){plugin.runGlobal(()->commands.forEach(command->Bukkit.dispatchCommand(Bukkit.getConsoleSender(),command.replace("%player_name%",player).replace("%code%",code))));}

    public void retryClaim(Player player){db.dashboard(player.getUniqueId(),tasks).thenAccept(d->{if(!d.tasks().isEmpty()&&d.tasks().stream().allMatch(TaskProgress::complete))claim(d.tasks().getFirst().relationId());});}
}
