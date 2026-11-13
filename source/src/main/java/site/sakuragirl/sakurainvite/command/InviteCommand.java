package site.sakuragirl.sakurainvite.command;

import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import site.sakuragirl.sakurainvite.SakuraInvitePlugin;

import java.util.*;

public final class InviteCommand implements CommandExecutor,TabCompleter {
    private final SakuraInvitePlugin plugin;
    public InviteCommand(SakuraInvitePlugin plugin){this.plugin=plugin;}
    @Override public boolean onCommand(@NotNull CommandSender sender,@NotNull Command command,@NotNull String label,@NotNull String[] args){
        if(args.length==0||args[0].equalsIgnoreCase("help")){help(sender);return true;}
        if(args.length>0&&args[0].equalsIgnoreCase("reload")){if(!sender.hasPermission("sakurainvite.reload")){msg(sender,"<red>没有权限。</red>");return true;}plugin.runGlobal(()->{plugin.reloadAll();plugin.runSender(sender,()->msg(sender,"<green>配置与任务已重新加载；数据库连接参数需重启生效。</green>"));});return true;}
        if(args.length>0&&args[0].equalsIgnoreCase("admin"))return admin(sender,args);
        if(!(sender instanceof Player p)){msg(sender,"<red>玩家命令只能在游戏内使用。</red>");return true;}
        switch(args[0].toLowerCase(Locale.ROOT)){
            case "gui"->plugin.gui().openCenter(p);
            case "create"->{if(!p.hasPermission("sakurainvite.create"))msg(p,"<red>没有权限。</red>");else plugin.gui().openCreate(p);}
            case "use"->{if(!p.hasPermission("sakurainvite.use")){msg(p,"<red>没有权限。</red>");break;}if(args.length<2){msg(p,"<yellow>用法：/invite use <邀请码></yellow>");break;}if(!plugin.database().ready()){msg(p,"<red>数据库未连接。</red>");break;}plugin.service().use(p,args[1]).thenAccept(text->plugin.runPlayer(p,()->msg(p,text))).exceptionally(ex->{plugin.logError("使用邀请码失败",ex);plugin.runPlayer(p,()->msg(p,"<red>操作失败，请稍后重试。</red>"));return null;});}
            case "records"->plugin.showRecords(p);
            case "top","rank"->plugin.showTop(p);
            default->help(p);
        }return true;
    }
    private boolean admin(CommandSender s,String[] a){
        if(!s.hasPermission("sakurainvite.admin")){msg(s,"<red>没有权限。</red>");return true;}
        if(a.length>=2&&a[1].equalsIgnoreCase("official")){if(s instanceof Player p)plugin.officialGui().openList(p);else msg(s,"<red>官方码管理 GUI 只能由玩家打开。</red>");return true;}
        if(a.length<3){msg(s,"<yellow>/invite admin official | createofficial <代码> | editofficial <代码> | deletecode <代码></yellow>");return true;}
        String code=a[2].trim();if(!code.matches("[A-Za-z0-9_-]{3,32}")){msg(s,"<red>代码只能含字母、数字、_、-，长度 3-32。</red>");return true;}
        if(a[1].equalsIgnoreCase("createofficial")){if(!(s instanceof Player p)){msg(s,"<red>请由游戏内管理员创建，以便打开奖励设置 GUI。</red>");return true;}plugin.database().createOfficial(code).thenAccept(ok->plugin.runPlayer(p,()->{if(ok){msg(s,"<green>官方邀请码已创建："+code+"，请设置奖励。</green>");plugin.officialGui().openEditor(p,code);}else msg(s,"<red>该邀请码已存在。</red>");})).exceptionally(ex->{plugin.logError("创建官方码失败",ex);return null;});}
        else if(a[1].equalsIgnoreCase("editofficial")){if(s instanceof Player p)plugin.officialGui().openEditor(p,code);else msg(s,"<red>奖励编辑 GUI 只能由玩家打开。</red>");}
        else if(a[1].equalsIgnoreCase("deletecode"))plugin.database().deleteCode(code).thenAccept(ok->plugin.runSender(s,()->msg(s,ok?"<green>邀请码已停用。</green>":"<red>未找到邀请码。</red>"))).exceptionally(ex->{plugin.logError("停用邀请码失败",ex);return null;});
        else msg(s,"<red>未知管理命令。</red>");return true;
    }
    private void help(CommandSender s){msg(s,"<gradient:#ff8fbd:#ffd5e5>===== SakuraInvite 指令帮助 =====</gradient>");msg(s,"<yellow>/invite help</yellow> <gray>- 查看本帮助</gray>");msg(s,"<yellow>/invite gui</yellow> <gray>- 打开邀请中心</gray>");msg(s,"<yellow>/invite create</yellow> <gray>- 创建个人邀请码并设置托管奖励</gray>");msg(s,"<yellow>/invite use <邀请码></yellow> <gray>- 使用个人或官方邀请码</gray>");msg(s,"<yellow>/invite records</yellow> <gray>- 查看邀请记录</gray>");msg(s,"<yellow>/invite top</yellow> <gray>- 查看邀请排行榜</gray>");if(s.hasPermission("sakurainvite.admin")){msg(s,"<light_purple>/invite admin official</light_purple> <gray>- 官方码管理 GUI</gray>");msg(s,"<light_purple>/invite admin createofficial <代码></light_purple> <gray>- 创建官方码并设置奖励</gray>");msg(s,"<light_purple>/invite reload</light_purple> <gray>- 重载配置与任务</gray>");}}
    private void msg(CommandSender s,String text){plugin.message(s,text);}
    @Override public @Nullable List<String> onTabComplete(@NotNull CommandSender s,@NotNull Command c,@NotNull String alias,@NotNull String[] a){
        List<String> values;if(a.length==1){values=new ArrayList<>(List.of("help","gui","create","use","records","top"));if(s.hasPermission("sakurainvite.admin"))values.addAll(List.of("admin","reload"));}
        else if(a.length==2&&a[0].equalsIgnoreCase("admin"))values=List.of("official","createofficial","editofficial","deletecode");else return List.of();String q=a[a.length-1].toLowerCase(Locale.ROOT);return values.stream().filter(x->x.startsWith(q)).toList();
    }
}
