package kr.commata.market.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link PlayerDatReader} 의 7가지 필수 시나리오 검증 (ECONOMY.md 13.6):
 * 정상 표시 있음·없음, 손상된 gzip, 잘린 NBT, 빈 파일, 없는 파일, 다른 UUID.
 */
class PlayerDatReaderTest {

    @TempDir
    Path temp;

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @Test
    void readsFoundAndAbsentAndSeqFromValidPlayerDat() throws Exception {
        Path world = temp.resolve("world");
        Path file = world.resolve("players/data").resolve(alice + ".dat");
        writePlayerDat(file, alice, "tx-1#1,tx-2#3", 7L);

        PlayerDatReader.DatSnapshot snap = PlayerDatReader.read(world, alice);
        assertEquals(PlayerDatReader.DatStatus.OK, snap.status());
        assertEquals(7L, snap.seq());

        assertEquals(PlayerDatReader.MarkerStatus.FOUND,
                PlayerDatReader.checkExact(world, alice, "tx-2", 3).status());
        assertEquals(PlayerDatReader.MarkerStatus.ABSENT,
                PlayerDatReader.checkExact(world, alice, "tx-2", 2).status());
        assertEquals(PlayerDatReader.MarkerStatus.FOUND,
                PlayerDatReader.checkAnyAttempt(world, alice, "tx-2").status());
        assertEquals(PlayerDatReader.MarkerStatus.ABSENT,
                PlayerDatReader.checkAnyAttempt(world, alice, "tx-999").status());
    }

    @Test
    void fallsBackToLegacyPlayerdataFolder() throws Exception {
        Path world = temp.resolve("legacy");
        Path file = world.resolve("playerdata").resolve(alice + ".dat");
        writePlayerDat(file, alice, "tx-a#1", 2L);

        assertEquals(PlayerDatReader.MarkerStatus.FOUND,
                PlayerDatReader.checkExact(world, alice, "tx-a", 1).status());
    }

    @Test
    void returnsUnreadableForMissingEmptyCorruptTruncatedAndWrongUuid() throws Exception {
        Path world = temp.resolve("broken");
        Path dir = world.resolve("players/data");
        Files.createDirectories(dir);

        // 1) 없는 파일
        assertEquals(PlayerDatReader.MarkerStatus.UNREADABLE,
                PlayerDatReader.checkAnyAttempt(world, alice, "tx-1").status());

        // 2) 빈 파일
        Path file = dir.resolve(alice + ".dat");
        Files.write(file, new byte[0]);
        assertEquals(PlayerDatReader.MarkerStatus.UNREADABLE,
                PlayerDatReader.checkAnyAttempt(world, alice, "tx-1").status());

        // 3) 손상된 GZIP
        Files.writeString(file, "not a gzip file at all");
        assertEquals(PlayerDatReader.MarkerStatus.UNREADABLE,
                PlayerDatReader.checkAnyAttempt(world, alice, "tx-1").status());

        // 4) 잘린 NBT (GZIP 안의 NBT 가 끝 태그 없이 잘림)
        byte[] valid = buildPlayerDatBytes(alice, "tx-1#1", 3L);
        byte[] truncatedGzip = new byte[Math.max(1, valid.length - 10)];
        System.arraycopy(valid, 0, truncatedGzip, 0, truncatedGzip.length);
        Files.write(file, truncatedGzip);
        assertEquals(PlayerDatReader.MarkerStatus.UNREADABLE,
                PlayerDatReader.checkAnyAttempt(world, alice, "tx-1").status());

        // 5) 다른 플레이어의 UUID 가 들어 있는 파일
        writePlayerDat(file, bob, "tx-1#1", 3L);
        assertEquals(PlayerDatReader.MarkerStatus.UNREADABLE,
                PlayerDatReader.checkAnyAttempt(world, alice, "tx-1").status());
    }

    @Test
    void resolvesWorldRootFromDimensionPath() {
        Path dim = temp.resolve("world/dimensions/minecraft/overworld");
        assertEquals(temp.resolve("world").toAbsolutePath().normalize(), PlayerDatReader.worldRoot(dim));
    }

    // ------------------------------------------------------------------ 최소 NBT 생성 헬퍼

    private static void writePlayerDat(Path file, UUID uuid, String appliedTx, long seq) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, buildPlayerDatBytes(uuid, appliedTx, seq));
    }

    private static byte[] buildPlayerDatBytes(UUID uuid, String appliedTx, long seq) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(baos);
             DataOutputStream out = new DataOutputStream(gz)) {
            // Root TAG_Compound ("")
            out.writeByte(10);
            out.writeUTF("");

            // 잡다한 태그 건너뛰기 테스트용 (List, Compound, Int, Double 등)
            out.writeByte(3); // TAG_Int
            out.writeUTF("XpLevel");
            out.writeInt(30);

            out.writeByte(9); // TAG_List of TAG_Double
            out.writeUTF("Pos");
            out.writeByte(6);
            out.writeInt(3);
            out.writeDouble(10.5);
            out.writeDouble(64.0);
            out.writeDouble(-20.25);

            // UUID: TAG_Int_Array[4]
            out.writeByte(11);
            out.writeUTF("UUID");
            out.writeInt(4);
            long msb = uuid.getMostSignificantBits();
            long lsb = uuid.getLeastSignificantBits();
            out.writeInt((int) (msb >>> 32));
            out.writeInt((int) msb);
            out.writeInt((int) (lsb >>> 32));
            out.writeInt((int) lsb);

            // BukkitValues: TAG_Compound
            out.writeByte(10);
            out.writeUTF("BukkitValues");
            if (appliedTx != null) {
                out.writeByte(8); // TAG_String
                out.writeUTF(PlayerDatReader.KEY_APPLIED_TX);
                out.writeUTF(appliedTx);
            }
            out.writeByte(4); // TAG_Long
            out.writeUTF(PlayerDatReader.KEY_SEQ);
            out.writeLong(seq);
            out.writeByte(0); // end BukkitValues

            out.writeByte(0); // end Root
        }
        return baos.toByteArray();
    }
}
