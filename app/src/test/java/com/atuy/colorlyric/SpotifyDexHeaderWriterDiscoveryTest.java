package com.atuy.colorlyric;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Set;

public final class SpotifyDexHeaderWriterDiscoveryTest {
    @Test
    public void findsObfuscatedHeaderWriterWithoutDependingOnItsName() {
        Set<SpotifyDexHeaderWriterDiscovery.Candidate> found =
                SpotifyDexHeaderWriterDiscovery.scanDex(fakeDex("Authorization", 1));
        assertEquals(1, found.size());
        assertTrue(found.contains(new SpotifyDexHeaderWriterDiscovery.Candidate(
                "com.spotify.net.RandomizedWriter", "a")));
    }

    @Test
    public void rejectsOtherStringsAndNonVoidSignatures() {
        assertTrue(SpotifyDexHeaderWriterDiscovery.scanDex(
                fakeDex("NonAuthorization", 1)).isEmpty());
        assertTrue(SpotifyDexHeaderWriterDiscovery.scanDex(
                fakeDex("Authorization", 0)).isEmpty());
        assertTrue(SpotifyDexHeaderWriterDiscovery.scanDex(new byte[128]).isEmpty());
    }

    private static byte[] fakeDex(String marker, int writerReturnType) {
        // Minimal structurally valid DEX layout: string/type/proto/method/class
        // tables, one class-data list, and one tiny code item invoking the writer.
        byte[] out = new byte[0x400];
        ByteBuffer b = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        b.put(0, (byte) 'd'); b.put(1, (byte) 'e');
        b.put(2, (byte) 'x'); b.put(3, (byte) '\n');
        b.putInt(0x38, 7); b.putInt(0x3c, 0x70);
        b.putInt(0x40, 4); b.putInt(0x44, 0x8c);
        b.putInt(0x48, 2); b.putInt(0x4c, 0x9c);
        b.putInt(0x58, 2); b.putInt(0x5c, 0xb4);
        b.putInt(0x60, 1); b.putInt(0x64, 0xc4);

        String[] strings = { marker, "Ljava/lang/String;", "V",
                "Lcom/spotify/net/RandomizedWriter;", "a",
                "Lcom/spotify/net/Caller;", "run" };
        int p = 0x170;
        for (int i = 0; i < strings.length; i++) {
            b.putInt(0x70 + i * 4, p);
            byte[] bytes = strings[i].getBytes(StandardCharsets.UTF_8);
            b.put(p++, (byte) bytes.length);
            for (byte value : bytes) b.put(p++, value);
            b.put(p++, (byte) 0);
        }

        // Type IDs: String, void, candidate writer and caller.
        b.putInt(0x8c, 1); b.putInt(0x90, 2);
        b.putInt(0x94, 3); b.putInt(0x98, 5);
        // Writer has (String, String) -> void, caller has () -> void.
        b.putInt(0x9c + 4, writerReturnType);
        b.putInt(0x9c + 8, 0xf0);
        b.putInt(0x9c + 12 + 4, 1);
        b.putInt(0xf0, 2);
        b.putShort(0xf4, (short) 0);
        b.putShort(0xf6, (short) 0);
        // Method IDs.
        b.putShort(0xb4, (short) 2);
        b.putShort(0xb4 + 2, (short) 0);
        b.putInt(0xb4 + 4, 4);
        b.putShort(0xbc, (short) 3);
        b.putShort(0xbc + 2, (short) 1);
        b.putInt(0xbc + 4, 6);
        // One class with one direct method whose method ID index is 1.
        b.putInt(0xc4, 3);
        b.putInt(0xc4 + 8, -1);
        b.putInt(0xc4 + 16, -1);
        b.putInt(0xc4 + 24, 0x110);
        b.put(0x110, (byte) 0); b.put(0x111, (byte) 0);
        b.put(0x112, (byte) 1); b.put(0x113, (byte) 0);
        b.put(0x114, (byte) 1); b.put(0x115, (byte) 1);
        b.put(0x116, (byte) 0xb0); b.put(0x117, (byte) 0x02);

        b.putShort(0x130, (short) 3);
        b.putShort(0x130 + 4, (short) 3);
        b.putInt(0x130 + 12, 6);
        // const-string v0, string@0; invoke-virtual {v0, v1, v2}, method@0.
        b.putShort(0x140, (short) 0x001a);
        b.putShort(0x142, (short) 0);
        b.putShort(0x144, (short) 0x306e);
        b.putShort(0x146, (short) 0);
        b.putShort(0x148, (short) 0x0210);
        b.putShort(0x14a, (short) 0x000e);
        return out;
    }
}
