/* SPDX-License-Identifier: GPL-3.0-only */
package com.atuy.colorlyric;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Discovers request-header writers by following DEX invoke instructions near
 * the stable HTTP header name "Authorization". R8 class and method names are
 * not used, and only a small, explicitly matched set of methods is hooked.
 *
 * The scanner is read-only and makes no class-loading or hooking decisions.
 */
final class SpotifyDexHeaderWriterDiscovery {
    private static final int MAX_DEX_SIZE = 48 * 1024 * 1024;
    private static final int MAX_CANDIDATES = 64;
    // All current Android request writers are called within a few instructions
    // of the Authorization constant; 48 code units allows for intermediate work.
    private static final int MAX_FORWARD_CODE_UNITS = 48;

    private SpotifyDexHeaderWriterDiscovery() {}

    static final class Candidate {
        final String className;
        final String methodName;

        Candidate(String className, String methodName) {
            this.className = className;
            this.methodName = methodName;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Candidate)) return false;
            Candidate value = (Candidate) other;
            return className.equals(value.className) && methodName.equals(value.methodName);
        }

        @Override public int hashCode() {
            return 31 * className.hashCode() + methodName.hashCode();
        }
    }

    static Set<Candidate> scanApk(String apkPath) throws IOException {
        if (apkPath == null || apkPath.isBlank()) return Collections.emptySet();
        Set<Candidate> out = new LinkedHashSet<>();
        try (ZipFile apk = new ZipFile(apkPath)) {
            for (java.util.Enumeration<? extends ZipEntry> entries = apk.entries();
                    entries.hasMoreElements() && out.size() < MAX_CANDIDATES;) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.matches("classes(\\d+)?\\.dex") || entry.isDirectory()
                        || entry.getSize() > MAX_DEX_SIZE) continue;
                try (InputStream stream = apk.getInputStream(entry)) {
                    byte[] bytes = stream.readNBytes(MAX_DEX_SIZE + 1);
                    if (bytes.length > MAX_DEX_SIZE) continue;
                    out.addAll(scanDex(bytes));
                } catch (RuntimeException ignored) {
                    // A malformed or unsupported secondary DEX should not
                    // suppress discovery from the other DEX files.
                }
            }
        }
        return out;
    }

    static Set<Candidate> scanDex(byte[] data) {
        if (data == null || data.length < 112 || data.length > MAX_DEX_SIZE
                || data[0] != 'd' || data[1] != 'e' || data[2] != 'x'
                || data[3] != '\n') return Collections.emptySet();
        try {
            return new Dex(data).discover();
        } catch (RuntimeException ignored) {
            return Collections.emptySet();
        }
    }

    private static final class Dex {
        final byte[] data;
        final int stringCount;
        final int stringOffset;
        final int typeCount;
        final int typeOffset;
        final int protoCount;
        final int protoOffset;
        final int methodCount;
        final int methodOffset;
        final int classCount;
        final int classOffset;
        final String[] stringCache;
        final boolean[] authorizationStrings;

        Dex(byte[] bytes) {
            data = bytes;
            stringCount = count(0x38, 0x3c, 4);
            stringOffset = u32(0x3c);
            typeCount = count(0x40, 0x44, 4);
            typeOffset = u32(0x44);
            protoCount = count(0x48, 0x4c, 12);
            protoOffset = u32(0x4c);
            methodCount = count(0x58, 0x5c, 8);
            methodOffset = u32(0x5c);
            classCount = count(0x60, 0x64, 32);
            classOffset = u32(0x64);
            stringCache = new String[stringCount];
            authorizationStrings = new boolean[stringCount];
            for (int i = 0; i < stringCount; i++) {
                int position = u32(stringOffset + i * 4);
                Cursor cursor = new Cursor(position);
                cursor.uleb(); // UTF-16 length
                if (cursor.offset + 13 <= data.length
                        && matchesAuthorization(cursor.offset)) {
                    authorizationStrings[i] = true;
                }
            }
        }

        boolean matchesAuthorization(int start) {
            byte[] marker = "Authorization".getBytes(StandardCharsets.US_ASCII);
            if (start + marker.length >= data.length) return false;
            for (int j = 0; j < marker.length; j++) {
                int c = data[start + j] & 0xff;
                if (c >= 'A' && c <= 'Z') c += 'a' - 'A';
                int expected = marker[j];
                if (expected >= 'A' && expected <= 'Z') expected += 'a' - 'A';
                if (c != expected) return false;
            }
            return data[start + marker.length] == 0;
        }

        Set<Candidate> discover() {
            Set<Candidate> found = new LinkedHashSet<>();
            for (int i = 0; i < classCount && found.size() < MAX_CANDIDATES; i++) {
                int classData = u32(classOffset + i * 32 + 24);
                if (classData == 0) continue;
                Cursor cursor = new Cursor(classData);
                int staticFields = cursor.uleb();
                int instanceFields = cursor.uleb();
                int directMethods = cursor.uleb();
                int virtualMethods = cursor.uleb();
                if ((long) staticFields + instanceFields + directMethods + virtualMethods > 200_000) {
                    continue;
                }
                for (int n = 0; n < staticFields + instanceFields; n++) {
                    cursor.uleb(); cursor.uleb();
                }
                for (int list = 0; list < 2; list++) {
                    int methodIndex = 0;
                    int length = list == 0 ? directMethods : virtualMethods;
                    for (int n = 0; n < length; n++) {
                        methodIndex += cursor.uleb();
                        cursor.uleb(); // access flags
                        int codeOffset = cursor.uleb();
                        if (methodIndex >= methodCount || codeOffset == 0) continue;
                        scanCode(codeOffset, found);
                        if (found.size() >= MAX_CANDIDATES) return found;
                    }
                }
            }
            return found;
        }

        void scanCode(int codeOffset, Set<Candidate> out) {
            int codeUnits = u32(codeOffset + 12);
            long start = (long) codeOffset + 16;
            long end = start + (long) codeUnits * 2;
            if (codeUnits < 2 || end > data.length) return;
            for (int position = (int) start; position + 4 <= end; position += 2) {
                int opcode = data[position] & 0xff;
                int index;
                if (opcode == 0x1a) {
                    index = u16(position + 2);
                } else if (opcode == 0x1b && position + 6 <= end) {
                    index = u32(position + 2);
                } else continue;
                if (index < 0 || index >= authorizationStrings.length
                        || !authorizationStrings[index]) continue;
                int limit = (int) Math.min(end - 4,
                        position + MAX_FORWARD_CODE_UNITS * 2L);
                for (int next = position + 2; next <= limit; next += 2) {
                    int invoke = data[next] & 0xff;
                    // invoke-virtual, invoke-direct and their /range forms.
                    // Static, interface and constructor calls cannot be hooked
                    // as a concrete instance header writer.
                    if (invoke != 0x6e && invoke != 0x70
                            && invoke != 0x74 && invoke != 0x76) continue;
                    int methodIndex = u16(next + 2);
                    Candidate candidate = toCandidate(methodIndex);
                    if (candidate != null) out.add(candidate);
                    if (out.size() >= MAX_CANDIDATES) return;
                }
            }
        }

        Candidate toCandidate(int index) {
            if (index >= methodCount) return null;
            int record = methodOffset + index * 8;
            int classIndex = u16(record);
            int protoIndex = u16(record + 2);
            int nameIndex = u32(record + 4);
            if (protoIndex >= protoCount || !isStringPairVoid(protoIndex)) return null;
            String name = string(nameIndex);
            if (name.startsWith("<")) return null;
            String descriptor = type(classIndex);
            if (!descriptor.startsWith("L") || !descriptor.endsWith(";")) return null;
            String className = descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
            if (className.startsWith("java.") || className.startsWith("android.")
                    || className.startsWith("javax.")) return null;
            return new Candidate(className, name);
        }

        boolean isStringPairVoid(int protoIndex) {
            int record = protoOffset + protoIndex * 12;
            if (!"V".equals(type(u32(record + 4)))) return false;
            int parameters = u32(record + 8);
            return parameters != 0 && u32(parameters) == 2
                    && "Ljava/lang/String;".equals(type(u16(parameters + 4)))
                    && "Ljava/lang/String;".equals(type(u16(parameters + 6)));
        }

        String type(int typeIndex) {
            if (typeIndex < 0 || typeIndex >= typeCount) throw new IllegalArgumentException();
            return string(u32(typeOffset + typeIndex * 4));
        }

        String string(int index) {
            if (index < 0 || index >= stringCount) throw new IllegalArgumentException();
            if (stringCache[index] != null) return stringCache[index];
            Cursor cursor = new Cursor(u32(stringOffset + index * 4));
            cursor.uleb();
            int begin = cursor.offset;
            while (cursor.offset < data.length && data[cursor.offset] != 0) cursor.offset++;
            if (cursor.offset >= data.length || cursor.offset - begin > 512) {
                throw new IllegalArgumentException("invalid string");
            }
            return stringCache[index] = new String(data, begin, cursor.offset - begin,
                    StandardCharsets.UTF_8);
        }

        int count(int countAt, int tableAt, int width) {
            int count = u32(countAt);
            int offset = u32(tableAt);
            if ((long) offset + (long) count * width > data.length) {
                throw new IllegalArgumentException("invalid dex table");
            }
            return count;
        }

        int u16(int offset) {
            if (offset < 0 || offset + 2 > data.length) throw new IllegalArgumentException();
            return (data[offset] & 255) | ((data[offset + 1] & 255) << 8);
        }

        int u32(int offset) {
            if (offset < 0 || offset + 4 > data.length) throw new IllegalArgumentException();
            return (data[offset] & 255) | ((data[offset + 1] & 255) << 8)
                    | ((data[offset + 2] & 255) << 16) | ((data[offset + 3] & 255) << 24);
        }

        final class Cursor {
            int offset;
            Cursor(int position) { offset = position; }
            int uleb() {
                int out = 0;
                for (int i = 0; i < 5; i++) {
                    if (offset >= data.length) throw new IllegalArgumentException();
                    int value = data[offset++] & 255;
                    out |= (value & 127) << (i * 7);
                    if ((value & 128) == 0) return out;
                }
                throw new IllegalArgumentException("invalid uleb");
            }
        }
    }
}
