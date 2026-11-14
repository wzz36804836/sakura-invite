package site.sakuragirl.sakurainvite;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import site.sakuragirl.sakurainvite.db.Database;

class DatabaseTest {
  @TempDir Path root;
  Database db;

  @BeforeEach
  void open() {
    YamlConfiguration cfg = new YamlConfiguration();
    cfg.set("storage", "sqlite");
    cfg.set("sqlite.file", "test.db");
    db = new Database(() -> cfg, root.toFile(), Logger.getLogger("InviteTest"));
    assertFalse(db.ready());
    db.connect().join();
    assertTrue(db.ready());
  }

  @AfterEach
  void close() {
    if (db != null) db.close();
  }

  @Test
  void draftsCannotBeRedeemedAndKeysAreUnique() {
    assertTrue(db.createOfficial("SPRING2026").join());
    assertNull(db.findCode("spring2026").join());
    assertNotNull(db.findAnyCode("spring2026").join());
    assertFalse(db.createOfficial("spring2026").join());
    assertFalse(db.publish("SPRING2026", true).join());
  }

  @Test
  void publishedCodeUsesCaseInsensitiveLifecycle() {
    db.createOfficial("EVENT").join();
    assertTrue(
        db.updateOfficial(
                "event", new java.math.BigDecimal("10.00"), null, true, java.util.List.of())
            .join());
    assertTrue(db.publish("event", true).join());
    assertNotNull(db.findCode("EvEnT").join());
    assertTrue(db.deleteCode("eVeNt").join());
    assertNull(db.findCode("EVENT").join());
  }

  private site.sakuragirl.sakurainvite.db.Database.Funding funding(
      String id, java.util.UUID owner, String code, boolean create) {
    return new Database.Funding(
        id,
        owner,
        "Inviter",
        "10.0.0.1",
        code,
        new java.math.BigDecimal("25.00"),
        new byte[0],
        create);
  }

  @Test
  void personalFundingIsIdempotentAndBindingReservesExactlyOneBundle() {
    var owner = java.util.UUID.randomUUID();
    var one = java.util.UUID.randomUUID();
    var two = java.util.UUID.randomUUID();
    var f = funding(java.util.UUID.randomUUID().toString(), owner, "PERSONAL", true);
    assertEquals("CREATED", db.commitFunding(f, false, true).join());
    assertEquals("CREATED", db.commitFunding(f, false, true).join());
    assertEquals(1, db.stock("personal").join());
    var code = db.findCode("personal").join();
    var task =
        new site.sakuragirl.sakurainvite.model.Models.TaskDefinition(
            "online",
            site.sakuragirl.sakurainvite.model.Models.TaskType.ONLINE_MINUTES,
            1,
            java.util.List.of(),
            "Online");
    var bind =
        db.bindPersonal(code, one, "One", "10.0.0.2", java.util.List.of(task), 1, true).join();
    assertTrue(bind.success());
    assertEquals(0, db.stock("PERSONAL").join());
    assertFalse(
        db.bindPersonal(code, two, "Two", "10.0.0.3", java.util.List.of(task), 1, true)
            .join()
            .success());
    assertTrue(db.progress(one, task.type(), "", 1, null).join().justCompleted());
    assertTrue(db.queuePersonal(bind.relationId(), java.util.List.of("say %player_name%")).join());
    assertFalse(db.queuePersonal(bind.relationId(), java.util.List.of()).join());
    assertEquals(1, db.deliveries(one).join().size());
    assertEquals(
        new java.math.BigDecimal("25"),
        db.deliveries(one).join().getFirst().money().stripTrailingZeros());
    assertEquals(1, db.deliveries(owner).join().size());
    assertEquals(
        "FUNDED",
        db.commitFunding(
                funding(java.util.UUID.randomUUID().toString(), owner, "PERSONAL", false),
                false,
                true)
            .join());
    assertEquals(1, db.stock("PERSONAL").join());
    assertFalse(
        db.bindPersonal(code, two, "Two", "10.0.0.2", java.util.List.of(task), 1, true)
            .join()
            .success());
    assertEquals(1, db.stock("PERSONAL").join());
  }

