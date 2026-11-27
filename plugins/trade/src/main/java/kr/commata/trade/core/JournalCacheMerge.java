package kr.commata.trade.core;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 저널 재로딩 시 메모리 캐시와 디스크 스냅샷의 병합 규칙(순수 로직, Bukkit 비의존, TRADE.md 6장).
 *
 * <p>디스크에서 읽은 저널이 메모리의 보호 상태(미영속 {@code UNCERTAIN}, 봉인, 저장 확인 3회 실패 잠금)보다
 * 덜 보호적이면 디스크 값으로 덮지 않고 메모리 상태를 보존합니다.
 */
public final class JournalCacheMerge {

    private JournalCacheMerge() {
    }

    /**
     * 재로딩 병합 결과.
     *
     * @param merged                 병합 후 캐시에 반영할 저널 맵
     * @param preservedMemoryIds     디스크가 덜 보호적이어서 메모리 상태를 유지한 거래 ID 집합
     * @param remainingUnpersistedIds 디스크에 아직 반영되지 않은 보호 상태가 남은 거래 ID 집합
     */
    public record MergeResult(
            Map<UUID, Journal.Entry> merged,
            Set<UUID> preservedMemoryIds,
            Set<UUID> remainingUnpersistedIds
    ) {
        public MergeResult {
            merged = Map.copyOf(merged);
            preservedMemoryIds = Set.copyOf(preservedMemoryIds);
            remainingUnpersistedIds = Set.copyOf(remainingUnpersistedIds);
        }
    }

    /**
     * 해당 저널이 디스크 다운그레이드로부터 보호해야 하는 상태({@code UNCERTAIN}·봉인·3회 저장 실패 잠금)를
     * 하나라도 포함하는지 반환합니다.
     */
    public static boolean hasProtectiveState(Journal.Entry entry) {
        Objects.requireNonNull(entry, "entry");
        if (entry.isSealed()) {
            return true;
        }
        if (entry.giveA() == Journal.SideStatus.UNCERTAIN
                || entry.giveB() == Journal.SideStatus.UNCERTAIN
                || entry.returnA() == Journal.SideStatus.UNCERTAIN
                || entry.returnB() == Journal.SideStatus.UNCERTAIN) {
            return true;
        }
        return entry.takeSaveRetriesA() >= RecoveryPolicy.MAX_TAKE_SAVE_RETRIES
                || entry.takeSaveRetriesB() >= RecoveryPolicy.MAX_TAKE_SAVE_RETRIES;
    }

    /**
     * {@code disk} 상태가 {@code memory} 의 보호 상태보다 "덜 보호적"인지 판정합니다.
     *
     * <p>다음 중 하나라도 해당하면 {@code true} 를 반환해 디스크로 덮어쓰기를 막습니다:
     * <ul>
     *   <li>{@code disk == null} 이고 {@code memory} 에 보호 상태가 있음</li>
     *   <li>{@code memory} 는 봉인({@code isSealed})인데 {@code disk} 는 비봉인</li>
     *   <li>{@code memory} 의 어느 지급·반환 쪽이 {@code UNCERTAIN} 인데 {@code disk} 는
     *       {@code UNCERTAIN} 이 아니거나 시도 번호가 더 낮음</li>
     *   <li>{@code memory} 의 {@code takeSaveRetries} 가 잠금 기준({@value RecoveryPolicy#MAX_TAKE_SAVE_RETRIES})
     *       이상인데 {@code disk} 는 미만임</li>
     * </ul>
     */
    public static boolean isDiskLessProtective(Journal.Entry memory, Journal.Entry disk) {
        Objects.requireNonNull(memory, "memory");
        if (disk == null) {
            return hasProtectiveState(memory);
        }
        if (memory.isSealed() && !disk.isSealed()) {
            return true;
        }
        if (isSideLessProtective(memory.giveA(), memory.giveAttemptA(), disk.giveA(), disk.giveAttemptA())) {
            return true;
        }
        if (isSideLessProtective(memory.giveB(), memory.giveAttemptB(), disk.giveB(), disk.giveAttemptB())) {
            return true;
        }
        if (isSideLessProtective(memory.returnA(), memory.returnAttemptA(), disk.returnA(), disk.returnAttemptA())) {
            return true;
        }
        if (isSideLessProtective(memory.returnB(), memory.returnAttemptB(), disk.returnB(), disk.returnAttemptB())) {
            return true;
        }
        if (memory.takeSaveRetriesA() >= RecoveryPolicy.MAX_TAKE_SAVE_RETRIES
                && disk.takeSaveRetriesA() < RecoveryPolicy.MAX_TAKE_SAVE_RETRIES) {
            return true;
        }
        return memory.takeSaveRetriesB() >= RecoveryPolicy.MAX_TAKE_SAVE_RETRIES
                && disk.takeSaveRetriesB() < RecoveryPolicy.MAX_TAKE_SAVE_RETRIES;
    }

