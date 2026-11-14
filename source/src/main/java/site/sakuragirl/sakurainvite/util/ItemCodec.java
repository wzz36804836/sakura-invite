package site.sakuragirl.sakurainvite.util;

import java.io.*;
import java.util.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;

/** Versioned modern item bytes, with bounded read support for v1.2.x backups. */
public final class ItemCodec {
  private static final int MAGIC = 0x53493133;
  private static final int MAX_BYTES = 2 * 1024 * 1024;

  private ItemCodec() {}

  public static byte[] encode(List<ItemStack> items) throws IOException {
    if (items.size() > 45) throw new IOException("最多 45 组物品");
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      out.writeInt(MAGIC);
      out.writeInt(items.size());
      for (ItemStack item : items) {
        byte[] data = item.serializeAsBytes();
        if (data.length > MAX_BYTES) throw new IOException("物品数据过大");
        out.writeInt(data.length);
        out.write(data);
        if (bytes.size() > MAX_BYTES) throw new IOException("奖励数据过大");
      }
    }
    return bytes.toByteArray();
  }

  public static List<ItemStack> decode(byte[] data) throws IOException, ClassNotFoundException {
    if (data == null || data.length == 0) return List.of();
    if (data.length > MAX_BYTES) throw new IOException("奖励数据过大");
    DataInputStream header = new DataInputStream(new ByteArrayInputStream(data));
    List<ItemStack> result = new ArrayList<>();
    if (header.readInt() == MAGIC) {
      int size = header.readInt();
      checkCount(size);
      for (int i = 0; i < size; i++) {
        int length = header.readInt();
        if (length < 1 || length > MAX_BYTES || length > header.available())
          throw new IOException("物品数据长度异常");
        result.add(ItemStack.deserializeBytes(header.readNBytes(length)));
      }
      if (header.available() != 0) throw new IOException("奖励数据有多余内容");
    } else {
      try (BukkitObjectInputStream in =
          new BukkitObjectInputStream(new ByteArrayInputStream(data))) {
        in.setObjectInputFilter(
            info ->
                info.depth() > 64 || info.references() > 100000 || info.arrayLength() > MAX_BYTES
                    ? ObjectInputFilter.Status.REJECTED
                    : ObjectInputFilter.Status.UNDECIDED);
        int size = in.readInt();
        checkCount(size);
        for (int i = 0; i < size; i++) {
          Object value = in.readObject();
          if (!(value instanceof ItemStack item)) throw new IOException("旧奖励物品类型异常");
          result.add(item);
        }
      }
    }
    return result;
  }

  private static void checkCount(int size) throws IOException {
    if (size < 0 || size > 45) throw new IOException("奖励组数异常");
  }
}