  @Test
  void officialLimitsAndOutboxSurviveRestartWithoutBlindRepeat() {
    var player = java.util.UUID.randomUUID();
    db.createOfficial("EVENT").join();
    db.updateOfficial("EVENT", new java.math.BigDecimal("2.00"), null, true, java.util.List.of())
        .join();
    db.publish("EVENT", true).join();
    db.officialLimits("EVENT", 1, 0).join();
    assertEquals("OK", db.queueOfficial("event", player, "One", "10.0.0.2").join());
    assertNotEquals("OK", db.queueOfficial("EVENT", player, "One", "10.0.0.2").join());
    assertNotEquals(
        "OK", db.queueOfficial("EVENT", java.util.UUID.randomUUID(), "Two", "10.0.0.3").join());
    var job = db.deliveries(player).join().getFirst();
    assertTrue(db.deliveryStatus(job.id(), "PENDING", "PROCESSING", "test").join());
    assertFalse(db.deliveryStatus(job.id(), "PENDING", "PROCESSING", "duplicate").join());
    db.close();
    open();
    assertTrue(db.deliveries(player).join().isEmpty());
    assertTrue(db.reviewDeliveries().join().stream().anyMatch(row -> row.contains(job.id())));
    assertTrue(db.deliveryStatus(job.id(), "REVIEW", "DELIVERED", "confirmed").join());
    assertFalse(db.deliveryStatus(job.id(), "REVIEW", "PENDING", "duplicate retry").join());
  }

  @Test
  void refundOutboxIsExactlyOnePerFundingOperation() {
    var owner = java.util.UUID.randomUUID();
    var f = funding(java.util.UUID.randomUUID().toString(), owner, "REFUND", true);
    assertEquals("REFUND", db.commitFunding(f, true, true).join());
    assertEquals("REFUND", db.commitFunding(f, true, true).join());
    assertEquals(1, db.deliveries(owner).join().size());
    assertEquals(0, db.stock("REFUND").join());
  }

  @Test
  void officialExpirationIsCheckedWithinClaimTransaction() {
    db.createOfficial("EXPIRED").join();
    db.updateOfficial("EXPIRED", java.math.BigDecimal.ONE, null, true, java.util.List.of()).join();
    db.publish("EXPIRED", true).join();
    db.officialLimits("EXPIRED", 0, System.currentTimeMillis() - 1000).join();
    assertNotEquals(
        "OK", db.queueOfficial("expired", java.util.UUID.randomUUID(), "One", "10.0.0.2").join());
  }

  @Test
  void concurrentBindingCannotOversellOneBundle() {
    var owner = java.util.UUID.randomUUID();
    db.commitFunding(
            funding(java.util.UUID.randomUUID().toString(), owner, "SINGLE", true), false, true)
        .join();
    var code = db.findCode("SINGLE").join();
    var task =
        new site.sakuragirl.sakurainvite.model.Models.TaskDefinition(
            "t",
            site.sakuragirl.sakurainvite.model.Models.TaskType.FISH,
            1,
            java.util.List.of(),
            "Fish");
    var work =
        new java.util.ArrayList<java.util.concurrent.CompletableFuture<Database.BindResult>>();
    for (int i = 0; i < 20; i++)
      work.add(
          db.bindPersonal(
              code,
              java.util.UUID.randomUUID(),
              "Player" + i,
              "10.0.1." + i,
              java.util.List.of(task),
              0,
              false));
    assertEquals(
        1,
        work.stream()
            .map(java.util.concurrent.CompletableFuture::join)
            .filter(Database.BindResult::success)
            .count());
    assertEquals(0, db.stock("SINGLE").join());
  }

