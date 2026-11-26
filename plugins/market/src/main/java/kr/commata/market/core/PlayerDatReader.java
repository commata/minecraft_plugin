package kr.commata.market.core;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

/**
 * Paper 26.3 플레이어 파일({@code players/data/<uuid>.dat})을 디스크에서 직접 GZIP·NBT 해석해
 * {@code BukkitValues}의 {@code market:applied_tx}와 {@code market:seq}를 확인합니다 (ECONOMY.md 13.6, 14.3).
 *
 * <p>결과는 반드시 세 가지로 나눕니다: {@link MarkerStatus#FOUND}, {@link MarkerStatus#ABSENT},
 * {@link MarkerStatus#UNREADABLE}. 읽기·해석 실패나 UUID 불일치는 절대 {@code ABSENT}로 취급하지 않습니다.
 * Bukkit에 의존하지 않아 단위 테스트로 모든 손상 케이스를 검증합니다.
 */
public final class PlayerDatReader {

    public static final String KEY_APPLIED_TX = "market:applied_tx";
    public static final String KEY_SEQ = "market:seq";

    private static final List<String> LOCATIONS = List.of("players/data", "playerdata");
    private static final int MAX_NBT_DEPTH = 128;

    private static final int TAG_END = 0;
    private static final int TAG_BYTE = 1;
    private static final int TAG_SHORT = 2;
    private static final int TAG_INT = 3;
    private static final int TAG_LONG = 4;
    private static final int TAG_FLOAT = 5;
    private static final int TAG_DOUBLE = 6;
    private static final int TAG_BYTE_ARRAY = 7;
    private static final int TAG_STRING = 8;
    private static final int TAG_LIST = 9;
    private static final int TAG_COMPOUND = 10;
    private static final int TAG_INT_ARRAY = 11;
    private static final int TAG_LONG_ARRAY = 12;

    public enum DatStatus { OK, UNREADABLE }

    public enum MarkerStatus { FOUND, ABSENT, UNREADABLE }

    public record DatSnapshot(DatStatus status, Path file, String appliedTx, long seq, String reason) {
        public static DatSnapshot ok(Path file, String appliedTx, long seq) {
            return new DatSnapshot(DatStatus.OK, file, appliedTx == null ? "" : appliedTx, seq, null);
        }

        public static DatSnapshot unreadable(Path file, String reason) {
            return new DatSnapshot(DatStatus.UNREADABLE, file, "", 0L, reason);
        }
    }

    public record MarkerCheck(MarkerStatus status, Path file, long seq, String reason) {
    }

    private PlayerDatReader() {
    }

    /**
     * {@code World#getWorldFolder()} 결과에서 월드 루트를 구합니다 (Bank {@code PlayerFiles.worldRoot}와 동일).
     */
    public static Path worldRoot(Path worldFolder) {
        Path abs = worldFolder.toAbsolutePath().normalize();
        for (Path p = abs; p != null; p = p.getParent()) {
            Path name = p.getFileName();
            if (name != null && name.toString().equals("dimensions") && p.getParent() != null) {
                return p.getParent();
            }
        }
        return abs;
    }

