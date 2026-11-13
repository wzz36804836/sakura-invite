package site.sakuragirl.sakurainvite.util;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

public final class ItemCodec {
    private ItemCodec() {}

    public static byte[] encode(List<ItemStack> items) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (BukkitObjectOutputStream out = new BukkitObjectOutputStream(bytes)) {
            out.writeInt(items.size());
            for (ItemStack item : items) out.writeObject(item);
        }
        return bytes.toByteArray();
    }

    public static List<ItemStack> decode(byte[] data) throws IOException, ClassNotFoundException {
        if (data == null || data.length == 0) return List.of();
        try (BukkitObjectInputStream in = new BukkitObjectInputStream(new ByteArrayInputStream(data))) {
            int size = in.readInt();
            List<ItemStack> result = new ArrayList<>(size);
            for (int i = 0; i < size; i++) result.add((ItemStack) in.readObject());
            return result;
        }
    }
}