  @Test
  void pendingJobRemainsPendingAcrossRestart() {
    var owner = java.util.UUID.randomUUID();
    db.commitFunding(
            funding(java.util.UUID.randomUUID().toString(), owner, "REFUND", true), true, true)
        .join();
    String id = db.deliveries(owner).join().getFirst().id();
    db.close();
    open();
    assertEquals(id, db.deliveries(owner).join().getFirst().id());
    assertTrue(db.reviewDeliveries().join().isEmpty());
  }

  @Test
  void failedNewPersonalCodeReturnsFundsWithoutChangingExistingStock() {
    var owner = java.util.UUID.randomUUID();
    db.commitFunding(
            funding(java.util.UUID.randomUUID().toString(), owner, "ORIGINAL", true), false, true)
        .join();
    assertEquals(
        "REFUND",
        db.commitFunding(
                funding(java.util.UUID.randomUUID().toString(), owner, "SECOND", true), false, true)
            .join());
    assertEquals(1, db.stock("ORIGINAL").join());
    assertNull(db.findAnyCode("SECOND").join());
    assertEquals(1, db.deliveries(owner).join().size());
  }

  @Test
  void selfAndDuplicateBindingsDoNotConsumeExtraStock() {
    var owner = java.util.UUID.randomUUID();
    var player = java.util.UUID.randomUUID();
    db.commitFunding(
            funding(java.util.UUID.randomUUID().toString(), owner, "SAFE", true), false, true)
        .join();
    var code = db.findCode("SAFE").join();
    var task =
        new site.sakuragirl.sakurainvite.model.Models.TaskDefinition(
            "t",
            site.sakuragirl.sakurainvite.model.Models.TaskType.FISH,
            1,
            java.util.List.of(),
            "Fish");
    assertFalse(
        db.bindPersonal(code, owner, "Self", "10.0.0.1", java.util.List.of(task), 1, true)
            .join()
            .success());
    assertEquals(1, db.stock("SAFE").join());
    assertTrue(
        db.bindPersonal(code, player, "One", "10.0.0.2", java.util.List.of(task), 1, true)
            .join()
            .success());
    db.commitFunding(
            funding(java.util.UUID.randomUUID().toString(), owner, "SAFE", false), false, true)
        .join();
    assertFalse(
        db.bindPersonal(code, player, "One", "10.0.0.2", java.util.List.of(task), 1, true)
            .join()
            .success());
    assertEquals(1, db.stock("SAFE").join());
  }

  @Test
  void unusedRewardWithdrawalCannotTakeReservedRewards() {
    var owner = java.util.UUID.randomUUID();
    var player = java.util.UUID.randomUUID();
    db.commitFunding(
            funding(java.util.UUID.randomUUID().toString(), owner, "RETURN", true), false, true)
        .join();
    assertTrue(db.withdrawUnused(owner, "Inviter").join());
    assertFalse(db.withdrawUnused(owner, "Inviter").join());
    assertEquals(1, db.deliveries(owner).join().size());
    db.commitFunding(
            funding(java.util.UUID.randomUUID().toString(), owner, "RETURN", false), false, true)
        .join();
    var task =
        new site.sakuragirl.sakurainvite.model.Models.TaskDefinition(
            "t",
            site.sakuragirl.sakurainvite.model.Models.TaskType.FISH,
            1,
            java.util.List.of(),
            "Fish");
    assertTrue(
        db.bindPersonal(
                db.findCode("RETURN").join(),
                player,
                "One",
                "10.0.0.2",
                java.util.List.of(task),
                0,
                false)
            .join()
            .success());
    assertFalse(db.withdrawUnused(owner, "Inviter").join());
  }

  @Test
  void shutdownRejectsFurtherWork() {
    db.close();
    assertFalse(db.ready());
    assertThrows(Exception.class, () -> db.createOfficial("AFTER").join());
  }
}
