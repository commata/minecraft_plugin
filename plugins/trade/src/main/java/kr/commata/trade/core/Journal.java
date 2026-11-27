package kr.commata.trade.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.CRC32;
import kr.commata.common.io.AtomicFiles;

/**
 * 거래 정산 저널({@code plugins/Trade/journal/<tradeId>.bin}) 직렬화, 원자적 쓰기({@link AtomicFiles}),
 * 손상 파일 격리 및 잠금({@code LOCKED}·{@code corrupt/*.resolved}) 처리 (TRADE.md 5·6·13-1·13-2장).
 *
 * <p>손상 저널 처리 순서(필수 불변식):
 * <ol>
 *   <li>읽기·역직렬화 실패 감지 → <b>먼저</b> {@code journal/LOCKED} 를 fsync 로 생성</li>
 *   <li><b>그 다음</b> 손상 파일을 {@code journal/corrupt/} 로 이동</li>
 *   <li>기동·reload 때는 {@code LOCKED} 가 있거나 {@code corrupt/} 에 {@code .resolved} 표시가 없는 파일이
 *       하나라도 있으면 Trade 전체 잠금</li>
 *   <li>관리자 {@code /trade resolve unlock}: 격리 파일마다 {@code <파일>.resolved} 를 fsync 로 먼저 남기고,
 *       미해결 파일이 0건일 때만 {@code LOCKED} 를 삭제</li>
 * </ol>
 */
public final class Journal {

    private static final byte[] MAGIC = {'T', 'R', 'D', 'J'};
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_PAYLOAD_BYTES = 16 * 1024 * 1024;
    private static final int MAX_ITEM_BYTES = 2 * 1024 * 1024;

    public enum Stage {
        TAKING(0),
        COMMITTED(1),
        RETURNING(2);

        private final int code;

        Stage(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }

        public static Stage fromCode(int code) throws IOException {
            for (Stage s : values()) {
                if (s.code == code) {
                    return s;
                }
            }
            throw new IOException("알 수 없는 저널 단계 코드: " + code);
        }
    }

    public enum SideStatus {
        NONE(0),
        PENDING(1),
        FOUND(2),
        ABSENT(3);

        private final int code;

        SideStatus(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }

        public static SideStatus fromCode(int code) throws IOException {
            for (SideStatus s : values()) {
                if (s.code == code) {
                    return s;
                }
            }
            throw new IOException("알 수 없는 쪽 상태 코드: " + code);
        }
    }

