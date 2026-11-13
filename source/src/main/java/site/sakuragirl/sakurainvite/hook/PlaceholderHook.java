package site.sakuragirl.sakurainvite.hook;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import site.sakuragirl.sakurainvite.SakuraInvitePlugin;
import site.sakuragirl.sakurainvite.model.Models.Dashboard;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlaceholderHook extends PlaceholderExpansion {
    private final SakuraInvitePlugin plugin;private final Map<UUID,Dashboard> cache=new ConcurrentHashMap<>();
    public PlaceholderHook(SakuraInvitePlugin plugin){this.plugin=plugin;}
    @Override public @NotNull String getIdentifier(){return "sakurainvite";}
    @Override public @NotNull String getAuthor(){return "Sakura_Girl";}
    @Override public @NotNull String getVersion(){return plugin.getPluginMeta().getVersion();}
    @Override public boolean persist(){return true;}
    @Override public String onRequest(OfflinePlayer p,@NotNull String id){if(p==null)return "";Dashboard d=cache.get(p.getUniqueId());plugin.database().dashboard(p.getUniqueId(),plugin.tasks()).thenAccept(v->cache.put(p.getUniqueId(),v));if(d==null)return "...";String key=id.toLowerCase();if(key.startsWith("invitee_")){try{int index=Integer.parseInt(key.substring(8))-1;return index>=0&&index<d.inviteeNames().size()?d.inviteeNames().get(index):"";}catch(NumberFormatException ignored){return "";}}return switch(key){case "code"->d.ownCode()==null?"":d.ownCode();case "inviter"->d.inviterName()==null?"":d.inviterName();case "invitees"->String.valueOf(d.invitedCount());case "invitee_names"->String.join(", ",d.inviteeNames());case "valid_count"->String.valueOf(d.validCount());case "rank"->d.rank()==0?"未上榜":String.valueOf(d.rank());case "task_progress"->d.tasks().isEmpty()?"0/0":d.tasks().stream().filter(t->t.complete()).count()+"/"+d.tasks().size();default->null;};}
}
