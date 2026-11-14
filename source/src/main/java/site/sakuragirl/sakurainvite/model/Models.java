package site.sakuragirl.sakurainvite.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class Models {
  private Models() {}

  public enum CodeType {
    PERSONAL,
    OFFICIAL
  }

  public enum TaskType {
    ONLINE_MINUTES,
    LEVEL,
    KILL,
    BLOCK_BREAK,
    PLANT,
    FISH
  }

  public record InviteCode(
      String code,
      CodeType type,
      UUID creatorUuid,
      String creatorName,
      BigDecimal money,
      byte[] items,
      List<String> commands,
      boolean active,
      String creatorIp) {}

  public record OfficialCodeView(
      String code,
      BigDecimal money,
      byte[] items,
      List<String> commands,
      int useCount,
      boolean active) {}

  public record TaskDefinition(
      String id, TaskType type, int target, List<String> targets, String name) {
    public boolean matches(String value) {
      return targets == null
          || targets.isEmpty()
          || targets.stream().anyMatch(s -> s.equalsIgnoreCase(value));
    }
  }

  public record TaskProgress(
      long relationId,
      String definitionId,
      int progress,
      int target,
      String name,
      TaskType type,
      List<String> targets) {
    public boolean complete() {
      return progress >= target;
    }
  }

  public record RelationView(
      long id,
      String code,
      UUID inviterUuid,
      String inviterName,
      UUID inviteeUuid,
      String inviteeName,
      boolean completed,
      boolean claimed,
      Instant boundAt) {}

  public record Dashboard(
      String ownCode,
      String inviterName,
      List<TaskProgress> tasks,
      List<String> inviteeNames,
      int invitedCount,
      int validCount,
      int rank) {}

  public record LeaderboardEntry(int rank, String name, int validCount) {}
}
