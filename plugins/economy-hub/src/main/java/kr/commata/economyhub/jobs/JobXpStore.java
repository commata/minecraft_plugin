package kr.commata.economyhub.jobs;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 직업 XP 복구 기록 파일 {@code plugins/EconomyHub/job-xp.tsv} (ECONOMY.md 7.2).
 *
 * <p>플레이어 데이터(.dat)는 Paper 가 언제 저장할지 알 수 없어서, VJobs DB 와 저장 순서가 엇갈리면 기록이 먼저 지워질 수 있습니다.
 * 그래서 바뀔 때마다 이 파일을 임시 파일에 쓰고 fsync 한 뒤 바꿔치기합니다. 쓰기에 실패하면 메모리 값도 되돌리고 예외를 던집니다.
 * 메인 스레드에서만 씁니다. Bukkit 에 의존하지 않아 단위 테스트합니다.
 */
public final class JobXpStore {

    /** 한 줄 = 한 기록. 기록을 바꿀 때마다 파일 전체를 다시 씀. 기록은 탈퇴한 직업 수만큼만 생겨서 작음. */
    public record Entry(UUID player, String job, SavedJobXp saved) { }

    private static final String HEADER = "# EconomyHub 직업 XP 복구 기록: uuid<TAB>직업<TAB>레벨:XP[:r]. 서버가 켜져 있을 때 고치지 마세요.";

    private final Path file;
    private final Map<UUID, Map<String, SavedJobXp>> entries = new LinkedHashMap<>();

    private JobXpStore(Path file) {
        this.file = file;
    }

    /** 파일이 없으면 빈 기록. 한 줄이라도 읽지 못하면 IOException (파일을 덮어쓰지 않도록 기능을 끔). */
    public static JobXpStore open(Path file) throws IOException {
        JobXpStore store = new JobXpStore(file);
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
            SavedJobXp saved = cols.length == 3 ? SavedJobXp.decode(cols[2]) : null;
            UUID uuid;
            try {
                uuid = cols.length == 3 ? UUID.fromString(cols[0]) : null;
            } catch (IllegalArgumentException e) {
                uuid = null;
            }
            if (uuid == null || saved == null || cols[1].isEmpty()) {
                throw new IOException(file + " " + (i + 1) + "번째 줄을 읽지 못함: " + line);
            }
            store.entries.computeIfAbsent(uuid, k -> new LinkedHashMap<>()).put(normalize(cols[1]), saved);
        }
        return store;
    }

    /** VJobs 는 직업 ID 를 소문자로 다룸(VJobsApiImpl.giveXp). 탭·줄바꿈은 파일 형식을 깨므로 바꿈. */
    public static String normalize(String job) {
        return job.toLowerCase(Locale.ROOT).replaceAll("[\\t\\r\\n]", "_");
    }

    public SavedJobXp get(UUID player, String job) {
        Map<String, SavedJobXp> jobs = entries.get(player);
        return jobs == null ? null : jobs.get(normalize(job));
    }

    /** 이 플레이어의 기록 복사본. */
    public Map<String, SavedJobXp> jobs(UUID player) {
        Map<String, SavedJobXp> jobs = entries.get(player);
        return jobs == null ? Map.of() : Map.copyOf(jobs);
    }

    public List<Entry> snapshot() {
        List<Entry> list = new ArrayList<>();
        entries.forEach((uuid, jobs) -> jobs.forEach((job, saved) -> list.add(new Entry(uuid, job, saved))));
        return list;
    }

    public int size() {
        return entries.values().stream().mapToInt(Map::size).sum();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** 기록을 쓰고 디스크에 반영될 때까지 기다림. 실패하면 이전 상태로 되돌리고 예외. */
    public void put(UUID player, String job, SavedJobXp saved) throws IOException {
        String key = normalize(job);
        Map<String, SavedJobXp> jobs = entries.computeIfAbsent(player, k -> new LinkedHashMap<>());
        SavedJobXp before = jobs.put(key, saved);
        try {
            persist();
        } catch (IOException e) {
            restore(player, key, before);
            throw e;
        }
    }

    /** 기록이 있으면 지우고 디스크에 반영. 없으면 아무것도 안 함. */
    public void remove(UUID player, String job) throws IOException {
        removeIf(player, job, null);
    }

    /**
     * 기록이 {@code expected} 와 같을 때만 지움 (null 이면 무조건). 비동기 확인 중에 플레이어가 다시 탈퇴해 기록이 바뀌었으면 남김.
     *
     * @return 지웠으면 true
     */
    public boolean removeIf(UUID player, String job, SavedJobXp expected) throws IOException {
        String key = normalize(job);
        Map<String, SavedJobXp> jobs = entries.get(player);
        SavedJobXp before = jobs == null ? null : jobs.get(key);
        if (before == null || (expected != null && !before.equals(expected))) {
            return false;
        }
        jobs.remove(key);
        if (jobs.isEmpty()) {
            entries.remove(player);
        }
        try {
            persist();
        } catch (IOException e) {
            restore(player, key, before);
            throw e;
        }
        return true;
    }

    private void restore(UUID player, String key, SavedJobXp before) {
        Map<String, SavedJobXp> jobs = entries.computeIfAbsent(player, k -> new LinkedHashMap<>());
        if (before == null) {
            jobs.remove(key);
        } else {
            jobs.put(key, before);
        }
        if (jobs.isEmpty()) {
            entries.remove(player);
        }
    }

    private void persist() throws IOException {
        StringBuilder out = new StringBuilder(HEADER).append('\n');
        entries.forEach((uuid, jobs) -> jobs.forEach((job, saved) ->
                out.append(uuid).append('\t').append(job).append('\t').append(saved.encode()).append('\n')));
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = dir.resolve(file.getFileName() + ".tmp");
        try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.wrap(out.toString().getBytes(StandardCharsets.UTF_8));
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        try (FileChannel dirChannel = FileChannel.open(dir, StandardOpenOption.READ)) {
            dirChannel.force(true); // Linux: 파일 이름 바꾼 것까지 디스크에 반영
        } catch (IOException | UnsupportedOperationException e) {
            // Windows 는 폴더를 열 수 없음. 파일 내용은 이미 fsync 됨
        }
    }
}
