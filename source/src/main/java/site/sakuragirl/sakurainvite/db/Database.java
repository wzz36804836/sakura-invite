package site.sakuragirl.sakurainvite.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import site.sakuragirl.sakurainvite.SakuraInvitePlugin;
import site.sakuragirl.sakurainvite.model.Models.*;
import site.sakuragirl.sakurainvite.task.TaskRegistry;

public final class Database implements AutoCloseable {
  public record BindResult(boolean success, String message, long relationId) {}

  public record ProgressResult(boolean changed, boolean justCompleted, long relationId) {}

  private final java.util.function.Supplier<org.bukkit.configuration.file.FileConfiguration>
      configuration;
  private final java.io.File folder;
  private final java.util.logging.Logger logger;
  private volatile boolean initialized;
  private volatile boolean stopping;
  private final ExecutorService executor =
      new ThreadPoolExecutor(
          1,
          1,
          0L,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(2048),
          r -> {
            Thread t = new Thread(r, "SakuraInvite-Database");
            t.setDaemon(true);
            return t;
          });
  private volatile HikariDataSource source;
  private volatile Dialect dialect = Dialect.MYSQL;

  private enum Dialect {
    MYSQL,
    SQLITE
  }

  public Database(SakuraInvitePlugin plugin) {
    this(plugin::getConfig, plugin.getDataFolder(), plugin.getLogger());
  }

  public Database(
      java.util.function.Supplier<org.bukkit.configuration.file.FileConfiguration> configuration,
      java.io.File folder,
      java.util.logging.Logger logger) {
    this.configuration = configuration;
    this.folder = folder;
    this.logger = logger;
  }

  public boolean ready() {
    return initialized && !stopping && source != null && !source.isClosed();
  }

  public String storageName() {
    return dialect == Dialect.SQLITE ? "SQLite (SakuraInvite.db)" : "MySQL";
  }

  public CompletableFuture<Void> connect() {
    return CompletableFuture.runAsync(
        () -> {
          try {
            String storage = configuration.get().getString("storage", "auto");
            if (storage.equalsIgnoreCase("sqlite")) {
              connectSqlite();
              migrate();
            } else {
              try {
                connectMysql();
              } catch (Exception error) {
                closeSource();
                if (!storage.equalsIgnoreCase("auto")
                    || !configuration.get().getBoolean("mysql.fallback-to-sqlite", true))
                  throw error;
                logger.warning("MySQL 初始化失败，使用本地 SQLite；请确认这与原有数据源一致。原因：" + rootMessage(error));
                connectSqlite();
              }
              migrate();
            }
            initialized = true;
          } catch (Exception error) {
            closeSource();
            throw new CompletionException(error);
          }
        },
        executor);
  }

  private void connectMysql() {
    HikariConfig cfg = new HikariConfig();
    String base = "mysql.";
    String url =
        "jdbc:mysql://"
            + configuration.get().getString(base + "host")
            + ":"
            + configuration.get().getInt(base + "port")
            + "/"
            + configuration.get().getString(base + "database")
            + "?"
            + configuration
                .get()
                .getString(
                    base + "parameters", "useUnicode=true&characterEncoding=utf8&useSSL=false");
    cfg.setJdbcUrl(url);
    cfg.setUsername(configuration.get().getString(base + "username"));
    cfg.setPassword(configuration.get().getString(base + "password"));
    cfg.setMaximumPoolSize(Math.max(2, configuration.get().getInt(base + "pool-size", 10)));
    cfg.setMinimumIdle(1);
    cfg.setPoolName("SakuraInvite-Pool");
    cfg.setConnectionTimeout(10_000);
    cfg.setInitializationFailTimeout(10_000);
    dialect = Dialect.MYSQL;
    source = new HikariDataSource(cfg);
  }

  private void connectSqlite() {
    Path root = folder.toPath().toAbsolutePath().normalize();
    Path file =
        root.resolve(configuration.get().getString("sqlite.file", "SakuraInvite.db")).normalize();
    if (!file.startsWith(root))
      throw new CompletionException(new IllegalArgumentException("sqlite.file 必须位于插件目录内"));
    HikariConfig cfg = new HikariConfig();
    cfg.setJdbcUrl("jdbc:sqlite:" + file);
    cfg.setDriverClassName("org.sqlite.JDBC");
    cfg.setMaximumPoolSize(1);
    cfg.setMinimumIdle(1);
    cfg.setPoolName("SakuraInvite-SQLite");
    cfg.setConnectionTestQuery("SELECT 1");
    cfg.setConnectionInitSql("PRAGMA foreign_keys=ON");
    cfg.setInitializationFailTimeout(10_000);
    dialect = Dialect.SQLITE;
    source = new HikariDataSource(cfg);
  }