    /** 저널 한 건의 불변 스냅샷 */
    public record Entry(
            UUID tradeId,
            long createdAtMs,
            long updatedAtMs,
            Stage stage,
            UUID playerA,
            String nameA,
            UUID playerB,
            String nameB,
            long aPays,
            long bPays,
            List<byte[]> itemsFromA,
            List<byte[]> itemsFromB,
            List<String> summaryFromA,
            List<String> summaryFromB,
            SideStatus takeA,
            SideStatus takeB,
            SideStatus giveA,
            SideStatus giveB,
            SideStatus returnA,
            SideStatus returnB,
            int giveAttemptA,
            int giveAttemptB,
            int returnAttemptA,
            int returnAttemptB,
            int takeSaveRetriesA,
            int takeSaveRetriesB,
            long bankTxAId,
            long bankTxBId,
            String cancelReason,
            boolean recovered,
            String sealedReason
    ) {
        public Entry {
            Objects.requireNonNull(tradeId, "tradeId");
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(playerA, "playerA");
            Objects.requireNonNull(playerB, "playerB");
            if (playerA.equals(playerB)) {
                throw new IllegalArgumentException("자기 자신 거래 저널은 허용되지 않습니다.");
            }
            nameA = nameA == null ? "" : nameA;
            nameB = nameB == null ? "" : nameB;
            if (aPays < 0 || aPays > OfferValidation.MAX_AMOUNT || bPays < 0 || bPays > OfferValidation.MAX_AMOUNT) {
                throw new IllegalArgumentException("저널 금액 범위 오류: aPays=" + aPays + ", bPays=" + bPays);
            }
            itemsFromA = copyBytesList(itemsFromA);
            itemsFromB = copyBytesList(itemsFromB);
            if (itemsFromA.size() > OfferValidation.MAX_OFFER_SLOTS || itemsFromB.size() > OfferValidation.MAX_OFFER_SLOTS) {
                throw new IllegalArgumentException("저널 아이템 수 초과");
            }
            summaryFromA = summaryFromA == null ? List.of() : List.copyOf(summaryFromA);
            summaryFromB = summaryFromB == null ? List.of() : List.copyOf(summaryFromB);
            Objects.requireNonNull(takeA, "takeA");
            Objects.requireNonNull(takeB, "takeB");
            Objects.requireNonNull(giveA, "giveA");
            Objects.requireNonNull(giveB, "giveB");
            Objects.requireNonNull(returnA, "returnA");
            Objects.requireNonNull(returnB, "returnB");
            if (giveAttemptA < 1 || giveAttemptB < 1 || returnAttemptA < 1 || returnAttemptB < 1) {
                throw new IllegalArgumentException("시도 번호는 1 이상이어야 합니다.");
            }
            if (takeSaveRetriesA < 0 || takeSaveRetriesB < 0) {
                throw new IllegalArgumentException("재시도 횟수는 음수일 수 없습니다.");
            }
            cancelReason = cancelReason == null ? "" : cancelReason;
            sealedReason = sealedReason == null ? "" : sealedReason;
        }

        public boolean hasMoney() {
            return aPays > 0 || bPays > 0;
        }

        public boolean involves(UUID uuid) {
            return playerA.equals(uuid) || playerB.equals(uuid);
        }

        public boolean isSealed() {
            return !sealedReason.isEmpty();
        }

        public TradeSession.Side sideOf(UUID uuid) {
            if (playerA.equals(uuid)) {
                return TradeSession.Side.A;
            }
            if (playerB.equals(uuid)) {
                return TradeSession.Side.B;
            }
            return null;
        }

        public UUID uuidOf(TradeSession.Side side) {
            return side == TradeSession.Side.A ? playerA : playerB;
        }

        public String nameOf(TradeSession.Side side) {
            return side == TradeSession.Side.A ? nameA : nameB;
        }

        /** {@code side} 가 보낸(자기 원래) 아이템 바이트 목록 */
        public List<byte[]> ownItems(TradeSession.Side side) {
            return side == TradeSession.Side.A ? itemsFromA : itemsFromB;
        }

        /** {@code side} 가 받을(상대가 보낸) 아이템 바이트 목록 */
        public List<byte[]> incomingItems(TradeSession.Side side) {
            return side == TradeSession.Side.A ? itemsFromB : itemsFromA;
        }

        public SideStatus takeOf(TradeSession.Side side) {
            return side == TradeSession.Side.A ? takeA : takeB;
        }

        public SideStatus giveOf(TradeSession.Side side) {
            return side == TradeSession.Side.A ? giveA : giveB;
        }

        public SideStatus returnOf(TradeSession.Side side) {
            return side == TradeSession.Side.A ? returnA : returnB;
        }

        public int giveAttemptOf(TradeSession.Side side) {
            return side == TradeSession.Side.A ? giveAttemptA : giveAttemptB;
        }

        public int returnAttemptOf(TradeSession.Side side) {
            return side == TradeSession.Side.A ? returnAttemptA : returnAttemptB;
        }

        public int takeSaveRetriesOf(TradeSession.Side side) {
            return side == TradeSession.Side.A ? takeSaveRetriesA : takeSaveRetriesB;
        }

        public Entry withTake(TradeSession.Side side, SideStatus status, int saveRetries, long nowMs) {
            return new Entry(
                    tradeId, createdAtMs, nowMs, stage,
                    playerA, nameA, playerB, nameB, aPays, bPays,
                    itemsFromA, itemsFromB, summaryFromA, summaryFromB,
                    side == TradeSession.Side.A ? status : takeA,
                    side == TradeSession.Side.B ? status : takeB,
                    giveA, giveB, returnA, returnB,
                    giveAttemptA, giveAttemptB, returnAttemptA, returnAttemptB,
                    side == TradeSession.Side.A ? saveRetries : takeSaveRetriesA,
                    side == TradeSession.Side.B ? saveRetries : takeSaveRetriesB,
                    bankTxAId, bankTxBId, cancelReason, recovered, sealedReason);
        }

        public Entry withCommitted(long txAId, long txBId, boolean isRecovered, long nowMs) {
            return new Entry(
                    tradeId, createdAtMs, nowMs, Stage.COMMITTED,
                    playerA, nameA, playerB, nameB, aPays, bPays,
                    itemsFromA, itemsFromB, summaryFromA, summaryFromB,
                    SideStatus.FOUND, SideStatus.FOUND,
                    itemsFromB.isEmpty() ? SideStatus.FOUND : giveA,
                    itemsFromA.isEmpty() ? SideStatus.FOUND : giveB,
                    SideStatus.NONE, SideStatus.NONE,
                    giveAttemptA, giveAttemptB, returnAttemptA, returnAttemptB,
                    takeSaveRetriesA, takeSaveRetriesB,
                    txAId, txBId, "", recovered || isRecovered, sealedReason);
        }

        /** 단방향 {@code RETURNING} 전환 (TRADE.md 5장 7번, 13-1장). */
        public Entry withReturning(SideStatus retA, SideStatus retB, String reason, boolean isRecovered, long nowMs) {
            return new Entry(
                    tradeId, createdAtMs, nowMs, Stage.RETURNING,
                    playerA, nameA, playerB, nameB, aPays, bPays,
                    itemsFromA, itemsFromB, summaryFromA, summaryFromB,
                    takeA, takeB, giveA, giveB,
                    retA, retB,
                    giveAttemptA, giveAttemptB, returnAttemptA, returnAttemptB,
                    takeSaveRetriesA, takeSaveRetriesB,
                    bankTxAId, bankTxBId, reason == null ? "" : reason, recovered || isRecovered, sealedReason);
        }

        public Entry withGive(TradeSession.Side side, SideStatus status, int attempt, boolean isRecovered, long nowMs) {
            return new Entry(
                    tradeId, createdAtMs, nowMs, stage,
                    playerA, nameA, playerB, nameB, aPays, bPays,
                    itemsFromA, itemsFromB, summaryFromA, summaryFromB,
                    takeA, takeB,
                    side == TradeSession.Side.A ? status : giveA,
                    side == TradeSession.Side.B ? status : giveB,
                    returnA, returnB,
                    side == TradeSession.Side.A ? attempt : giveAttemptA,
                    side == TradeSession.Side.B ? attempt : giveAttemptB,
                    returnAttemptA, returnAttemptB,
                    takeSaveRetriesA, takeSaveRetriesB,
                    bankTxAId, bankTxBId, cancelReason, recovered || isRecovered, sealedReason);
        }

        public Entry withReturn(TradeSession.Side side, SideStatus status, int attempt, boolean isRecovered, long nowMs) {
            return new Entry(
                    tradeId, createdAtMs, nowMs, stage,
                    playerA, nameA, playerB, nameB, aPays, bPays,
                    itemsFromA, itemsFromB, summaryFromA, summaryFromB,
                    takeA, takeB, giveA, giveB,
                    side == TradeSession.Side.A ? status : returnA,
                    side == TradeSession.Side.B ? status : returnB,
                    giveAttemptA, giveAttemptB,
                    side == TradeSession.Side.A ? attempt : returnAttemptA,
                    side == TradeSession.Side.B ? attempt : returnAttemptB,
                    takeSaveRetriesA, takeSaveRetriesB,
                    bankTxAId, bankTxBId, cancelReason, recovered || isRecovered, sealedReason);
        }

        public Entry withSealed(String reason, long nowMs) {
            return new Entry(
                    tradeId, createdAtMs, nowMs, stage,
                    playerA, nameA, playerB, nameB, aPays, bPays,
                    itemsFromA, itemsFromB, summaryFromA, summaryFromB,
                    takeA, takeB, giveA, giveB, returnA, returnB,
                    giveAttemptA, giveAttemptB, returnAttemptA, returnAttemptB,
                    takeSaveRetriesA, takeSaveRetriesB,
                    bankTxAId, bankTxBId, cancelReason, recovered, reason == null ? "" : reason);
        }

        public Entry withRecovered(long nowMs) {
            if (recovered) {
                return this;
            }
            return new Entry(
                    tradeId, createdAtMs, nowMs, stage,
                    playerA, nameA, playerB, nameB, aPays, bPays,
                    itemsFromA, itemsFromB, summaryFromA, summaryFromB,
                    takeA, takeB, giveA, giveB, returnA, returnB,
                    giveAttemptA, giveAttemptB, returnAttemptA, returnAttemptB,
                    takeSaveRetriesA, takeSaveRetriesB,
                    bankTxAId, bankTxBId, cancelReason, true, sealedReason);
        }

        private static List<byte[]> copyBytesList(List<byte[]> src) {
            if (src == null || src.isEmpty()) {
                return List.of();
            }
            List<byte[]> out = new ArrayList<>(src.size());
            for (byte[] b : src) {
                if (b == null || b.length == 0 || b.length > MAX_ITEM_BYTES) {
                    throw new IllegalArgumentException("잘못된 직렬화 아이템 바이트");
                }
                out.add(Arrays.copyOf(b, b.length));
            }
            return Collections.unmodifiableList(out);
        }
    }

