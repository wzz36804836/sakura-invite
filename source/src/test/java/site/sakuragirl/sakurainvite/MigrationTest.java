package site.sakuragirl.sakurainvite;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import site.sakuragirl.sakurainvite.db.Database;

class MigrationTest {
  @TempDir Path root;
  Database db;

  private void oldSchema() throws Exception {
    String sql =
        new String(
            getClass().getResourceAsStream("/legacy-schema.sql").readAllBytes(),
            java.nio.charset.StandardCharsets.UTF_8);
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("test.db"));
        Statement s = c.createStatement()) {
      for (String part : sql.split(";")) if (!part.isBlank()) s.executeUpdate(part);
    }
    YamlConfiguration cfg = new YamlConfiguration();
    cfg.set("storage", "sqlite");
    cfg.set("sqlite.file", "test.db");
    db =
        new Database(() -> cfg, root.toFile(), java.util.logging.Logger.getLogger("MigrationTest"));
  }

  @AfterEach
  void close() {
    if (db != null) db.close();
  }

  @Test
  void legacyPendingRewardRequiresFundingAndKeepsOriginalRelation() throws Exception {
    oldSchema();
    UUID owner = UUID.randomUUID(), invitee = UUID.randomUUID();
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("test.db"));
        Statement s = c.createStatement()) {
      s.executeUpdate(
          "INSERT INTO si_codes(code,code_type,creator_uuid,creator_name,reward_money)"
              + " VALUES('OLD','PERSONAL','"
              + owner
              + "','Old',25)");
      s.executeUpdate(
          "INSERT INTO"
              + " si_relations(id,code,inviter_uuid,inviter_name,invitee_uuid,invitee_name,completed)"
              + " VALUES(1,'OLD','"
              + owner
              + "','Old','"
              + invitee
              + "','New',TRUE)");
    }
    db.connect().join();
    assertEquals(0, db.stock("OLD").join());
    assertFalse(db.queuePersonal(1, List.of()).join());
    var f =
        new Database.Funding(
            UUID.randomUUID().toString(),
            owner,
            "Old",
            "10.0.0.1",
            "OLD",
            new BigDecimal("25.00"),
            new byte[0],
            false);
    assertEquals("LEGACY_FUNDED", db.commitFunding(f, false, true).join());
    assertEquals(0, db.stock("OLD").join());
    assertTrue(db.queuePersonal(1, List.of()).join());
    assertEquals(1, db.deliveries(invitee).join().size());
  }

  @Test
  void legacyCaseCollisionFailsClosed() throws Exception {
    oldSchema();
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("test.db"));
        Statement s = c.createStatement()) {
      s.executeUpdate(
          "INSERT INTO si_codes(code,code_type) VALUES('EVENT','OFFICIAL'),('event','OFFICIAL')");
    }
    assertThrows(Exception.class, () -> db.connect().join());
    assertFalse(db.ready());
  }
}