  private void migrate() {
    String[] mysql = {
      "CREATE TABLE IF NOT EXISTS si_codes (code VARCHAR(32) PRIMARY KEY, code_type VARCHAR(16) NOT"
          + " NULL, creator_uuid CHAR(36) NULL UNIQUE, creator_name VARCHAR(32) NULL, creator_ip"
          + " VARCHAR(45) NULL, reward_money DECIMAL(20,2) NOT NULL DEFAULT 0, reward_items"
          + " MEDIUMBLOB NULL, reward_commands TEXT NULL, active BOOLEAN NOT NULL DEFAULT TRUE,"
          + " created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP) ENGINE=InnoDB DEFAULT"
          + " CHARSET=utf8mb4",
      "CREATE TABLE IF NOT EXISTS si_relations (id BIGINT PRIMARY KEY AUTO_INCREMENT, code"
          + " VARCHAR(32) NOT NULL, inviter_uuid CHAR(36) NOT NULL, inviter_name VARCHAR(32) NOT"
          + " NULL, invitee_uuid CHAR(36) NOT NULL UNIQUE, invitee_name VARCHAR(32) NOT NULL,"
          + " invitee_ip VARCHAR(45) NULL, completed BOOLEAN NOT NULL DEFAULT FALSE, reward_claimed"
          + " BOOLEAN NOT NULL DEFAULT FALSE, bound_at TIMESTAMP NOT NULL DEFAULT"
          + " CURRENT_TIMESTAMP, completed_at TIMESTAMP NULL, INDEX idx_inviter(inviter_uuid),"
          + " INDEX idx_code(code), CONSTRAINT fk_si_relation_code FOREIGN KEY(code) REFERENCES"
          + " si_codes(code)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
      "CREATE TABLE IF NOT EXISTS si_tasks (relation_id BIGINT NOT NULL, definition_id VARCHAR(64)"
          + " NOT NULL, task_type VARCHAR(24) NOT NULL, task_name VARCHAR(128) NOT NULL,"
          + " task_targets TEXT NULL, progress INT NOT NULL DEFAULT 0, target INT NOT NULL, PRIMARY"
          + " KEY(relation_id, definition_id), CONSTRAINT fk_si_task_relation FOREIGN"
          + " KEY(relation_id) REFERENCES si_relations(id) ON DELETE CASCADE) ENGINE=InnoDB DEFAULT"
          + " CHARSET=utf8mb4",
      "CREATE TABLE IF NOT EXISTS si_official_claims (code VARCHAR(32) NOT NULL, player_uuid"
          + " CHAR(36) NOT NULL, player_name VARCHAR(32) NOT NULL, player_ip VARCHAR(45) NULL,"
          + " claimed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(code,"
          + " player_uuid), CONSTRAINT fk_si_official_code FOREIGN KEY(code) REFERENCES"
          + " si_codes(code) ON DELETE CASCADE) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
      "CREATE TABLE IF NOT EXISTS si_players (uuid CHAR(36) PRIMARY KEY, player_name VARCHAR(32)"
          + " NOT NULL, last_ip VARCHAR(45) NULL, updated_at TIMESTAMP NOT NULL DEFAULT"
          + " CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
    };
    String[] sqlite = {
      "CREATE TABLE IF NOT EXISTS si_codes (code TEXT PRIMARY KEY, code_type TEXT NOT NULL,"
          + " creator_uuid TEXT NULL UNIQUE, creator_name TEXT NULL, creator_ip TEXT NULL,"
          + " reward_money NUMERIC NOT NULL DEFAULT 0, reward_items BLOB NULL, reward_commands TEXT"
          + " NULL, active INTEGER NOT NULL DEFAULT 1, created_at TEXT NOT NULL DEFAULT"
          + " CURRENT_TIMESTAMP)",
      "CREATE TABLE IF NOT EXISTS si_relations (id INTEGER PRIMARY KEY AUTOINCREMENT, code TEXT NOT"
          + " NULL, inviter_uuid TEXT NOT NULL, inviter_name TEXT NOT NULL, invitee_uuid TEXT NOT"
          + " NULL UNIQUE, invitee_name TEXT NOT NULL, invitee_ip TEXT NULL, completed INTEGER NOT"
          + " NULL DEFAULT 0, reward_claimed INTEGER NOT NULL DEFAULT 0, bound_at TEXT NOT NULL"
          + " DEFAULT CURRENT_TIMESTAMP, completed_at TEXT NULL, FOREIGN KEY(code) REFERENCES"
          + " si_codes(code))",
      "CREATE INDEX IF NOT EXISTS idx_si_relations_inviter ON si_relations(inviter_uuid)",
      "CREATE INDEX IF NOT EXISTS idx_si_relations_code ON si_relations(code)",
      "CREATE TABLE IF NOT EXISTS si_tasks (relation_id INTEGER NOT NULL, definition_id TEXT NOT"
          + " NULL, task_type TEXT NOT NULL, task_name TEXT NOT NULL, task_targets TEXT NULL,"
          + " progress INTEGER NOT NULL DEFAULT 0, target INTEGER NOT NULL, PRIMARY"
          + " KEY(relation_id,definition_id), FOREIGN KEY(relation_id) REFERENCES si_relations(id)"
          + " ON DELETE CASCADE)",
      "CREATE TABLE IF NOT EXISTS si_official_claims (code TEXT NOT NULL, player_uuid TEXT NOT"
          + " NULL, player_name TEXT NOT NULL, player_ip TEXT NULL, claimed_at TEXT NOT NULL"
          + " DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(code,player_uuid), FOREIGN KEY(code)"
          + " REFERENCES si_codes(code) ON DELETE CASCADE)",
      "CREATE TABLE IF NOT EXISTS si_players (uuid TEXT PRIMARY KEY, player_name TEXT NOT NULL,"
          + " last_ip TEXT NULL, updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)"
    };
    try (Connection c = source.getConnection();
        Statement s = c.createStatement()) {
      for (String statement : dialect == Dialect.MYSQL ? mysql : sqlite) s.executeUpdate(statement);
      s.executeUpdate(
          "CREATE TABLE IF NOT EXISTS si_meta (meta_key VARCHAR(32) PRIMARY KEY, meta_value INTEGER"
              + " NOT NULL)");
      try (ResultSet version =
          s.executeQuery("SELECT meta_value FROM si_meta WHERE meta_key='schema'")) {
        if (version.next() && version.getInt(1) > 130) throw new SQLException("数据库版本比本插件新，禁止降级写入");
      }

      ensureColumn(c, "si_codes", "reward_commands", "TEXT NULL");
      ensureColumn(c, "si_tasks", "task_type", "VARCHAR(24) NOT NULL DEFAULT 'ONLINE_MINUTES'");
      ensureColumn(c, "si_tasks", "task_name", "VARCHAR(128) NOT NULL DEFAULT '历史任务'");
      ensureColumn(c, "si_tasks", "task_targets", "TEXT NULL");
      ensureColumn(c, "si_codes", "available_bundles", "INTEGER NOT NULL DEFAULT 0");
      ensureColumn(c, "si_relations", "reserved", "BOOLEAN NOT NULL DEFAULT FALSE");
      ensureColumn(c, "si_relations", "reserved_money", "DECIMAL(20,2) NULL");
      ensureColumn(
          c,
          "si_relations",
          "reserved_items",
          (dialect == Dialect.MYSQL ? "MEDIUMBLOB" : "BLOB") + " NULL");
      s.executeUpdate(
          "CREATE TABLE IF NOT EXISTS si_funding_ops (id VARCHAR(36) PRIMARY KEY, result"
              + " VARCHAR(32) NOT NULL, code VARCHAR(32) NOT NULL)");
      ensureColumn(c, "si_codes", "code_key", "VARCHAR(32) NULL");
      ensureColumn(c, "si_codes", "expires_at", "BIGINT NOT NULL DEFAULT 0");
      ensureColumn(c, "si_codes", "max_uses", "INTEGER NOT NULL DEFAULT 0");
      s.executeUpdate("UPDATE si_codes SET code_key=UPPER(code) WHERE code_key IS NULL");
      boolean indexed = false;
      try (ResultSet indexes =
          c.getMetaData().getIndexInfo(c.getCatalog(), null, "si_codes", false, false)) {
        while (indexes.next())
          if ("si_code_key_unique".equalsIgnoreCase(indexes.getString("INDEX_NAME")))
            indexed = true;
      }
      if (!indexed) s.executeUpdate("CREATE UNIQUE INDEX si_code_key_unique ON si_codes(code_key)");
      s.executeUpdate(
          "CREATE TABLE IF NOT EXISTS si_deliveries (id VARCHAR(100) PRIMARY KEY, player_uuid"
              + " VARCHAR(36) NOT NULL, player_name VARCHAR(32) NOT NULL, code VARCHAR(32) NOT"
              + " NULL, money DECIMAL(20,2) NOT NULL DEFAULT 0, items "
              + (dialect == Dialect.MYSQL ? "MEDIUMBLOB" : "BLOB")
              + " NULL, commands TEXT NULL, status VARCHAR(16) NOT NULL DEFAULT 'PENDING', detail"
              + " VARCHAR(512) NULL, created_at BIGINT NOT NULL)");
      s.executeUpdate(
          "UPDATE si_deliveries SET status='REVIEW',detail='上次发放中断，请核对实际到账后处理' WHERE"
              + " status='PROCESSING'");
      if (dialect == Dialect.MYSQL)
        s.executeUpdate(
            "INSERT INTO si_meta(meta_key,meta_value) VALUES('schema',130) ON DUPLICATE KEY UPDATE"
                + " meta_value=130");
      else
        s.executeUpdate(
            "INSERT INTO si_meta(meta_key,meta_value) VALUES('schema',130) ON CONFLICT(meta_key) DO"
                + " UPDATE SET meta_value=130");

    } catch (SQLException e) {
      throw new CompletionException(e);
    }
  }