    private Journal() {
    }

    /** 저널 엔트리를 매직·버전·길이·CRC32 체크섬이 붙은 바이트 배열로 직렬화합니다. */
    public static byte[] serialize(Entry e) throws IOException {
        Objects.requireNonNull(e, "entry");
        ByteArrayOutputStream payloadOut = new ByteArrayOutputStream(512);
        try (DataOutputStream out = new DataOutputStream(payloadOut)) {
            writeUuid(out, e.tradeId());
            out.writeLong(e.createdAtMs());
            out.writeLong(e.updatedAtMs());
            out.writeByte(e.stage().code());
            writeUuid(out, e.playerA());
            out.writeUTF(e.nameA());
            writeUuid(out, e.playerB());
            out.writeUTF(e.nameB());
            out.writeLong(e.aPays());
            out.writeLong(e.bPays());
            writeBytesList(out, e.itemsFromA());
            writeBytesList(out, e.itemsFromB());
            writeStringList(out, e.summaryFromA());
            writeStringList(out, e.summaryFromB());
            out.writeByte(e.takeA().code());
            out.writeByte(e.takeB().code());
            out.writeByte(e.giveA().code());
            out.writeByte(e.giveB().code());
            out.writeByte(e.returnA().code());
            out.writeByte(e.returnB().code());
            out.writeInt(e.giveAttemptA());
            out.writeInt(e.giveAttemptB());
            out.writeInt(e.returnAttemptA());
            out.writeInt(e.returnAttemptB());
            out.writeInt(e.takeSaveRetriesA());
            out.writeInt(e.takeSaveRetriesB());
            out.writeLong(e.bankTxAId());
            out.writeLong(e.bankTxBId());
            out.writeUTF(e.cancelReason());
            out.writeBoolean(e.recovered());
            out.writeUTF(e.sealedReason());
        }
        byte[] payload = payloadOut.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(payload);

        ByteArrayOutputStream frameOut = new ByteArrayOutputStream(16 + payload.length);
        try (DataOutputStream out = new DataOutputStream(frameOut)) {
            out.write(MAGIC);
            out.writeInt(FORMAT_VERSION);
            out.writeInt(payload.length);
            out.writeLong(crc.getValue());
            out.write(payload);
        }
        return frameOut.toByteArray();
    }

