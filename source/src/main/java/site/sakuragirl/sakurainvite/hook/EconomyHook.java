package site.sakuragirl.sakurainvite.hook;

import java.math.BigDecimal;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import site.sakuragirl.sakurainvite.SakuraInvitePlugin;
import site.sakuragirl.sakurainvite.util.Amounts;

/** Optional Vault linkage is isolated in a nested bridge. All calls are on Paper's main thread. */
public final class EconomyHook {
  private interface Bridge {
    boolean deposit(OfflinePlayer p, double n);

    boolean withdraw(OfflinePlayer p, double n);

    boolean has(OfflinePlayer p, double n);

    String name();
  }

  private final SakuraInvitePlugin plugin;
  private Bridge bridge;

  public EconomyHook(SakuraInvitePlugin plugin) {
    this.plugin = plugin;
  }

  public void hook() {
    bridge = null;
    if (!Bukkit.getPluginManager().isPluginEnabled("Vault")) return;
    try {
      var provider =
          Bukkit.getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
      if (provider != null) bridge = new VaultBridge(provider.getProvider());
    } catch (LinkageError | RuntimeException e) {
      plugin.logError("Vault 接入失败，金币功能关闭", e);
    }
    if (bridge != null) plugin.getLogger().info("已连接经济服务：" + bridge.name());
  }

  public boolean available() {
    return bridge != null && Bukkit.getPluginManager().isPluginEnabled("Vault");
  }

  public boolean deposit(OfflinePlayer p, BigDecimal n) {
    n = Amounts.validate(n);
    return n.signum() == 0 || available() && bridge.deposit(p, n.doubleValue());
  }

  public boolean withdraw(OfflinePlayer p, BigDecimal n) {
    n = Amounts.validate(n);
    return n.signum() == 0 || available() && bridge.withdraw(p, n.doubleValue());
  }

  public boolean has(OfflinePlayer p, BigDecimal n) {
    n = Amounts.validate(n);
    return n.signum() == 0 || available() && bridge.has(p, n.doubleValue());
  }

  private static final class VaultBridge implements Bridge {
    private final net.milkbowl.vault.economy.Economy provider;

    VaultBridge(net.milkbowl.vault.economy.Economy provider) {
      this.provider = provider;
    }

    public boolean deposit(OfflinePlayer p, double n) {
      return provider.depositPlayer(p, n).transactionSuccess();
    }

    public boolean withdraw(OfflinePlayer p, double n) {
      return provider.withdrawPlayer(p, n).transactionSuccess();
    }

    public boolean has(OfflinePlayer p, double n) {
      return provider.has(p, n);
    }

    public String name() {
      return provider.getName();
    }
  }
}
