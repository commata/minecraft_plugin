package kr.commata.market.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import kr.commata.market.core.MarketStore;
import kr.commata.market.core.PlayerDatReader;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Paper 26.3 의 {@code .dat} 로드 실패 시 {@code .dat_old} 폴백으로 인한 구매품 유실·중복을 막습니다
 * (ECONOMY.md 13.6 5차 리뷰).
 *
 * <p>접속 전({@link AsyncPlayerPreLoginEvent})에 디스크의 {@code .dat}를 직접 읽어 {@code market:seq}를 기록하고,
 * 접속 직후({@link PlayerJoinEvent} LOWEST) Paper 가 실제로 불러온 PDC 의 {@code market:seq}와 비교합니다.
 * 다르면 다음 저장 전에 최신 {@code .dat}를 {@code plugins/Market/recovery/}에 복사하고 플레이어를 격리·퇴장시킵니다.
 */
public final class PlayerDataGuard implements Listener {

    private static final String KICK_MESSAGE = "플레이어 데이터를 확인 중입니다. 관리자에게 문의해 주세요.";

    private final Plugin plugin;
    private final MarketStore store;
    private final MarketService market;
    private final Path worldRoot;
    private final NamespacedKey keySeq;
    private final Logger logger;

    private final Map<UUID, PlayerDatReader.DatSnapshot> preLoginSnapshots = new ConcurrentHashMap<>();
    private final Map<UUID, String> preLoginUnreadable = new ConcurrentHashMap<>();

    public PlayerDataGuard(Plugin plugin, MarketStore store, MarketService market, Path worldRoot) {
        this.plugin = plugin;
        this.store = store;
        this.market = market;
        this.worldRoot = worldRoot;
        this.keySeq = new NamespacedKey(plugin, "seq");
        this.logger = plugin.getLogger();
    }

    /** 최근 접속 전 단계에서 .dat 읽기 실패로 차단된 플레이어 목록 (/market pending 표시용). */
    public Map<UUID, String> unreadablePlayers() {
        return Map.copyOf(preLoginUnreadable);
    }

    public void clearUnreadable(UUID uuid) {
        preLoginUnreadable.remove(uuid);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        if (store == null || store.isClosed()) {
            return;
        }
        UUID uuid = event.getUniqueId();
        try {
            var quarantine = store.quarantine(uuid);
            if (quarantine.isPresent()) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        Component.text(KICK_MESSAGE, NamedTextColor.RED));
                logger.warning("격리된 플레이어 접속 차단: " + event.getName() + " (" + uuid + ") - "
                        + quarantine.get().reason());
                return;
            }
            if (!store.hasMarketHistory(uuid)) {
                return;
            }
            PlayerDatReader.DatSnapshot snap = PlayerDatReader.read(worldRoot, uuid);
            if (snap.status() != PlayerDatReader.DatStatus.OK) {
                preLoginUnreadable.put(uuid, snap.reason());
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        Component.text(KICK_MESSAGE, NamedTextColor.RED));
                logger.severe("플레이어 .dat 읽기 실패로 접속 차단 (" + event.getName() + ", " + uuid + "): "
                        + snap.reason());
                return;
            }
            preLoginUnreadable.remove(uuid);
            preLoginSnapshots.put(uuid, snap);
        } catch (SQLException e) {
            logger.severe("접속 전 Market DB 조회 실패 (" + uuid + "): " + e.getMessage());
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    Component.text(KICK_MESSAGE, NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoinLowest(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        PlayerDatReader.DatSnapshot pre = preLoginSnapshots.remove(uuid);
        if (pre != null) {
            long loadedSeq = player.getPersistentDataContainer().getOrDefault(keySeq, PersistentDataType.LONG, 0L);
            if (loadedSeq != pre.seq()) {
                String backupName = backupDatFile(uuid, pre.file());
                String reason = "Paper 로드 .dat 불일치 (디스크 seq=" + pre.seq() + ", 로드 seq=" + loadedSeq + ")";
                try {
                    store.quarantinePlayer(uuid, reason, backupName);
                } catch (SQLException e) {
                    logger.severe("격리 DB 기록 실패, 메모리 격리 집합에 등록됨 (" + uuid + "): " + e.getMessage());
                }
                logger.severe("플레이어 .dat 불일치 감지! " + player.getName() + " (" + uuid + ") "
                        + reason + (backupName != null ? " / 백업: plugins/Market/recovery/" + backupName : ""));
                player.kick(Component.text(KICK_MESSAGE, NamedTextColor.RED));
                return;
            }
        }
        // 정상 일치: 접속 때 미완료 거래 복구 및 직업 탈퇴 이관 보정 진행
        market.onPlayerJoin(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        preLoginSnapshots.remove(uuid);
        market.onPlayerQuit(uuid);
    }

    private String backupDatFile(UUID uuid, Path source) {
        if (source == null || !Files.isRegularFile(source)) {
            return null;
        }
        try {
            Path recoveryDir = plugin.getDataFolder().toPath().resolve("recovery");
            Files.createDirectories(recoveryDir);
            String fileName = uuid + "-" + System.currentTimeMillis() + ".dat";
            Files.copy(source, recoveryDir.resolve(fileName));
            return fileName;
        } catch (IOException e) {
            logger.severe("최신 .dat 백업 복사 실패 (" + uuid + "): " + e.getMessage());
            return null;
        }
    }
}