    /** 바이트 배열에서 매직·버전·길이·CRC32 를 모두 검증하고 저널 엔트리를 복원합니다. */
    public static Entry deserialize(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < 20) {
            throw new IOException("저널 파일이 너무 짧습니다");
        }
        try (DataInputStream frame = new DataInputStream(new ByteArrayInputStream(bytes))) {
            byte[] magic = new byte[4];
            frame.readFully(magic);
            if (!Arrays.equals(magic, MAGIC)) {
                throw new IOException("저널 매직 헤더 불일치");
            }
            int version = frame.readInt();
            if (version != FORMAT_VERSION) {
                throw new IOException("지원하지 않는 저널 버전: " + version);
            }
            int payloadLen = frame.readInt();
            if (payloadLen <= 0 || payloadLen > MAX_PAYLOAD_BYTES) {
                throw new IOException("저널 페이로드 길이 오류: " + payloadLen);
            }
            long expectedCrc = frame.readLong();
            if (bytes.length != 20 + payloadLen) {
                throw new IOException("저널 전체 길이 불일치 (기대=" + (20 + payloadLen) + ", 실제=" + bytes.length + ")");
            }
            byte[] payload = new byte[payloadLen];
            frame.readFully(payload);
            CRC32 crc = new CRC32();
            crc.update(payload);
            if (crc.getValue() != expectedCrc) {
                throw new IOException("저널 CRC32 체크섬 불일치");
            }

            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
                UUID tradeId = readUuid(in);
                long createdAtMs = in.readLong();
                long updatedAtMs = in.readLong();
                Stage stage = Stage.fromCode(in.readUnsignedByte());
                UUID playerA = readUuid(in);
                String nameA = in.readUTF();
                UUID playerB = readUuid(in);
                String nameB = in.readUTF();
                long aPays = in.readLong();
                long bPays = in.readLong();
                List<byte[]> itemsFromA = readBytesList(in);
                List<byte[]> itemsFromB = readBytesList(in);
                List<String> summaryFromA = readStringList(in);
                List<String> summaryFromB = readStringList(in);
                SideStatus takeA = SideStatus.fromCode(in.readUnsignedByte());
                SideStatus takeB = SideStatus.fromCode(in.readUnsignedByte());
                SideStatus giveA = SideStatus.fromCode(in.readUnsignedByte());
                SideStatus giveB = SideStatus.fromCode(in.readUnsignedByte());
                SideStatus returnA = SideStatus.fromCode(in.readUnsignedByte());
                SideStatus returnB = SideStatus.fromCode(in.readUnsignedByte());
                int giveAttemptA = in.readInt();
                int giveAttemptB = in.readInt();
                int returnAttemptA = in.readInt();
                int returnAttemptB = in.readInt();
                int takeSaveRetriesA = in.readInt();
                int takeSaveRetriesB = in.readInt();
                long bankTxAId = in.readLong();
                long bankTxBId = in.readLong();
                String cancelReason = in.readUTF();
                boolean recovered = in.readBoolean();
                String sealedReason = in.readUTF();
                if (in.read() != -1) {
                    throw new IOException("저널 페이로드 뒤에 남은 바이트가 있습니다");
                }
                return new Entry(
                        tradeId, createdAtMs, updatedAtMs, stage,
                        playerA, nameA, playerB, nameB, aPays, bPays,
                        itemsFromA, itemsFromB, summaryFromA, summaryFromB,
                        takeA, takeB, giveA, giveB, returnA, returnB,
                        giveAttemptA, giveAttemptB, returnAttemptA, returnAttemptB,
                        takeSaveRetriesA, takeSaveRetriesB,
                        bankTxAId, bankTxBId, cancelReason, recovered, sealedReason);
            }
        } catch (EOFException | IllegalArgumentException e) {
            throw new IOException("저널 손상: " + e.getMessage(), e);
        }
    }

    private static void writeUuid(DataOutputStream out, UUID uuid) throws IOException {
        out.writeLong(uuid.getMostSignificantBits());
        out.writeLong(uuid.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    private static void writeBytesList(DataOutputStream out, List<byte[]> list) throws IOException {
        out.writeInt(list.size());
        for (byte[] item : list) {
            out.writeInt(item.length);
            out.write(item);
        }
    }

    private static List<byte[]> readBytesList(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > OfferValidation.MAX_OFFER_SLOTS) {
            throw new IOException("저널 아이템 목록 길이 오류: " + count);
        }
        List<byte[]> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int len = in.readInt();
            if (len <= 0 || len > MAX_ITEM_BYTES) {
                throw new IOException("저널 아이템 바이트 길이 오류: " + len);
            }
            byte[] buf = new byte[len];
            in.readFully(buf);
            out.add(buf);
        }
        return out;
    }

    private static void writeStringList(DataOutputStream out, List<String> list) throws IOException {
        out.writeInt(list.size());
        for (String s : list) {
            out.writeUTF(s == null ? "" : s);
        }
    }

    private static List<String> readStringList(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > OfferValidation.MAX_OFFER_SLOTS) {
            throw new IOException("저널 요약 목록 길이 오류: " + count);
        }
        List<String> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(in.readUTF());
        }
        return out;
    }

    // ------------------------------------------------------------------ 파일 저장소 + 손상 격리 + 잠금

    public record LoadReport(List<Entry> entries, List<String> newlyQuarantined, boolean locked, String lockReason) {
    }

    public record UnlockResult(boolean unlocked, int resolvedFiles, String message) {
    }

    /**
     * {@code plugins/Trade/journal/} 디렉터리 저장소 (Bukkit 비의존).
     */
    public static final class Store {

        public static final String LOCKED_FILE = "LOCKED";
        public static final String CORRUPT_DIR = "corrupt";
        public static final String RESOLVED_SUFFIX = ".resolved";

        private final Path journalDir;
        private final Path lockedFile;
        private final Path corruptDir;

        public Store(Path dataDir) {
            this.journalDir = Objects.requireNonNull(dataDir, "dataDir").resolve("journal");
            this.lockedFile = journalDir.resolve(LOCKED_FILE);
            this.corruptDir = journalDir.resolve(CORRUPT_DIR);
        }

        public Path journalDir() {
            return journalDir;
        }

        public Path lockedFile() {
            return lockedFile;
        }

        public Path corruptDir() {
            return corruptDir;
        }

        public Path fileOf(UUID tradeId) {
            return journalDir.resolve(tradeId + ".bin");
        }

        /** 저널을 임시 파일 → fsync → 원자적 교체로 디스크에 씁니다. */
        public void write(Entry entry) throws IOException {
            byte[] bytes = Journal.serialize(entry);
            AtomicFiles.writeBytes(fileOf(entry.tradeId()), bytes);
        }

        /** 완료·취소 완료된 저널을 삭제합니다. */
        public void delete(UUID tradeId) throws IOException {
            Path file = fileOf(tradeId);
            Files.deleteIfExists(file);
            syncDir(journalDir);
        }

        /**
         * 디렉터리의 모든 {@code *.bin} 저널을 읽고, 손상된 파일이 있으면
         * <b>먼저 {@code LOCKED} 를 fsync 로 만든 뒤</b> {@code corrupt/} 로 격리합니다 (TRADE.md 6·13-1장).
         */
        public LoadReport loadAll(long nowMs) throws IOException {
            Files.createDirectories(journalDir);
            List<Entry> loaded = new ArrayList<>();
            List<String> newlyQuarantined = new ArrayList<>();

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(journalDir)) {
                for (Path file : stream) {
                    if (!Files.isRegularFile(file)) {
                        continue;
                    }
                    String name = file.getFileName().toString();
                    if (!name.endsWith(".bin")) {
                        continue;
                    }
                    try {
                        byte[] raw = Files.readAllBytes(file);
                        Entry entry = Journal.deserialize(raw);
                        String expectedName = entry.tradeId() + ".bin";
                        if (!expectedName.equals(name)) {
                            throw new IOException("파일 이름과 tradeId 불일치: " + name + " != " + expectedName);
                        }
                        loaded.add(entry);
                    } catch (IOException e) {
                        String quarantinedName = quarantineCorrupt(file, e.getMessage(), nowMs);
                        newlyQuarantined.add(quarantinedName);
                    }
                }
            }

            List<String> unresolved = unresolvedCorruptFiles();
            boolean lockFilePresent = Files.exists(lockedFile);
            boolean locked = lockFilePresent || !unresolved.isEmpty();
            String reason = buildLockReason(lockFilePresent, unresolved);
            return new LoadReport(List.copyOf(loaded), List.copyOf(newlyQuarantined), locked, reason);
        }

        /**
         * 현재 잠금 상태만 다시 검사합니다(기동·reload·거래 시작 전).
         * {@code LOCKED} 파일이 있거나 {@code corrupt/} 에 {@code .resolved} 없는 파일이 있으면 잠깁니다.
         */
        public boolean isLocked() {
            if (Files.exists(lockedFile)) {
                return true;
            }
            try {
                return !unresolvedCorruptFiles().isEmpty();
            } catch (IOException e) {
                // corrupt 폴더를 읽지 못해도 fail-closed 잠금
                return true;
            }
        }

        public String lockReason() {
            boolean lockFilePresent = Files.exists(lockedFile);
            try {
                List<String> unresolved = unresolvedCorruptFiles();
                return buildLockReason(lockFilePresent, unresolved);
            } catch (IOException e) {
                return "corrupt 디렉터리 읽기 실패: " + e.getMessage();
            }
        }

        /** {@code journal/corrupt/} 안에서 {@code <파일>.resolved} 표시가 없는 격리 파일 이름 목록. */
        public List<String> unresolvedCorruptFiles() throws IOException {
            if (!Files.isDirectory(corruptDir)) {
                return List.of();
            }
            List<String> unresolved = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(corruptDir)) {
                for (Path file : stream) {
                    if (!Files.isRegularFile(file)) {
                        continue;
                    }
                    String name = file.getFileName().toString();
                    if (name.endsWith(RESOLVED_SUFFIX) || name.endsWith(".tmp")) {
                        continue;
                    }
                    Path marker = corruptDir.resolve(name + RESOLVED_SUFFIX);
                    if (!Files.isRegularFile(marker)) {
                        unresolved.add(name);
                    }
                }
            }
            Collections.sort(unresolved);
            return List.copyOf(unresolved);
        }

        /**
         * 관리자 {@code /trade resolve unlock} 처리 (TRADE.md 6·13-2장):
         * 1) 미해결 격리 파일마다 {@code <파일>.resolved} 를 fsync 로 먼저 기록
         * 2) 미해결 파일이 0건임을 확인한 뒤에만 {@code LOCKED} 삭제
         */
        public UnlockResult unlock(String adminName, long nowMs) throws IOException {
            String admin = (adminName == null || adminName.isBlank()) ? "CONSOLE" : adminName.trim();
            List<String> unresolved = unresolvedCorruptFiles();
            int marked = 0;
            for (String fileName : unresolved) {
                Path marker = corruptDir.resolve(fileName + RESOLVED_SUFFIX);
                String content = "file=" + fileName + "\n"
                        + "admin=" + admin + "\n"
                        + "time=" + Instant.ofEpochMilli(nowMs) + "\n"
                        + "action=resolved_by_admin\n";
                AtomicFiles.write(marker, content);
                marked++;
            }
            List<String> remaining = unresolvedCorruptFiles();
            if (!remaining.isEmpty()) {
                return new UnlockResult(false, marked,
                        "아직 해결 표시가 없는 격리 파일이 남아 잠금을 해제하지 못했습니다: " + remaining);
            }
            boolean removedLock = Files.deleteIfExists(lockedFile);
            if (removedLock) {
                syncDir(journalDir);
            }
            if (marked == 0 && !removedLock) {
                return new UnlockResult(true, 0, "이미 잠금이 해제된 상태입니다.");
            }
            return new UnlockResult(true, marked,
                    "격리 파일 " + marked + "건 해결 표시(fsync) 후 Trade 전체 잠금을 해제했습니다.");
        }

        /**
         * 손상 파일을 격리합니다. 반드시 {@code LOCKED} 를 먼저 fsync 로 쓴 뒤 파일을 옮깁니다 (TRADE.md 6·13-1장).
         */
        private String quarantineCorrupt(Path corruptFile, String errorReason, long nowMs) throws IOException {
            String originalName = corruptFile.getFileName().toString();
            // 1) LOCKED 먼저 fsync (순서가 반대면 이동 직후 종료 때 잠금이 사라질 수 있음)
            String lockContent = "lockedAt=" + Instant.ofEpochMilli(nowMs) + "\n"
                    + "file=" + originalName + "\n"
                    + "reason=" + (errorReason == null ? "unknown" : errorReason) + "\n";
            AtomicFiles.write(lockedFile, lockContent);

            // 2) 그 다음 corrupt/ 로 이동
            Files.createDirectories(corruptDir);
            String targetName = originalName;
            Path target = corruptDir.resolve(targetName);
            if (Files.exists(target)) {
                targetName = originalName + "." + nowMs;
                target = corruptDir.resolve(targetName);
            }
            Files.move(corruptFile, target, StandardCopyOption.REPLACE_EXISTING);
            syncDir(corruptDir);
            syncDir(journalDir);
            return targetName;
        }

        private static String buildLockReason(boolean lockFilePresent, List<String> unresolved) {
            if (!lockFilePresent && unresolved.isEmpty()) {
                return null;
            }
            StringBuilder sb = new StringBuilder("손상 저널 감지");
            if (lockFilePresent) {
                sb.append(" (LOCKED 파일 있음)");
            }
            if (!unresolved.isEmpty()) {
                sb.append(" (미해결 격리 파일: ").append(String.join(", ", unresolved)).append(")");
            }
            return sb.toString();
        }

        private static void syncDir(Path dir) {
            try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
                ch.force(true);
            } catch (IOException | UnsupportedOperationException ignored) {
                // Windows 는 디렉터리 fsync 미지원
            }
        }
    }
}
