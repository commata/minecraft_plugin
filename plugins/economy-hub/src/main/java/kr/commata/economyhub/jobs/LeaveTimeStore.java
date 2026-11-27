package kr.commata.economyhub.jobs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.commata.common.io.AtomicFiles;

/**
 * 마지막 직업 탈퇴 시각 파일 {@code plugins/EconomyHub/job-leaves.tsv} (ECONOMY.md 5.1, 7.2).
 *
 * <p>플레이어 PDC 는 Paper 가 언제 저장할지 몰라서, VJobs 가 탈퇴를 DB 에 저장한 뒤 서버가 강제 종료되면 탈퇴 시각만 사라져
 * 쿨타임이 풀릴 수 있습니다. 탈퇴 때 바로 이 파일에 쓰고 fsync 하므로, VJobs 가 저장한 탈퇴는 항상 여기에도 있습니다.
 * 메인 스레드에서만 씁니다. Bukkit 에 의존하지 않아 단위 테스트합니다.
 */
public final class LeaveTimeStore {

    private static final String HEADER = "# EconomyHub 마지막 직업 탈퇴 시각: uuid<TAB>epoch ms. 서버가 켜져 있을 때 고치지 마세요.";
    /** 이보다 오래된 기록은 쿨타임과 상관없으니 다음 저장 때 뺌. */
    static final long KEEP_MS = 30L * 24 * 60 * 60 * 1000;

    private final Path file;
    private final Map<UUID, Long> times = new HashMap<>();

    private LeaveTimeStore(Path file) {
        this.file = file;
    }

    /** 파일이 없으면 빈 기록. 한 줄이라도 읽지 못하면 IOException. */
    public static LeaveTimeStore open(Path file) throws IOException {
        LeaveTimeStore store = new LeaveTimeStore(file);
        if (!Files.exists(file)) {
            return store;
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] cols = line.split("\t");
            try {
                if (cols.length != 2) {
                    throw new IllegalArgumentException("열 개수");
                }
                long ms = Long.parseLong(cols[1]);
                if (ms <= 0) {
                    throw new IllegalArgumentException("시각");
                }
                store.times.merge(UUID.fromString(cols[0]), ms, Math::max);
            } catch (IllegalArgumentException e) {
                throw new IOException(file + " " + (i + 1) + "번째 줄을 읽지 못함: " + line, e);
            }
        }
        return store;
    }

    /** 마지막 탈퇴 시각, 없으면 0. */
    public long get(UUID player) {
        return times.getOrDefault(player, 0L);
    }

    /** 탈퇴 시각을 쓰고 디스크에 반영될 때까지 기다림. 실패하면 메모리 값도 되돌리고 예외. */
    public void record(UUID player, long nowMs) throws IOException {
        Long before = times.put(player, Math.max(nowMs, get(player)));
        try {
            StringBuilder out = new StringBuilder(HEADER).append('\n');
            times.forEach((uuid, ms) -> {
                if (nowMs - ms < KEEP_MS) {
                    out.append(uuid).append('\t').append(ms).append('\n');
                }
            });
            AtomicFiles.write(file, out.toString());
        } catch (IOException e) {
            if (before == null) {
                times.remove(player);
            } else {
                times.put(player, before);
            }
            throw e;
        }
    }
}