    /** 플레이어 {@code .dat} 파일 경로를 찾습니다(MC 26.x {@code players/data} 우선, 없으면 {@code playerdata}). */
    public static Path locateDatFile(Path worldRoot, UUID uuid) {
        for (String loc : LOCATIONS) {
            Path candidate = worldRoot.resolve(loc).resolve(uuid + ".dat");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return worldRoot.resolve(LOCATIONS.get(0)).resolve(uuid + ".dat");
    }

    /** 월드 루트에서 플레이어 {@code .dat}를 직접 읽어 검증합니다. */
    public static DatSnapshot read(Path worldRoot, UUID expectedUuid) {
        Path file = locateDatFile(worldRoot, expectedUuid);
        return readFile(file, expectedUuid);
    }

    /** 특정 {@code .dat} 파일을 직접 GZIP 해제·NBT 파싱하고 UUID 일치 여부를 검증합니다. */
    public static DatSnapshot readFile(Path file, UUID expectedUuid) {
        if (file == null || !Files.isRegularFile(file)) {
            return DatSnapshot.unreadable(file, "플레이어 파일 없음");
        }
        try {
            if (Files.size(file) == 0L) {
                return DatSnapshot.unreadable(file, "빈 파일");
            }
        } catch (IOException e) {
            return DatSnapshot.unreadable(file, "파일 크기 확인 실패: " + e.getMessage());
        }

        try (InputStream raw = Files.newInputStream(file);
             GZIPInputStream gzip = new GZIPInputStream(new BufferedInputStream(raw));
             DataInputStream in = new DataInputStream(gzip)) {
            int rootType = in.readUnsignedByte();
            if (rootType != TAG_COMPOUND) {
                return DatSnapshot.unreadable(file, "루트 NBT가 Compound가 아님: " + rootType);
            }
            // 루트 이름 (보통 빈 문자열)
            readString(in);

            RootFields fields = parseRootCompound(in);
            // GZIP 트레일러까지 정상 끝났는지 확인 (잘린 파일 검출)
            if (in.read() != -1) {
                return DatSnapshot.unreadable(file, "루트 Compound 뒤에 예기치 않은 데이터 있음");
            }
            if (fields.uuid == null) {
                return DatSnapshot.unreadable(file, "NBT에 UUID 태그가 없음");
            }
            if (!fields.uuid.equals(expectedUuid)) {
                return DatSnapshot.unreadable(file, "UUID 불일치 (파일=" + fields.uuid + ", 대상=" + expectedUuid + ")");
            }
            return DatSnapshot.ok(file, fields.appliedTx, fields.seq);
        } catch (EOFException e) {
            return DatSnapshot.unreadable(file, "잘린 파일(EOF)");
        } catch (IOException e) {
            return DatSnapshot.unreadable(file, "파일·GZIP·NBT 읽기 실패: " + e.getMessage());
        }
    }

    /** 특정 시도 토큰({@code txId#attempt})이 디스크에 저장됐는지 3상태로 확인합니다. */
    public static MarkerCheck checkExact(Path worldRoot, UUID uuid, String txId, int attempt) {
        DatSnapshot snap = read(worldRoot, uuid);
        if (snap.status() != DatStatus.OK) {
            return new MarkerCheck(MarkerStatus.UNREADABLE, snap.file(), 0L, snap.reason());
        }
        boolean found = AppliedTxTokens.containsExact(snap.appliedTx(), txId, attempt);
        return new MarkerCheck(found ? MarkerStatus.FOUND : MarkerStatus.ABSENT, snap.file(), snap.seq(), null);
    }

    /** 어느 시도든 {@code txId#} 토큰이 디스크에 저장됐는지 3상태로 확인합니다(기동·접속 복구용). */
    public static MarkerCheck checkAnyAttempt(Path worldRoot, UUID uuid, String txId) {
        DatSnapshot snap = read(worldRoot, uuid);
        if (snap.status() != DatStatus.OK) {
            return new MarkerCheck(MarkerStatus.UNREADABLE, snap.file(), 0L, snap.reason());
        }
        boolean found = AppliedTxTokens.containsAnyAttempt(snap.appliedTx(), txId);
        return new MarkerCheck(found ? MarkerStatus.FOUND : MarkerStatus.ABSENT, snap.file(), snap.seq(), null);
    }

    // ------------------------------------------------------------------ NBT 파서

    private static final class RootFields {
        UUID uuid;
        Long uuidMost;
        Long uuidLeast;
        String appliedTx = "";
        long seq;
    }

    private static RootFields parseRootCompound(DataInputStream in) throws IOException {
        RootFields out = new RootFields();
        while (true) {
            int type = in.readUnsignedByte();
            if (type == TAG_END) {
                break;
            }
            String name = readString(in);
            if ("UUID".equals(name) && type == TAG_INT_ARRAY) {
                int len = in.readInt();
                if (len != 4) {
                    throw new IOException("UUID IntArray 길이 오류: " + len);
                }
                int a0 = in.readInt();
                int a1 = in.readInt();
                int a2 = in.readInt();
                int a3 = in.readInt();
                long msb = ((long) a0 << 32) | (a1 & 0xFFFFFFFFL);
                long lsb = ((long) a2 << 32) | (a3 & 0xFFFFFFFFL);
                out.uuid = new UUID(msb, lsb);
            } else if ("UUIDMost".equals(name) && type == TAG_LONG) {
                out.uuidMost = in.readLong();
            } else if ("UUIDLeast".equals(name) && type == TAG_LONG) {
                out.uuidLeast = in.readLong();
            } else if ("BukkitValues".equals(name) && type == TAG_COMPOUND) {
                parseBukkitValues(in, out);
            } else {
                skipPayload(in, type, 1);
            }
        }
        if (out.uuid == null && out.uuidMost != null && out.uuidLeast != null) {
            out.uuid = new UUID(out.uuidMost, out.uuidLeast);
        }
        return out;
    }

    private static void parseBukkitValues(DataInputStream in, RootFields out) throws IOException {
        while (true) {
            int type = in.readUnsignedByte();
            if (type == TAG_END) {
                break;
            }
            String name = readString(in);
            if (KEY_APPLIED_TX.equals(name) && type == TAG_STRING) {
                out.appliedTx = readString(in);
            } else if (KEY_SEQ.equals(name) && type == TAG_LONG) {
                out.seq = in.readLong();
            } else if (KEY_SEQ.equals(name) && type == TAG_INT) {
                out.seq = in.readInt();
            } else {
                skipPayload(in, type, 2);
            }
        }
    }

    private static void skipPayload(DataInputStream in, int type, int depth) throws IOException {
        if (depth > MAX_NBT_DEPTH) {
            throw new IOException("NBT 중첩 깊이 초과");
        }
        switch (type) {
            case TAG_BYTE -> in.readByte();
            case TAG_SHORT -> in.readShort();
            case TAG_INT, TAG_FLOAT -> in.readInt();
            case TAG_LONG, TAG_DOUBLE -> in.readLong();
            case TAG_BYTE_ARRAY -> skipExactly(in, checkNonNegativeLength(in.readInt()));
            case TAG_STRING -> skipExactly(in, in.readUnsignedShort());
            case TAG_LIST -> {
                int elemType = in.readUnsignedByte();
                int len = in.readInt();
                if (len < 0) {
                    throw new IOException("음수 리스트 길이: " + len);
                }
                if (len > 0 && elemType == TAG_END) {
                    throw new IOException("비어 있지 않은 TAG_END 리스트");
                }
                for (int i = 0; i < len; i++) {
                    skipPayload(in, elemType, depth + 1);
                }
            }
            case TAG_COMPOUND -> {
                while (true) {
                    int childType = in.readUnsignedByte();
                    if (childType == TAG_END) {
                        break;
                    }
                    readString(in);
                    skipPayload(in, childType, depth + 1);
                }
            }
            case TAG_INT_ARRAY -> skipExactly(in, (long) checkNonNegativeLength(in.readInt()) * 4L);
            case TAG_LONG_ARRAY -> skipExactly(in, (long) checkNonNegativeLength(in.readInt()) * 8L);
            default -> throw new IOException("알 수 없는 NBT 태그 종류: " + type);
        }
    }

    private static int checkNonNegativeLength(int len) throws IOException {
        if (len < 0) {
            throw new IOException("음수 배열 길이: " + len);
        }
        return len;
    }

    private static void skipExactly(DataInputStream in, long bytes) throws IOException {
        long remaining = bytes;
        byte[] buf = new byte[(int) Math.min(remaining, 4096)];
        while (remaining > 0) {
            int n = in.read(buf, 0, (int) Math.min(remaining, buf.length));
            if (n < 0) {
                throw new EOFException("예상보다 일찍 EOF 도달");
            }
            remaining -= n;
        }
    }

    private static String readString(DataInputStream in) throws IOException {
        return in.readUTF();
    }
}