  private void ensureColumn(Connection c, String table, String column, String definition)
      throws SQLException {
    try (ResultSet rs = c.getMetaData().getColumns(c.getCatalog(), null, table, column)) {
      if (rs.next()) return;
    }
    try (Statement s = c.createStatement()) {
      s.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    }
  }

  private <T> CompletableFuture<T> async(Function<Connection, T> action) {
    if (!ready()) return CompletableFuture.failedFuture(new IllegalStateException("数据库尚未就绪或正在关闭"));
    try {
      return CompletableFuture.supplyAsync(
          () -> {
            try (Connection c = source.getConnection()) {
              return action.apply(c);
            } catch (SQLException e) {
              throw new CompletionException(e);
            }
          },
          executor);
    } catch (RejectedExecutionException error) {
      return CompletableFuture.failedFuture(error);
    }
  }

  public CompletableFuture<Void> touchPlayer(UUID uuid, String name, String ip) {
    return async(
        c -> {
          String sql =
              dialect == Dialect.MYSQL
                  ? "INSERT INTO si_players(uuid,player_name,last_ip) VALUES(?,?,?) ON DUPLICATE"
                      + " KEY UPDATE player_name=VALUES(player_name),last_ip=VALUES(last_ip)"
                  : "INSERT INTO si_players(uuid,player_name,last_ip) VALUES(?,?,?) ON"
                        + " CONFLICT(uuid) DO UPDATE SET"
                        + " player_name=excluded.player_name,last_ip=excluded.last_ip,updated_at=CURRENT_TIMESTAMP";
          try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setString(3, ip);
            ps.executeUpdate();
            return null;
          } catch (SQLException e) {
            throw new CompletionException(e);
          }
        });
  }

  public CompletableFuture<Boolean> createOfficial(String code) {
    return async(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO si_codes(code,code_key,code_type,reward_money,active)"
                      + " VALUES(?,UPPER(?),'OFFICIAL',0,FALSE)")) {
            ps.setString(1, code);
            ps.setString(2, code);
            return ps.executeUpdate() == 1;
          } catch (SQLException e) {
            if (isConstraint(e)) return false;
            throw new CompletionException(e);
          }
        });
  }

  public CompletableFuture<Boolean> updateOfficial(
      String code, BigDecimal money, byte[] items, boolean replaceItems, List<String> commands) {
    return async(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE si_codes SET reward_money=?,reward_commands=?,reward_items=CASE WHEN ?"
                      + " THEN ? ELSE reward_items END WHERE code_key=UPPER(?) AND"
                      + " code_type='OFFICIAL'")) {
            ps.setBigDecimal(1, money);
            ps.setString(2, String.join("\n", commands));
            ps.setBoolean(3, replaceItems);
            ps.setBytes(4, items);
            ps.setString(5, code);
            return ps.executeUpdate() == 1;
          } catch (SQLException e) {
            throw new CompletionException(e);
          }
        });
  }

  public CompletableFuture<List<OfficialCodeView>> officialCodes() {
    return async(
        c -> {
          List<OfficialCodeView> out = new ArrayList<>();
          try (PreparedStatement ps =
                  c.prepareStatement(
                      "SELECT c.*,(SELECT COUNT(*) FROM si_official_claims o WHERE o.code=c.code)"
                          + " use_count FROM si_codes c WHERE c.code_type='OFFICIAL' ORDER BY"
                          + " c.created_at DESC");
              ResultSet rs = ps.executeQuery()) {
            while (rs.next())
              out.add(
                  new OfficialCodeView(
                      rs.getString("code"),
                      rs.getBigDecimal("reward_money"),
                      rs.getBytes("reward_items"),
                      splitCommands(rs.getString("reward_commands")),
                      rs.getInt("use_count"),
                      rs.getBoolean("active")));
          } catch (SQLException e) {
            throw new CompletionException(e);
          }
          return out;
        });
  }

  public CompletableFuture<Boolean> deleteCode(String code) {
    return async(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement("UPDATE si_codes SET active=FALSE WHERE code_key=UPPER(?)")) {
            ps.setString(1, code);
            return ps.executeUpdate() == 1;
          } catch (SQLException e) {
            throw new CompletionException(e);
          }
        });
  }

  public CompletableFuture<InviteCode> findAnyCode(String raw) {
    return async(c -> selectCode(c, raw, false));
  }

  public CompletableFuture<InviteCode> findCode(String raw) {
    return async(c -> selectCode(c, raw));
  }

  private InviteCode selectCode(Connection c, String raw) {
    return selectCode(c, raw, true);
  }

  private InviteCode selectCode(Connection c, String raw, boolean activeOnly) {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT * FROM si_codes WHERE code_key=UPPER(?)"
                + (activeOnly ? " AND active=TRUE" : ""))) {
      ps.setString(1, raw);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) return null;
        String uuid = rs.getString("creator_uuid");
        return new InviteCode(
            rs.getString("code"),
            CodeType.valueOf(rs.getString("code_type")),
            uuid == null ? null : UUID.fromString(uuid),
            rs.getString("creator_name"),
            rs.getBigDecimal("reward_money"),
            rs.getBytes("reward_items"),
            splitCommands(rs.getString("reward_commands")),
            rs.getBoolean("active"),
            rs.getString("creator_ip"));
      }
    } catch (SQLException e) {
      throw new CompletionException(e);
    }
  }

  public CompletableFuture<BindResult> bindPersonal(
      InviteCode code,
      UUID player,
      String name,
      String ip,
      List<TaskDefinition> tasks,
      int maxSameIp,
      boolean blockCreatorIp) {
    return async(
        c -> {
          try {
            c.setAutoCommit(false);
            try (PreparedStatement lock =
                c.prepareStatement(
                    "SELECT active,available_bundles FROM si_codes WHERE code=?"
                        + (dialect == Dialect.MYSQL ? " FOR UPDATE" : ""))) {
              lock.setString(1, code.code());
              try (ResultSet row = lock.executeQuery()) {
                if (!row.next() || !row.getBoolean(1)) return rollback(c, "邀请码已停用");
                if (row.getInt(2) < 1) return rollback(c, "邀请人奖励库存不足，请邀请人先 /invite fund 补充一份奖励");
              }
            }
            if (code.creatorUuid().equals(player)) return rollback(c, "不能使用自己的邀请码");
            if (blockCreatorIp && ip != null && ip.equals(code.creatorIp()))
              return rollback(c, "当前 IP 与邀请人相同，已触发防刷限制");
            try (PreparedStatement exists =
                c.prepareStatement("SELECT 1 FROM si_relations WHERE invitee_uuid=?")) {
              exists.setString(1, player.toString());
              try (ResultSet rs = exists.executeQuery()) {
                if (rs.next()) return rollback(c, "你已经绑定过个人邀请码");
              }
            }
            if (maxSameIp > 0 && ip != null) {
              try (PreparedStatement count =
                  c.prepareStatement(
                      "SELECT COUNT(*) FROM si_relations WHERE inviter_uuid=? AND invitee_ip=?")) {
                count.setString(1, code.creatorUuid().toString());
                count.setString(2, ip);
                try (ResultSet rs = count.executeQuery()) {
                  rs.next();
                  if (rs.getInt(1) >= maxSameIp) return rollback(c, "该 IP 的邀请次数已达到上限");
                }
              }
            }
            long id;
            try (PreparedStatement ps =
                c.prepareStatement(
                    "INSERT INTO"
                        + " si_relations(code,inviter_uuid,inviter_name,invitee_uuid,invitee_name,invitee_ip)"
                        + " VALUES(?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
              ps.setString(1, code.code());
              ps.setString(2, code.creatorUuid().toString());
              ps.setString(3, code.creatorName());
              ps.setString(4, player.toString());
              ps.setString(5, name);
              ps.setString(6, ip);
              ps.executeUpdate();
              try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) id = keys.getLong(1);
                else if (dialect == Dialect.SQLITE)
                  try (Statement last = c.createStatement();
                      ResultSet rs = last.executeQuery("SELECT last_insert_rowid()")) {
                    rs.next();
                    id = rs.getLong(1);
                  }
                else throw new SQLException("未取得邀请关系 ID");
              }
            }
            try (PreparedStatement ps =
                c.prepareStatement(
                    "INSERT INTO"
                        + " si_tasks(relation_id,definition_id,task_type,task_name,task_targets,target)"
                        + " VALUES(?,?,?,?,?,?)")) {
              for (TaskDefinition task : tasks) {
                ps.setLong(1, id);
                ps.setString(2, task.id());
                ps.setString(3, task.type().name());
                ps.setString(4, task.name());
                ps.setString(5, String.join(",", task.targets()));
                ps.setInt(6, task.target());
                ps.addBatch();
              }
              ps.executeBatch();
            }
            try (PreparedStatement ps =
                c.prepareStatement(
                    "UPDATE si_codes SET available_bundles=available_bundles-1 WHERE code=? AND"
                        + " available_bundles>0")) {
              ps.setString(1, code.code());
              if (ps.executeUpdate() != 1) return rollback(c, "奖励库存不足");
            }
            try (PreparedStatement ps =
                c.prepareStatement(
                    "UPDATE si_relations SET reserved=TRUE,reserved_money=?,reserved_items=? WHERE"
                        + " id=?")) {
              ps.setBigDecimal(1, code.money());
              ps.setBytes(2, code.items());
              ps.setLong(3, id);
              ps.executeUpdate();
            }
            c.commit();
            return new BindResult(true, "邀请码绑定成功，已随机生成 " + tasks.size() + " 个成长任务", id);
          } catch (SQLException e) {
            try {
              c.rollback();
            } catch (SQLException ignored) {
            }
            if (isConstraint(e)) return new BindResult(false, "你已经绑定过个人邀请码", 0);
            throw new CompletionException(e);
          } finally {
            try {
              c.setAutoCommit(true);
            } catch (SQLException ignored) {
            }
          }
        });
  }

  private BindResult rollback(Connection c, String message) throws SQLException {
    c.rollback();
    return new BindResult(false, message, 0);
  }

  public CompletableFuture<ProgressResult> progress(
      UUID player, TaskType eventType, String key, int amount, TaskRegistry registry) {
    return async(
        c -> {
          try {
            c.setAutoCommit(false);
            long relationId = 0;
            boolean changed = false;
            String select =
                "SELECT"
                    + " r.id,t.definition_id,t.task_type,t.task_name,t.task_targets,t.progress,t.target"
                    + " FROM si_relations r JOIN si_tasks t ON t.relation_id=r.id WHERE"
                    + " r.invitee_uuid=? AND r.completed=FALSE"
                    + (dialect == Dialect.MYSQL ? " FOR UPDATE" : "");
            try (PreparedStatement ps = c.prepareStatement(select)) {
              ps.setString(1, player.toString());
              try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                  relationId = rs.getLong("id");
                  TaskDefinition def = snapshot(rs);
                  if (def.type() != eventType
                      || !def.matches(key)
                      || rs.getInt("progress") >= rs.getInt("target")) continue;
                  int next =
                      eventType == TaskType.LEVEL
                          ? Math.max(rs.getInt("progress"), amount)
                          : Math.min(rs.getInt("target"), rs.getInt("progress") + amount);
                  try (PreparedStatement up =
                      c.prepareStatement(
                          "UPDATE si_tasks SET progress=? WHERE relation_id=? AND"
                              + " definition_id=?")) {
                    up.setInt(1, next);
                    up.setLong(2, relationId);
                    up.setString(3, def.id());
                    up.executeUpdate();
                    changed = true;
                  }
                }
              }
            }
            boolean complete = false;
            if (changed && relationId != 0) {
              try (PreparedStatement ps =
                  c.prepareStatement(
                      "SELECT COUNT(*) FROM si_tasks WHERE relation_id=? AND progress<target")) {
                ps.setLong(1, relationId);
                try (ResultSet rs = ps.executeQuery()) {
                  rs.next();
                  complete = rs.getInt(1) == 0;
                }
              }
              if (complete)
                try (PreparedStatement ps =
                    c.prepareStatement(
                        "UPDATE si_relations SET completed=TRUE,completed_at=CURRENT_TIMESTAMP"
                            + " WHERE id=? AND completed=FALSE")) {
                  ps.setLong(1, relationId);
                  complete = ps.executeUpdate() == 1;
                }
            }
            c.commit();
            return new ProgressResult(changed, complete, relationId);
          } catch (SQLException e) {
            try {
              c.rollback();
            } catch (SQLException ignored) {
            }
            throw new CompletionException(e);
          } finally {
            try {
              c.setAutoCommit(true);
            } catch (SQLException ignored) {
            }
          }
        });
  }

  public CompletableFuture<Dashboard> dashboard(UUID player, TaskRegistry registry) {
    return async(
        c -> {
          try {
            String own = null, inviter = null;
            int invited = 0, valid = 0, rank = 0;
            List<TaskProgress> tasks = new ArrayList<>();
            List<String> inviteeNames = new ArrayList<>();
            long rel = 0;
            try (PreparedStatement ps =
                c.prepareStatement(
                    "SELECT code FROM si_codes WHERE creator_uuid=? AND active=TRUE")) {
              ps.setString(1, player.toString());
              try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) own = rs.getString(1);
              }
            }
            try (PreparedStatement ps =
                c.prepareStatement(
                    "SELECT id,inviter_name FROM si_relations WHERE invitee_uuid=?")) {
              ps.setString(1, player.toString());
              try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                  rel = rs.getLong(1);
                  inviter = rs.getString(2);
                }
              }
            }
            if (rel != 0)
              try (PreparedStatement ps =
                  c.prepareStatement(
                      "SELECT definition_id,task_type,task_name,task_targets,progress,target FROM"
                          + " si_tasks WHERE relation_id=?")) {
                ps.setLong(1, rel);
                try (ResultSet rs = ps.executeQuery()) {
                  while (rs.next()) {
                    TaskDefinition d = snapshot(rs);
                    tasks.add(
                        new TaskProgress(
                            rel,
                            d.id(),
                            rs.getInt("progress"),
                            rs.getInt("target"),
                            d.name(),
                            d.type(),
                            d.targets()));
                  }
                }
              }
            try (PreparedStatement ps =
                c.prepareStatement(
                    "SELECT COUNT(*),COALESCE(SUM(completed),0) FROM si_relations WHERE"
                        + " inviter_uuid=?")) {
              ps.setString(1, player.toString());
              try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                invited = rs.getInt(1);
                valid = rs.getInt(2);
              }
            }
            try (PreparedStatement ps =
                c.prepareStatement(
                    "SELECT invitee_name FROM si_relations WHERE inviter_uuid=? ORDER BY bound_at"
                        + " DESC LIMIT 20")) {
              ps.setString(1, player.toString());
              try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) inviteeNames.add(rs.getString(1));
              }
            }
            if (valid > 0)
              try (PreparedStatement ps =
                  c.prepareStatement(
                      "SELECT 1+COUNT(*) FROM (SELECT inviter_uuid,COUNT(*) n FROM si_relations"
                          + " WHERE completed=TRUE GROUP BY inviter_uuid) x WHERE n>?")) {
                ps.setInt(1, valid);
                try (ResultSet rs = ps.executeQuery()) {
                  rs.next();
                  rank = rs.getInt(1);
                }
              }
            return new Dashboard(
                own, inviter, tasks, List.copyOf(inviteeNames), invited, valid, rank);
          } catch (SQLException e) {
            throw new CompletionException(e);
          }
        });
  }

  public CompletableFuture<List<RelationView>> records(UUID inviter, int limit) {
    return async(
        c -> {
          List<RelationView> out = new ArrayList<>();
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT * FROM si_relations WHERE inviter_uuid=? ORDER BY bound_at DESC LIMIT"
                      + " ?")) {
            ps.setString(1, inviter.toString());
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) out.add(relation(rs));
            }
          } catch (SQLException e) {
            throw new CompletionException(e);
          }
          return out;
        });
  }

  public CompletableFuture<List<LeaderboardEntry>> top(int limit) {
    return async(
        c -> {
          List<LeaderboardEntry> out = new ArrayList<>();
          int rank = 0, position = 0, previous = -1;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT inviter_name,COUNT(*) n,MIN(bound_at) first_bound FROM si_relations WHERE"
                      + " completed=TRUE GROUP BY inviter_uuid,inviter_name ORDER BY n"
                      + " DESC,first_bound ASC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                position++;
                int count = rs.getInt(2);
                if (count != previous) rank = position;
                previous = count;
                out.add(new LeaderboardEntry(rank, rs.getString(1), count));
              }
            }
          } catch (SQLException e) {
            throw new CompletionException(e);
          }
          return out;
        });
  }

  private RelationView relation(ResultSet rs) throws SQLException {
    return new RelationView(
        rs.getLong("id"),
        rs.getString("code"),
        UUID.fromString(rs.getString("inviter_uuid")),
        rs.getString("inviter_name"),
        UUID.fromString(rs.getString("invitee_uuid")),
        rs.getString("invitee_name"),
        rs.getBoolean("completed"),
        rs.getBoolean("reward_claimed"),
        rs.getTimestamp("bound_at").toInstant());
  }

  private TaskDefinition snapshot(ResultSet rs) throws SQLException {
    String values = rs.getString("task_targets");
    return new TaskDefinition(
        rs.getString("definition_id"),
        TaskType.valueOf(rs.getString("task_type")),
        rs.getInt("target"),
        values == null || values.isBlank() ? List.of() : Arrays.asList(values.split(",")),
        rs.getString("task_name"));
  }

  private List<String> splitCommands(String value) {
    return value == null || value.isBlank()
        ? List.of()
        : Arrays.stream(value.split("\\R")).filter(s -> !s.isBlank()).toList();
  }

  private boolean isConstraint(SQLException e) {
    String state = e.getSQLState();
    String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
    return e instanceof SQLIntegrityConstraintViolationException
        || (state != null && state.startsWith("23"))
        || message.contains("constraint failed")
        || message.contains("unique constraint");
  }

  private String rootMessage(Throwable error) {
    Throwable t = error;
    while (t.getCause() != null) t = t.getCause();
    return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
  }

  private void closeSource() {
    HikariDataSource current = source;
    source = null;
    if (current != null && !current.isClosed()) current.close();
  }

  @Override
  public void close() {
    stopping = true;
    executor.shutdown();
    try {
      if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
        logger.severe("数据库收尾超时，未完成操作将保留供恢复");
        executor.shutdownNow();
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
    }
    closeSource();
    initialized = false;
  }

  public record Delivery(
      String id,
      UUID player,
      String playerName,
      String code,
      BigDecimal money,
      byte[] items,
      List<String> commands,
      String status) {}

  private void insertDelivery(
      Connection c,
      String id,
      UUID player,
      String name,
      String code,
      BigDecimal money,
      byte[] items,
      List<String> commands)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO"
                + " si_deliveries(id,player_uuid,player_name,code,money,items,commands,status,created_at)"
                + " VALUES(?,?,?,?,?,?,?,'PENDING',?)")) {
      ps.setString(1, id);
      ps.setString(2, player.toString());
      ps.setString(3, name);
      ps.setString(4, code);
      ps.setBigDecimal(5, money);
      ps.setBytes(6, items);
      ps.setString(7, String.join("\n", commands));
      ps.setLong(8, System.currentTimeMillis());
      ps.executeUpdate();
    }
  }

  public CompletableFuture<List<Delivery>> deliveries(UUID player) {
    return async(
        c -> {
          List<Delivery> result = new ArrayList<>();
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT * FROM si_deliveries WHERE player_uuid=? AND status='PENDING' ORDER BY"
                      + " created_at LIMIT 20")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next())
                result.add(
                    new Delivery(
                        rs.getString("id"),
                        player,
                        rs.getString("player_name"),
                        rs.getString("code"),
                        rs.getBigDecimal("money"),
                        rs.getBytes("items"),
                        splitCommands(rs.getString("commands")),
                        rs.getString("status")));
            }
          } catch (SQLException e) {
            throw new CompletionException(e);
          }
          return result;
        });
  }

  public CompletableFuture<Boolean> deliveryStatus(
      String id, String from, String to, String detail) {
    return async(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE si_deliveries SET status=?,detail=? WHERE id=? AND status=?")) {
            ps.setString(1, to);
            ps.setString(
                2, detail == null ? "" : detail.substring(0, Math.min(500, detail.length())));
            ps.setString(3, id);
            ps.setString(4, from);
            return ps.executeUpdate() == 1;
          } catch (SQLException e) {
            throw new CompletionException(e);
          }
        });
  }

  public CompletableFuture<List<String>> reviewDeliveries() {
    return async(
        c -> {
          List<String> out = new ArrayList<>();
          try (Statement st = c.createStatement();
              ResultSet rs =
                  st.executeQuery(
                      "SELECT id,player_name,detail FROM si_deliveries WHERE status='REVIEW' ORDER"
                          + " BY created_at LIMIT 30")) {
            while (rs.next())
              out.add(rs.getString(1) + " | " + rs.getString(2) + " | " + rs.getString(3));
          } catch (SQLException e) {
            throw new CompletionException(e);
          }
          return out;
        });
  }

  public CompletableFuture<Boolean> publish(String raw, boolean active) {
    return async(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE si_codes SET active=? WHERE code_key=UPPER(?) AND code_type='OFFICIAL'"
                      + " AND (reward_money>0 OR reward_items IS NOT NULL OR (reward_commands IS"
                      + " NOT NULL AND reward_commands<>''))")) {
            ps.setBoolean(1, active);
            ps.setString(2, raw);
            return ps.executeUpdate() == 1;
          } catch (SQLException e) {
            throw new CompletionException(e);
          }
        });
  }

  public CompletableFuture<Boolean> officialLimits(String raw, int maximum, long expires) {
    return async(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE si_codes SET max_uses=?,expires_at=? WHERE code_key=UPPER(?) AND"
                      + " code_type='OFFICIAL'")) {
            ps.setInt(1, maximum);
            ps.setLong(2, expires);
            ps.setString(3, raw);
            return ps.executeUpdate() == 1;
          } catch (SQLException e) {
            throw new CompletionException(e);
          }
        });
  }

  public CompletableFuture<Boolean> queuePersonal(long relationId, List<String> inviterCommands) {
    return async(
        c -> {
          try {
            c.setAutoCommit(false);
            String code, name;
            UUID player;
            BigDecimal reservedMoney;
            byte[] reservedItems;
            try (PreparedStatement ps =
                c.prepareStatement(
                    "SELECT"
                        + " code,invitee_uuid,invitee_name,completed,reward_claimed,reserved,reserved_money,reserved_items"
                        + " FROM si_relations WHERE id=?"
                        + (dialect == Dialect.MYSQL ? " FOR UPDATE" : ""))) {
              ps.setLong(1, relationId);
              try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()
                    || !rs.getBoolean("completed")
                    || rs.getBoolean("reward_claimed")
                    || !rs.getBoolean("reserved")) {
                  c.rollback();
                  return false;
                }
                code = rs.getString("code");
                name = rs.getString("invitee_name");
                player = UUID.fromString(rs.getString("invitee_uuid"));
                reservedMoney = rs.getBigDecimal("reserved_money");
                reservedItems = rs.getBytes("reserved_items");
              }
            }
            InviteCode reward = selectCode(c, code, false);
            insertDelivery(
                c,
                "personal-" + relationId,
                player,
                name,
                code,
                reservedMoney,
                reservedItems,
                List.of());
            if (!inviterCommands.isEmpty())
              insertDelivery(
                  c,
                  "inviter-" + relationId,
                  reward.creatorUuid(),
                  reward.creatorName(),
                  code,
                  BigDecimal.ZERO,
                  null,
                  inviterCommands);
            try (PreparedStatement ps =
                c.prepareStatement("UPDATE si_relations SET reward_claimed=TRUE WHERE id=?")) {
              ps.setLong(1, relationId);
              ps.executeUpdate();
            }
            c.commit();
            return true;
          } catch (SQLException e) {
            try {
              c.rollback();
            } catch (SQLException ignored) {
            }
            throw new CompletionException(e);
          } finally {
            try {
              c.setAutoCommit(true);
            } catch (SQLException ignored) {
            }
          }
        });
  }

  public CompletableFuture<String> queueOfficial(String raw, UUID player, String name, String ip) {
    return async(
        c -> {
          try {
            c.setAutoCommit(false);
            String canonical;
            int max;
            long expires;
            try (PreparedStatement ps =
                c.prepareStatement(
                    "SELECT code,max_uses,expires_at FROM si_codes WHERE code_key=UPPER(?) AND"
                        + " active=TRUE AND code_type='OFFICIAL'"
                        + (dialect == Dialect.MYSQL ? " FOR UPDATE" : ""))) {
              ps.setString(1, raw);
              try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                  c.rollback();
                  return "官方码未发布或已停用";
                }
                canonical = rs.getString(1);
                max = rs.getInt(2);
                expires = rs.getLong(3);
              }
            }
            if (expires > 0 && System.currentTimeMillis() >= expires) {
              c.rollback();
              return "官方码已过期";
            }
            try (PreparedStatement ps =
                c.prepareStatement(
                    "SELECT COUNT(*) FROM si_official_claims WHERE code=? AND player_uuid=?")) {
              ps.setString(1, canonical);
              ps.setString(2, player.toString());
              try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                if (rs.getInt(1) > 0) {
                  c.rollback();
                  return "你已领取过此官方码";
                }
              }
            }
            if (max > 0)
              try (PreparedStatement ps =
                  c.prepareStatement("SELECT COUNT(*) FROM si_official_claims WHERE code=?")) {
                ps.setString(1, canonical);
                try (ResultSet rs = ps.executeQuery()) {
                  rs.next();
                  if (rs.getInt(1) >= max) {
                    c.rollback();
                    return "官方码名额已用完";
                  }
                }
              }
            InviteCode reward = selectCode(c, canonical, false);
            try (PreparedStatement ps =
                c.prepareStatement(
                    "INSERT INTO si_official_claims(code,player_uuid,player_name,player_ip)"
                        + " VALUES(?,?,?,?)")) {
              ps.setString(1, canonical);
              ps.setString(2, player.toString());
              ps.setString(3, name);
              ps.setString(4, ip);
              ps.executeUpdate();
            }
            insertDelivery(
                c,
                "official-" + UUID.randomUUID(),
                player,
                name,
                canonical,
                reward.money(),
                reward.items(),
                reward.commands());
            c.commit();
            return "OK";
          } catch (SQLException e) {
            try {
              c.rollback();
            } catch (SQLException ignored) {
            }
            throw new CompletionException(e);
          } finally {
            try {
              c.setAutoCommit(true);
            } catch (SQLException ignored) {
            }
          }
        });
  }

  public record Funding(
      String id,
      UUID player,
      String name,
      String ip,
      String code,
      BigDecimal money,
      byte[] items,
      boolean create) {}

  public CompletableFuture<InviteCode> ownCode(UUID owner) {
    return async(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement("SELECT code FROM si_codes WHERE creator_uuid=?")) {
            ps.setString(1, owner.toString());
            try (ResultSet rs = ps.executeQuery()) {
              return rs.next() ? selectCode(c, rs.getString(1), false) : null;
            }
          } catch (SQLException e) {
            throw new CompletionException(e);
          }
        });
  }

  public CompletableFuture<Integer> stock(String code) {
    return async(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT available_bundles FROM si_codes WHERE code_key=UPPER(?)")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
              return rs.next() ? rs.getInt(1) : 0;
            }
          } catch (SQLException e) {
            throw new CompletionException(e);
          }
        });
  }

  /** Idempotency key and funding mutation/refund are committed together. */
  public CompletableFuture<String> commitFunding(
      Funding f, boolean requestedRefund, boolean refundMoney) {
    return async(
        c -> {
          try {
            c.setAutoCommit(false);
            try (PreparedStatement ps =
                c.prepareStatement("SELECT result FROM si_funding_ops WHERE id=?")) {
              ps.setString(1, f.id());
              try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                  String result = rs.getString(1);
                  c.rollback();
                  return result;
                }
              }
            }
            boolean refund = requestedRefund;
            String result = f.create() ? "CREATED" : "FUNDED";
            InviteCode existing = null;
            if (!refund) {
              if (f.create()) {
                try (PreparedStatement ps =
                    c.prepareStatement(
                        "SELECT code FROM si_codes WHERE creator_uuid=? OR code_key=UPPER(?)")) {
                  ps.setString(1, f.player().toString());
                  ps.setString(2, f.code());
                  try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) refund = true;
                  }
                }
                if (!refund)
                  try (PreparedStatement ps =
                      c.prepareStatement(
                          "INSERT INTO"
                              + " si_codes(code,code_key,code_type,creator_uuid,creator_name,creator_ip,reward_money,reward_items,available_bundles)"
                              + " VALUES(?,UPPER(?),'PERSONAL',?,?,?,?,?,1)")) {
                    ps.setString(1, f.code());
                    ps.setString(2, f.code());
                    ps.setString(3, f.player().toString());
                    ps.setString(4, f.name());
                    ps.setString(5, f.ip());
                    ps.setBigDecimal(6, f.money());
                    ps.setBytes(7, f.items());
                    ps.executeUpdate();
                  }
              } else {
                existing = selectCode(c, f.code(), false);
                if (existing == null
                    || existing.type() != CodeType.PERSONAL
                    || !f.player().equals(existing.creatorUuid())) refund = true;
                if (!refund) {
                  // Legacy unfinished relations are funded before opening another invitation seat.
                  long legacy = 0;
                  try (PreparedStatement ps =
                      c.prepareStatement(
                          "SELECT id FROM si_relations WHERE code=? AND reserved=FALSE AND"
                              + " reward_claimed=FALSE ORDER BY id LIMIT 1"
                              + (dialect == Dialect.MYSQL ? " FOR UPDATE" : ""))) {
                    ps.setString(1, existing.code());
                    try (ResultSet rs = ps.executeQuery()) {
                      if (rs.next()) legacy = rs.getLong(1);
                    }
                  }
                  if (legacy > 0)
                    try (PreparedStatement ps =
                        c.prepareStatement(
                            "UPDATE si_relations SET"
                                + " reserved=TRUE,reserved_money=?,reserved_items=? WHERE id=?")) {
                      ps.setBigDecimal(1, f.money());
                      ps.setBytes(2, f.items());
                      ps.setLong(3, legacy);
                      ps.executeUpdate();
                      result = "LEGACY_FUNDED";
                    }
                  else
                    try (PreparedStatement ps =
                        c.prepareStatement(
                            "UPDATE si_codes SET available_bundles=available_bundles+1 WHERE"
                                + " code=?")) {
                      ps.setString(1, existing.code());
                      ps.executeUpdate();
                    }
                }
              }
            }
            if (refund) {
              insertDelivery(
                  c,
                  "refund-" + f.id(),
                  f.player(),
                  f.name(),
                  f.code(),
                  refundMoney ? f.money() : BigDecimal.ZERO,
                  f.items(),
                  List.of());
              result = "REFUND";
            }
            try (PreparedStatement ps =
                c.prepareStatement("INSERT INTO si_funding_ops(id,result,code) VALUES(?,?,?)")) {
              ps.setString(1, f.id());
              ps.setString(2, result);
              ps.setString(3, f.code());
              ps.executeUpdate();
            }
            c.commit();
            return result;
          } catch (SQLException e) {
            try {
              c.rollback();
            } catch (SQLException ignored) {
            }
            throw new CompletionException(e);
          } finally {
            try {
              c.setAutoCommit(true);
            } catch (SQLException ignored) {
            }
          }
        });
  }

  /** Only unused, unreserved bundles may be returned. Bound-player obligations are untouched. */
  public CompletableFuture<Boolean> withdrawUnused(UUID owner, String currentName) {
    return async(
        c -> {
          try {
            c.setAutoCommit(false);
            String code;
            try (PreparedStatement ps =
                c.prepareStatement(
                    "SELECT code,available_bundles FROM si_codes WHERE creator_uuid=? AND"
                        + " code_type='PERSONAL'"
                        + (dialect == Dialect.MYSQL ? " FOR UPDATE" : ""))) {
              ps.setString(1, owner.toString());
              try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || rs.getInt(2) < 1) {
                  c.rollback();
                  return false;
                }
                code = rs.getString(1);
              }
            }
            InviteCode reward = selectCode(c, code, false);
            try (PreparedStatement ps =
                c.prepareStatement(
                    "UPDATE si_codes SET available_bundles=available_bundles-1 WHERE code=? AND"
                        + " available_bundles>0")) {
              ps.setString(1, code);
              if (ps.executeUpdate() != 1) {
                c.rollback();
                return false;
              }
            }
            insertDelivery(
                c,
                "withdraw-" + UUID.randomUUID(),
                owner,
                currentName,
                code,
                reward.money(),
                reward.items(),
                List.of());
            c.commit();
            return true;
          } catch (SQLException e) {
            try {
              c.rollback();
            } catch (SQLException ignored) {
            }
            throw new CompletionException(e);
          } finally {
            try {
              c.setAutoCommit(true);
            } catch (SQLException ignored) {
            }
          }
        });
  }
}