    /**
     * 메모리 캐시와 디스크 로드 목록을 병합합니다.
     *
     * <p>메모리의 미영속 보호 상태(또는 메모리 캐시에 존재하는 보호 상태)보다 디스크가 덜 보호적이면
     * 메모리 상태를 유지하고 {@link MergeResult#preservedMemoryIds()} 와
     * {@link MergeResult#remainingUnpersistedIds()} 에 기록합니다.
     */
    public static MergeResult merge(
            Map<UUID, Journal.Entry> memory,
            List<Journal.Entry> disk,
            Set<UUID> unpersistedProtectiveIds
    ) {
        Map<UUID, Journal.Entry> memMap = memory == null ? Map.of() : memory;
        List<Journal.Entry> diskList = disk == null ? List.of() : disk;
        Set<UUID> unpersisted = unpersistedProtectiveIds == null ? Set.of() : unpersistedProtectiveIds;

        Map<UUID, Journal.Entry> diskById = new HashMap<>(diskList.size());
        for (Journal.Entry e : diskList) {
            diskById.put(e.tradeId(), e);
        }

        Map<UUID, Journal.Entry> merged = new LinkedHashMap<>();
        Set<UUID> preserved = new LinkedHashSet<>();
        Set<UUID> remainingUnpersisted = new LinkedHashSet<>();

        // 1) 디스크에 존재하는 항목 병합
        for (Journal.Entry diskEntry : diskList) {
            UUID tradeId = diskEntry.tradeId();
            Journal.Entry memEntry = memMap.get(tradeId);
            boolean memProtected = memEntry != null
                    && (unpersisted.contains(tradeId) || hasProtectiveState(memEntry));
            if (memProtected && isDiskLessProtective(memEntry, diskEntry)) {
                merged.put(tradeId, memEntry);
                preserved.add(tradeId);
                remainingUnpersisted.add(tradeId);
            } else {
                merged.put(tradeId, diskEntry);
            }
        }

        // 2) 디스크에 없는 메모리 항목 중 보호 상태(또는 미영속 표시)가 있으면 보존
        for (Map.Entry<UUID, Journal.Entry> e : memMap.entrySet()) {
            UUID tradeId = e.getKey();
            if (diskById.containsKey(tradeId)) {
                continue;
            }
            Journal.Entry memEntry = e.getValue();
            if (unpersisted.contains(tradeId) || hasProtectiveState(memEntry)) {
                merged.put(tradeId, memEntry);
                preserved.add(tradeId);
                remainingUnpersisted.add(tradeId);
            }
        }

        return new MergeResult(merged, preserved, remainingUnpersisted);
    }

    private static boolean isSideLessProtective(
            Journal.SideStatus memStatus, int memAttempt,
            Journal.SideStatus diskStatus, int diskAttempt
    ) {
        if (memStatus != Journal.SideStatus.UNCERTAIN) {
            return false;
        }
        if (diskStatus != Journal.SideStatus.UNCERTAIN) {
            return true;
        }
        return diskAttempt < memAttempt;
    }
}
