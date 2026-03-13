package mage.util;

import java.awt.*;
import java.util.Collection;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Created by IGOUDT on 5-9-2016.
 */
public final class RandomUtil {

    private static final Random random = new Random(); // thread safe with seed support

    private RandomUtil() {
    }

    public static Random getRandom() {
        return random;
    }

    public static int nextInt() {
        return random.nextInt();
    }

    public static int nextInt(int max) {
        return random.nextInt(max);
    }

    public static boolean nextBoolean() {
        return random.nextBoolean();
    }

    public static double nextDouble() {
        return random.nextDouble();
    }

    public static Color nextColor() {
        return new Color(RandomUtil.nextInt(256), RandomUtil.nextInt(256), RandomUtil.nextInt(256));
    }

    public static void setSeed(long newSeed) {
        random.setSeed(newSeed);
    }

    public static UUID randomUUID() {
        byte[] data = new byte[16];
        random.nextBytes(data);
        data[6] = (byte) (data[6] & 0x0f | 0x40); // version 4
        data[8] = (byte) (data[8] & 0x3f | 0x80); // IETF variant
        long msb = 0, lsb = 0;
        for (int i = 0; i < 8; i++) msb = (msb << 8) | (data[i] & 0xff);
        for (int i = 8; i < 16; i++) lsb = (lsb << 8) | (data[i] & 0xff);
        return new UUID(msb, lsb);
    }

    public static <T> T randomFromCollection(Collection<T> collection) {
        if (collection.size() < 2) {
            return collection.stream().findFirst().orElse(null);
        }
        int rand = nextInt(collection.size());
        int count = 0;
        for (T current : collection) {
            if (count == rand) {
                return current;
            }
            count++;
        }
        return null;
    }
}
