package site.sakuragirl.sakurainvite.hook;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import site.sakuragirl.sakurainvite.SakuraInvitePlugin;

import java.math.BigDecimal;

public final class EconomyHook {
    private final SakuraInvitePlugin plugin;
    private Economy economy;
    public EconomyHook(SakuraInvitePlugin plugin) { this.plugin=plugin; }
    public void hook() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) return;
        RegisteredServiceProvider<Economy> provider=Bukkit.getServicesManager().getRegistration(Economy.class);
        economy=provider==null?null:provider.getProvider();
        if(economy!=null)plugin.getLogger().info("已连接经济服务: "+economy.getName());
    }
    public boolean available(){return economy!=null;}
    public boolean deposit(OfflinePlayer player, BigDecimal amount){return amount.signum()<=0 || economy!=null&&economy.depositPlayer(player,amount.doubleValue()).transactionSuccess();}
    public boolean withdraw(OfflinePlayer player,BigDecimal amount){return amount.signum()<=0 || economy!=null&&economy.withdrawPlayer(player,amount.doubleValue()).transactionSuccess();}
    public boolean has(OfflinePlayer player,BigDecimal amount){return amount.signum()<=0 || economy!=null&&economy.has(player,amount.doubleValue());}
}
