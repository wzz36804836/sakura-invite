package site.sakuragirl.sakurainvite;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import site.sakuragirl.sakurainvite.command.InviteCommand;
import site.sakuragirl.sakurainvite.db.Database;
import site.sakuragirl.sakurainvite.gui.GuiManager;
import site.sakuragirl.sakurainvite.gui.OfficialGuiManager;
import site.sakuragirl.sakurainvite.hook.EconomyHook;
import site.sakuragirl.sakurainvite.hook.PlaceholderHook;
import site.sakuragirl.sakurainvite.listener.ProgressListener;
import site.sakuragirl.sakurainvite.model.Models.LeaderboardEntry;
import site.sakuragirl.sakurainvite.service.InviteService;
import site.sakuragirl.sakurainvite.task.TaskRegistry;

import java.util.concurrent.CompletionException;
import java.util.logging.Level;

public final class SakuraInvitePlugin extends JavaPlugin {
    private static final MiniMessage MM=MiniMessage.miniMessage();
    private Database database;private TaskRegistry tasks;private EconomyHook economy;private InviteService service;private GuiManager gui;private OfficialGuiManager officialGui;
    @Override public void onEnable(){
        saveDefaultConfig();getConfig().options().copyDefaults(true);saveConfig();if(!new java.io.File(getDataFolder(),"tasks.yml").exists())saveResource("tasks.yml",false);tasks=new TaskRegistry(this);tasks.reload();database=new Database(this);economy=new EconomyHook(this);economy.hook();service=new InviteService(this,database,tasks,economy);gui=new GuiManager(this,service);officialGui=new OfficialGuiManager(this);
        Bukkit.getPluginManager().registerEvents(gui,this);Bukkit.getPluginManager().registerEvents(officialGui,this);Bukkit.getPluginManager().registerEvents(new ProgressListener(this,service),this);
        InviteCommand handler=new InviteCommand(this);getCommand("invite").setExecutor(handler);getCommand("invite").setTabCompleter(handler);
        if(Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI"))new PlaceholderHook(this).register();
        database.connect().thenRun(()->getLogger().info(database.storageName()+" 已连接，数据表检查完成。" )).exceptionally(ex->{logError("MySQL 与 SQLite 均无法初始化；邀请功能将暂不可用，请检查 config.yml",ex);return null;});
        getLogger().info("SakuraInvite v"+getPluginMeta().getVersion()+" 已启用，加载任务 "+tasks.size()+" 个。");
    }
    @Override public void onDisable(){if(gui!=null)gui.shutdown();if(officialGui!=null)officialGui.shutdown();if(database!=null)database.close();}
    public void reloadAll(){reloadConfig();tasks.reload();economy.hook();}
    public void message(CommandSender target,String text){String prefix=getConfig().getString("messages.prefix","");target.sendMessage(MM.deserialize(prefix+text));}
    public void runGlobal(Runnable action){Bukkit.getGlobalRegionScheduler().execute(this,action);}
    public void runPlayer(Player player,Runnable action){player.getScheduler().run(this,task->action.run(),null);}
    public void runSender(CommandSender sender,Runnable action){if(sender instanceof Player player)runPlayer(player,action);else runGlobal(action);}
    public void logError(String message,Throwable error){Throwable t=error instanceof CompletionException&&error.getCause()!=null?error.getCause():error;getLogger().log(Level.SEVERE,message,t);}
    public void showRecords(Player p){if(!database.ready()){message(p,"<red>数据库未连接。</red>");return;}database.records(p.getUniqueId(),10).thenAccept(list->runPlayer(p,()->{message(p,"<light_purple>最近邀请记录：</light_purple>");if(list.isEmpty())message(p,"<gray>暂无记录。</gray>");else list.forEach(r->message(p,(r.completed()?"<green>✔ ":"<yellow>⌛ ")+r.inviteeName()+" <gray>使用 "+r.code()+"</gray>"));})).exceptionally(ex->{logError("读取记录失败",ex);return null;});}
    public void showTop(Player p){if(!database.ready()){message(p,"<red>数据库未连接。</red>");return;}database.top(10).thenAccept(list->runPlayer(p,()->{message(p,"<gold>邀请排行榜（有效邀请）：</gold>");if(list.isEmpty())message(p,"<gray>暂时无人上榜。</gray>");for(LeaderboardEntry e:list)message(p,"<yellow>#"+e.rank()+"</yellow> <white>"+e.name()+"</white> <green>"+e.validCount()+" 次</green>");})).exceptionally(ex->{logError("读取排行榜失败",ex);return null;});}
    public Database database(){return database;}public TaskRegistry tasks(){return tasks;}public InviteService service(){return service;}public GuiManager gui(){return gui;}public OfficialGuiManager officialGui(){return officialGui;}
}
