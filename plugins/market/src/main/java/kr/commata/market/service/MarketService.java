package kr.commata.market.service;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import kr.commata.common.gui.Menu;
import kr.commata.market.MarketConfig;
import kr.commata.market.Msg;
import kr.commata.market.bridge.BankBridge;
import kr.commata.market.bridge.VJobsBridge;
import kr.commata.market.core.AppliedTxTokens;
import kr.commata.market.core.GachaCatalog;
import kr.commata.market.core.MarketPeriods;
import kr.commata.market.core.MarketStore;
import kr.commata.market.core.OrderPayload;
import kr.commata.market.core.PlayerDatReader;
import kr.commata.market.core.RareCatalog;
import kr.commata.market.core.RefundPolicy;
import kr.commata.market.core.SellCatalog;
import kr.commata.market.core.SellMath;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * 희귀 상점·인첸트 책 뽑기·직업별 서버 매입의 거래 실행과 장애 복구 유스케이스 (ECONOMY.md 13.6, 14.3).
 *
 * <p>모든 거래(예약 → 출금/아이템 제거 → PDC 기록 → {@code saveData()} → 디스크 직접 확인 → 확정/회수/보류)는
 * 메인 스레드 한 틱 안에서 끝내며, 같은 플레이어의 동시 거래와 미완료 거래 중 추가 거래를 모두 막습니다.
 */
public final class MarketService {

    /** 연속 읽기/조회 실패가 이 횟수에 도달하면 콘솔 경고를 남김 (ECONOMY.md 13.6). */
    public static final int ALERT_THRESHOLD = 10;
    /** 접속 뒤 VJobs 로딩을 기다리는 탈퇴 이관 보정 재시도 횟수 (1초 간격). */
    private static final int JOIN_RECONCILE_TRIES = 30;

    private final Plugin plugin;
    private final MarketStore store;
    private final String storeLockReason;
    private final BankBridge bank;
    private final VJobsBridge vjobs;
    private final Path worldRoot;
    private final Logger logger;
    private final NamespacedKey keyAppliedTx;
    private final NamespacedKey keySeq;
    private final SecureRandom random = new SecureRandom();

    private volatile MarketConfig config;

    /** 한 틱 안 재진입 방지. */
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    /** 이번 기동에서 저장 미확인으로 같은 틱 회수 후 지급 대기 중인 주문. */
    private final Set<String> inProcessChargedOrders = ConcurrentHashMap.newKeySet();
    /** 이번 기동에서 저장 미확인으로 아이템 제거 상태 보류 중인 판매. */
    private final Set<String> inProcessPendingSales = ConcurrentHashMap.newKeySet();
    /** 플레이어별 판매 가능량 버퍼를 DB 에 즉시 반영하는 콜백 (QuotaTracker 연결). */
    private volatile java.util.function.Predicate<UUID> quotaFlusher = uuid -> true;

    public MarketService(Plugin plugin, MarketStore store, String storeLockReason,
                         BankBridge bank, VJobsBridge vjobs, Path worldRoot, MarketConfig config) {
        this.plugin = plugin;
        this.store = store;
        this.storeLockReason = storeLockReason;
        this.bank = bank;
        this.vjobs = vjobs;
        this.worldRoot = worldRoot;
        this.config = config;
        this.logger = plugin.getLogger();
        this.keyAppliedTx = new NamespacedKey(plugin, "applied_tx");
        this.keySeq = new NamespacedKey(plugin, "seq");
    }

    public void updateConfig(MarketConfig newConfig) {
        this.config = newConfig;
    }

    public void setQuotaFlusher(java.util.function.Predicate<UUID> quotaFlusher) {
        this.quotaFlusher = quotaFlusher == null ? uuid -> true : quotaFlusher;
    }

    /** 해당 플레이어의 메모리 판매 가능량 버퍼를 DB 에 먼저 반영합니다. 실패하면 false. */
    public boolean flushQuota(UUID uuid) {
        return quotaFlusher.test(uuid);
    }

    public MarketConfig config() {
        return config;
    }

    public MarketStore store() {
        return store;
    }

    public BankBridge bank() {
        return bank;
    }

    public VJobsBridge vjobs() {
        return vjobs;
    }

    public boolean dbLocked() {
        return store == null || store.isClosed();
    }

    public String dbLockReason() {
        if (storeLockReason != null) {
            return storeLockReason;
        }
        return store == null ? "DB 없음" : store.brokenReason();
    }

    public boolean canTransact() {
        return !dbLocked() && bank.available();
    }

    public String currentWeekKey() {
        return MarketPeriods.weekKey(System.currentTimeMillis(), config.zone());
    }

    public String currentDayKey() {
        return MarketPeriods.dayKey(System.currentTimeMillis(), config.zone());
    }

    public boolean isPlayerLocked(UUID uuid) {
        if (inFlight.contains(uuid)) {
            return true;
        }
        if (dbLocked()) {
            return true;
        }
        try {
            return store.isPlayerLocked(uuid);
        } catch (SQLException e) {
            return true;
        }
    }

    public boolean hasClaimableOrder(UUID uuid) {
        if (dbLocked()) {
            return false;
        }
        try {
            for (MarketStore.OrderRecord o : store.openOrdersOf(uuid)) {
                if (MarketStore.ORDER_CHARGED.equals(o.status())) {
                    return true;
                }
            }
            return false;
        } catch (SQLException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 희귀 상점 구매 (ECONOMY.md 13.6)

    public boolean buyRare(Player player, String itemId) {
        MarketConfig cfg = this.config;
        if (!ensureReady(player, cfg.rare().available(), cfg.rare().lockReason())) {
            return false;
        }
        RareCatalog.Entry entry = cfg.rare().get(itemId);
        if (entry == null) {
            Msg.error(player, "판매하지 않는 품목입니다.");
            return false;
        }
        UUID uuid = player.getUniqueId();
        if (!inFlight.add(uuid)) {
            Msg.warn(player, "이미 거래를 처리 중입니다.");
            return false;
        }
        try {
            if (store.isPlayerLocked(uuid)) {
                notifyPlayerLocked(player);
                return false;
            }
            if (ItemFactory.emptySlots(player) < 1) {
                Msg.error(player, "인벤토리에 빈칸이 최소 1칸 필요합니다.");
                return false;
            }
            String weekKey = currentWeekKey();
            int used = store.weeklyUsed(uuid, weekKey, entry.limitKey());
            if (used + 1 > entry.weeklyLimit()) {
                Msg.error(player, "이번 주 구매 한도를 모두 사용했습니다.");
                return false;
            }
            long balance = bank.balance(player);
            if (balance >= 0 && balance < entry.price()) {
                Msg.error(player, "잔액이 부족합니다. 필요 금액: " + MarketPeriods.won(entry.price()));
                return false;
            }

            String payload = entry.kind() == RareCatalog.Kind.RESOURCE
                    ? OrderPayload.encodeRareResource(entry)
                    : OrderPayload.encodeRareBook(entry);
            String txId = UUID.randomUUID().toString();

            MarketStore.ReserveOrderResult reserved = store.reserveRarePurchase(txId, uuid, entry, payload, weekKey);
            if (!reserved.ok()) {
                handleReserveFailure(player, reserved.failure());
                return false;
            }

            String context = "희귀상점 " + entry.displayName();
            BankBridge.TxResult charge = bank.withdraw(uuid, entry.price(), txId, context);
            if (charge.status() == BankBridge.TxStatus.REJECTED) {
                store.abortOrder(txId, "Bank 출금 거절");
                Msg.error(player, "결제에 실패했습니다. 잔액을 확인해 주세요.");
                return false;
            }
            if (charge.status() == BankBridge.TxStatus.UNKNOWN) {
                Msg.warn(player, "결제 결과를 확인 중입니다. 1분 안에 자동으로 반영됩니다.");
                return false;
            }

            store.markOrderCharged(txId, charge.bankTxId());
            MarketStore.OrderRecord charged = store.findOrder(txId).orElse(reserved.order());
            boolean delivered = deliverOrderInTick(player, charged);
            if (delivered) {
                Msg.success(player, entry.displayName() + "을(를) " + MarketPeriods.won(entry.price()) + "에 구매했습니다.");
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
            }
            return delivered;
        } catch (SQLException e) {
            logger.severe("희귀 상점 구매 오류 (" + player.getName() + "): " + e.getMessage());
            Msg.error(player, "저장 오류로 구매를 처리하지 못했습니다.");
            return false;
        } finally {
            inFlight.remove(uuid);
        }
    }

    // ------------------------------------------------------------------ 인첸트 책 뽑기 (ECONOMY.md 13.5, 13.6)

    public boolean pullGacha(Player player, int count) {
        if (count != 1 && count != 10) {
            return false;
        }
        MarketConfig cfg = this.config;
        if (!ensureReady(player, cfg.gacha().available(), cfg.gacha().lockReason())) {
            return false;
        }
        UUID uuid = player.getUniqueId();
        if (!inFlight.add(uuid)) {
            Msg.warn(player, "이미 거래를 처리 중입니다.");
            return false;
        }
        try {
            if (store.isPlayerLocked(uuid)) {
                notifyPlayerLocked(player);
                return false;
            }
            if (ItemFactory.emptySlots(player) < count) {
                Msg.error(player, "인벤토리에 빈칸이 최소 " + count + "칸 필요합니다.");
                return false;
            }
            GachaCatalog gacha = cfg.gacha();
            String dayKey = currentDayKey();
            int used = store.dailyPullsUsed(uuid, dayKey);
            if (used + count > gacha.dailyLimit()) {
                int remain = Math.max(0, gacha.dailyLimit() - used);
                Msg.error(player, "오늘 뽑기 한도가 부족합니다 (남은 횟수: " + remain + "/" + gacha.dailyLimit() + "회).");
                return false;
            }
            long totalPrice = gacha.price() * count;
            long balance = bank.balance(player);
            if (balance >= 0 && balance < totalPrice) {
                Msg.error(player, "잔액이 부족합니다. 필요 금액: " + MarketPeriods.won(totalPrice));
                return false;
            }

            // 2단계: 서버에서 결과 확정 (SecureRandom, 천장 반영)
            int pityBefore = store.pity(uuid);
            List<GachaCatalog.Draw> draws = gacha.roll(count, pityBefore, random);
            int pityAfter = draws.get(draws.size() - 1).pityAfter();
            String payload = OrderPayload.encodeGacha(draws, gacha.price());
            String txId = UUID.randomUUID().toString();

            // 3단계: 선기록 + 한도·천장 예약
            MarketStore.ReserveOrderResult reserved = store.reserveGachaPull(
                    txId, uuid, count, gacha.dailyLimit(), totalPrice, pityBefore, pityAfter, payload, dayKey);
            if (!reserved.ok()) {
                handleReserveFailure(player, reserved.failure());
                return false;
            }

            // 4단계: Bank 출금
            BankBridge.TxResult charge = bank.withdraw(uuid, totalPrice, txId, "인첸트 뽑기 " + count + "회");
            if (charge.status() == BankBridge.TxStatus.REJECTED) {
                store.abortOrder(txId, "Bank 출금 거절");
                Msg.error(player, "결제에 실패했습니다. 잔액을 확인해 주세요.");
                return false;
            }
            if (charge.status() == BankBridge.TxStatus.UNKNOWN) {
                Msg.warn(player, "결제 결과를 확인 중입니다. 1분 안에 자동으로 반영됩니다.");
                return false;
            }

            // 5단계: 지급 + PDC + saveData + 디스크 확인
            store.markOrderCharged(txId, charge.bankTxId());
            MarketStore.OrderRecord charged = store.findOrder(txId).orElse(reserved.order());
            boolean delivered = deliverOrderInTick(player, charged);
            if (delivered) {
                announceGachaDraws(player, draws, pityAfter, gacha.pityThreshold());
            }
            return delivered;
        } catch (SQLException e) {
            logger.severe("인첸트 뽑기 오류 (" + player.getName() + "): " + e.getMessage());
            Msg.error(player, "저장 오류로 뽑기를 처리하지 못했습니다.");
            return false;
        } finally {
            inFlight.remove(uuid);
        }
    }

    private void announceGachaDraws(Player player, List<GachaCatalog.Draw> draws, int pityAfter, int threshold) {
        boolean gotLegendary = false;
        if (draws.size() == 1) {
            GachaCatalog.Draw d = draws.get(0);
            gotLegendary = d.pityAfter() == 0;
            String pityTag = d.pityTriggered() ? " <#F5C542>[천장 확정!]" : "";
            Msg.raw(player, "<#F5C542>[뽑기] <" + d.tierColor() + ">[" + d.tierName() + "] <white>"
                    + d.bookName() + pityTag + " <#AAAAAA>(천장 " + pityAfter + "/" + threshold + ")");
        } else {
            Msg.raw(player, "<#F5C542>[뽑기 10회 결과] <#AAAAAA>(천장 " + pityAfter + "/" + threshold + ")");
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < draws.size(); i++) {
                GachaCatalog.Draw d = draws.get(i);
                if (d.pityAfter() == 0) {
                    gotLegendary = true;
                }
                if (i > 0) {
                    line.append("<#AAAAAA>, ");
                }
                line.append('<').append(d.tierColor()).append(">[").append(d.tierName()).append("] ")
                        .append(d.bookName());
                if (d.pityTriggered()) {
                    line.append("★");
                }
            }
            Msg.raw(player, line.toString());
        }
        player.playSound(player.getLocation(),
                gotLegendary ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_EXPERIENCE_ORB_PICKUP,
                0.9f, gotLegendary ? 1.0f : 1.3f);
    }

    // ------------------------------------------------------------------ 직업별 서버 매입 (ECONOMY.md 14.3)

    public boolean sellItem(Player player, String itemId, boolean sellMax) {
        MarketConfig cfg = this.config;
        if (!ensureReady(player, cfg.sell().available(), cfg.sell().lockReason())) {
            return false;
        }
        SellCatalog.Item item = cfg.sell().item(itemId);
        if (item == null) {
            Msg.error(player, "매입하지 않는 품목입니다.");
            return false;
        }
        Material material = Material.matchMaterial(item.id());
        if (material == null) {
            Msg.error(player, "아이템 재질 오류: " + item.id());
            return false;
        }
        UUID uuid = player.getUniqueId();
        if (!inFlight.add(uuid)) {
            Msg.warn(player, "이미 거래를 처리 중입니다.");
            return false;
        }
        try {
            if (store.isPlayerLocked(uuid)) {
                notifyPlayerLocked(player);
                return false;
            }
            // 판매 시작 전 메모리 버퍼의 판매 가능량을 먼저 DB 에 반영 (ECONOMY.md 14.2)
            if (!flushQuota(uuid)) {
                Msg.error(player, "판매 가능량 저장에 실패했습니다. 잠시 후 다시 시도해 주세요.");
                return false;
            }
            // VJobs 가 켜져 있는데 직업 정보를 아직 못 읽었으면 판매하지 않음 (보너스가 빠진 기본가로 팔리는 것 방지)
            if (jobsLoading(uuid)) {
                Msg.warn(player, "직업 정보를 불러오는 중입니다. 잠시 후 다시 시도해 주세요.");
                return false;
            }
            // 판매 직전 탈퇴 이관 보정 (ECONOMY.md 14.2)
            reconcileJobQuotas(player);

            String dayKey = currentDayKey();
            long dailyLimit = cfg.sell().dailyLimit();
            long todayTotal = store.dailySalesTotal(uuid, dayKey);
            long remainingLimit = Math.max(0L, dailyLimit - todayTotal);
            if (remainingLimit < item.basePrice()) {
                Msg.error(player, "오늘 판매 한도를 모두 사용했습니다 (" + MarketPeriods.won(todayTotal)
                        + " / " + MarketPeriods.won(dailyLimit) + ").");
                return false;
            }

            int invCount = ItemFactory.countSellable(player, material);
            if (invCount < item.unit()) {
                Msg.error(player, "판매할 " + item.displayName() + "이(가) 부족합니다 (단위: " + item.unit() + "개).");
                return false;
            }

            MarketStore.Quota quota = store.quota(uuid, item.id());
            Set<String> joinedJobs = vjobs.joinedJobIds(uuid);
            boolean hasJob = joinedJobs != null && joinedJobs.contains(item.jobId());
            long effectiveJobQuota = hasJob ? quota.jobAmount() : 0L;

            SellMath.Plan plan = SellMath.calculate(
                    invCount, effectiveJobQuota, quota.baseAmount(),
                    item.unit(), item.basePrice(), item.bonusPrice(),
                    remainingLimit, sellMax ? Integer.MAX_VALUE : 1);

            if (plan.isEmpty()) {
                if (effectiveJobQuota < item.unit() && quota.baseAmount() < item.unit()) {
                    Msg.error(player, "직접 채집·획득한 판매 가능량이 부족합니다 (보너스 " + effectiveJobQuota
                            + "개 / 기본 " + quota.baseAmount() + "개, 단위 " + item.unit() + "개).");
                } else {
                    Msg.error(player, "오늘 남은 판매 한도(" + MarketPeriods.won(remainingLimit) + ")로 판매할 수 없습니다.");
                }
                return false;
            }

            String txId = UUID.randomUUID().toString();
            MarketStore.ReserveSaleResult reserved = store.reserveSale(txId, uuid, item, plan, dailyLimit, dayKey);
            if (!reserved.ok()) {
                handleReserveFailure(player, reserved.failure());
                return false;
            }

            // 5단계: 인벤토리 제거 + PDC 기록 + saveData + 디스크 확인
            PersistentDataContainer pdc = player.getPersistentDataContainer();
            String prevApplied = pdc.getOrDefault(keyAppliedTx, PersistentDataType.STRING, "");
            long prevSeq = pdc.getOrDefault(keySeq, PersistentDataType.LONG, 0L);
            long dbSeq = store.playerSeq(uuid);
            long nextSeq = Math.max(prevSeq, dbSeq) + 1L;
            String token = AppliedTxTokens.token(txId, 1);
            String newApplied = AppliedTxTokens.append(prevApplied, token);
            ItemStack[] invSnapshot = ItemFactory.snapshotStorage(player.getInventory());

            if (!ItemFactory.removeSellable(player.getInventory(), material, plan.totalAmount())) {
                store.abortSale(txId, "인벤토리 제거 실패");
                Msg.error(player, "인벤토리 수량이 변경되어 판매를 취소했습니다.");
                return false;
            }

            try {
                pdc.set(keyAppliedTx, PersistentDataType.STRING, newApplied);
                pdc.set(keySeq, PersistentDataType.LONG, nextSeq);
                player.saveData();
            } catch (Throwable e) {
                // 인벤토리 제거 후 저장 중 예외 발생 시 같은 틱 안에서 인벤토리 및 PDC 복원, 판매 취소
                player.getInventory().setStorageContents(invSnapshot);
                if (prevApplied.isEmpty()) {
                    pdc.remove(keyAppliedTx);
                } else {
                    pdc.set(keyAppliedTx, PersistentDataType.STRING, prevApplied);
                }
                if (prevSeq == 0L) {
                    pdc.remove(keySeq);
                } else {
                    pdc.set(keySeq, PersistentDataType.LONG, prevSeq);
                }
                store.abortSale(txId, "저장 중 오류로 판매 취소: " + e.getMessage());
                logger.severe("판매 처리 중 예외 발생, 인벤토리 복구 완료 (" + txId + "): " + e);
                Msg.error(player, "판매 저장 중 오류가 발생하여 아이템을 복구했습니다.");
                return false;
            }

            PlayerDatReader.MarkerCheck check = PlayerDatReader.checkExact(worldRoot, uuid, txId, 1);
            if (check.status() != PlayerDatReader.MarkerStatus.FOUND) {
                // ECONOMY.md 14.3 5번: 아이템을 돌려주지 않고 보류 (메모리는 아이템 빠짐 + 표시 있음 그대로)
                inProcessPendingSales.add(txId);
                store.incrementSaleUnreadable(txId,
                        check.reason() != null ? check.reason() : "저장 확인 미검출(ABSENT)");
                Msg.warn(player, "판매 처리 중(저장 확인 대기)입니다. 잠시 후 자동으로 입금됩니다.");
                return false;
            }

            store.savePlayerSeq(uuid, nextSeq);
            store.markSaleItemsTaken(txId);

            // 6단계: Bank 입금 (extRef = txId)
            String context = "매입 " + item.displayName() + " x" + plan.totalAmount();
            BankBridge.TxResult dep = bank.deposit(uuid, plan.totalMoney(), txId, context);
            if (dep.status() == BankBridge.TxStatus.OK) {
                store.markSaleDone(txId, dep.bankTxId());
                String detail = formatSaleBreakdown(plan);
                Msg.success(player, item.displayName() + " " + plan.totalAmount() + "개를 "
                        + MarketPeriods.won(plan.totalMoney()) + "에 판매했습니다." + detail);
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.3f);
                return true;
            }

            // 입금 실패/불확실: ITEMS_TAKEN 유지, 1분 타이머가 같은 txId 로 재입금
            Msg.warn(player, "아이템은 정상 처리됐으며 대금(" + MarketPeriods.won(plan.totalMoney())
                    + ") 입금을 대기 중입니다. 잠시 후 자동 입금됩니다.");
            return false;
        } catch (SQLException e) {
            logger.severe("매입 처리 오류 (" + player.getName() + "): " + e.getMessage());
            Msg.error(player, "저장 오류로 판매를 처리하지 못했습니다.");
            return false;
        } finally {
            inFlight.remove(uuid);
        }
    }

    private static String formatSaleBreakdown(SellMath.Plan plan) {
        if (plan.bonusAmount() > 0 && plan.baseAmount() > 0) {
            return " <#AAAAAA>(보너스 " + plan.bonusAmount() + "개 " + MarketPeriods.won(plan.bonusTotal())
                    + " + 기본 " + plan.baseAmount() + "개 " + MarketPeriods.won(plan.baseTotal()) + ")";
        }
        if (plan.bonusAmount() > 0) {
            return " <#55FF55>(직업 보너스 적용)";
        }
        return "";
    }

    // ------------------------------------------------------------------ 5단계 지급 + 디스크 확인 + 같은 틱 회수

    /**
     * {@code CHARGED} 주문을 한 틱 안에서 지급 → PDC 표시 → {@code saveData()} → 디스크 확인합니다.
     * {@code FOUND}가 아니면 같은 틱 안에서 방금 지급한 아이템과 PDC 표시를 정확히 회수하고 {@code CHARGED}로 둡니다.
     */
    private boolean deliverOrderInTick(Player player, MarketStore.OrderRecord order) throws SQLException {
        UUID uuid = player.getUniqueId();
        List<ItemStack> items;
        try {
            items = ItemFactory.itemsForOrder(order.kind(), order.payload());
        } catch (RuntimeException e) {
            logger.severe("주문 아이템 생성 실패 (" + order.txId() + "): " + e.getMessage());
            store.incrementOrderUnreadable(order.txId(), "아이템 생성 실패: " + e.getMessage());
            Msg.error(player, "주문 아이템을 만들지 못했습니다. 관리자에게 문의해 주세요.");
            return false;
        }

        if (ItemFactory.emptySlots(player) < items.size()) {
            inProcessChargedOrders.add(order.txId());
            Msg.warn(player, "인벤토리 빈칸이 부족합니다(필요 " + items.size()
                    + "칸). 빈칸을 비운 뒤 <#FFDF6B>/market claim<#AAAAAA> 으로 받아 주세요.");
            return false;
        }

        ItemStack[] invSnapshot = ItemFactory.snapshotStorage(player.getInventory());
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        String prevApplied = pdc.getOrDefault(keyAppliedTx, PersistentDataType.STRING, "");
        long prevSeq = pdc.getOrDefault(keySeq, PersistentDataType.LONG, 0L);
        long dbSeq = store.playerSeq(uuid);
        long nextSeq = Math.max(prevSeq, dbSeq) + 1L;
        int attempt = order.attempt();
        String token = AppliedTxTokens.token(order.txId(), attempt);
        String newApplied = AppliedTxTokens.append(prevApplied, token);

        if (!ItemFactory.giveIntoEmptySlots(player.getInventory(), items)) {
            inProcessChargedOrders.add(order.txId());
            Msg.warn(player, "인벤토리 빈칸이 부족합니다. 빈칸을 비운 뒤 <#FFDF6B>/market claim<#AAAAAA> 으로 받아 주세요.");
            return false;
        }

        try {
            pdc.set(keyAppliedTx, PersistentDataType.STRING, newApplied);
            pdc.set(keySeq, PersistentDataType.LONG, nextSeq);
            player.saveData();
        } catch (Throwable e) {
            // 인벤토리 지급 후 저장 중 예외 발생 시 같은 틱 안에서 아이템 회수 및 PDC 복원
            player.getInventory().setStorageContents(invSnapshot);
            if (prevApplied.isEmpty()) {
                pdc.remove(keyAppliedTx);
            } else {
                pdc.set(keyAppliedTx, PersistentDataType.STRING, prevApplied);
            }
            if (prevSeq == 0L) {
                pdc.remove(keySeq);
            } else {
                pdc.set(keySeq, PersistentDataType.LONG, prevSeq);
            }
            inProcessChargedOrders.add(order.txId());
            logger.severe("주문 지급 중 예외 발생, 아이템 회수 완료 (" + order.txId() + "): " + e);
            Msg.error(player, "구매품 저장 중 오류가 발생하여 지급을 보류했습니다. /market claim 으로 다시 받아 주세요.");
            return false;
        }

        PlayerDatReader.MarkerCheck check = PlayerDatReader.checkExact(worldRoot, uuid, order.txId(), attempt);
        if (check.status() == PlayerDatReader.MarkerStatus.FOUND) {
            store.savePlayerSeq(uuid, nextSeq);
            store.markOrderDelivered(order.txId());
            inProcessChargedOrders.remove(order.txId());
            return true;
        }

        // ECONOMY.md 13.6: FOUND 가 아니면 같은 틱 안에서 방금 지급한 아이템과 PDC 표시를 회수
        player.getInventory().setStorageContents(invSnapshot);
        if (prevApplied.isEmpty()) {
            pdc.remove(keyAppliedTx);
        } else {
            pdc.set(keyAppliedTx, PersistentDataType.STRING, prevApplied);
        }
        if (prevSeq == 0L) {
            pdc.remove(keySeq);
        } else {
            pdc.set(keySeq, PersistentDataType.LONG, prevSeq);
        }

        inProcessChargedOrders.add(order.txId());
        String reason = check.reason() != null ? check.reason() : "저장 확인 미검출(ABSENT)";
        store.bumpOrderAttempt(order.txId(), reason);
        if (check.status() == PlayerDatReader.MarkerStatus.UNREADABLE) {
            int count = store.incrementOrderUnreadable(order.txId(), reason);
            if (count >= ALERT_THRESHOLD) {
                logger.severe("주문 " + order.txId() + " 디스크 확인 " + count + "회 연속 실패: " + reason);
            }
        }
        Msg.warn(player, "저장 확인이 지연되어 잠시 보류되었습니다. 1분 안에 자동 지급되거나 "
                + "<#FFDF6B>/market claim<#AAAAAA> 으로 받을 수 있습니다.");
        return false;
    }

    // ------------------------------------------------------------------ /market claim

    public void claimPendingOrders(Player player) {
        if (dbLocked()) {
            Msg.error(player, "마켓이 잠금 모드입니다.");
            return;
        }
        UUID uuid = player.getUniqueId();
        if (!inFlight.add(uuid)) {
            Msg.warn(player, "이미 거래를 처리 중입니다.");
            return;
        }
        try {
            List<MarketStore.OrderRecord> orders = store.openOrdersOf(uuid);
            int delivered = 0;
            int remaining = 0;
            for (MarketStore.OrderRecord order : orders) {
                if (MarketStore.ORDER_CHARGED.equals(order.status())) {
                    // 이전 기동 건이면 먼저 디스크 확인
                    if (!inProcessChargedOrders.contains(order.txId())) {
                        PlayerDatReader.MarkerCheck check = PlayerDatReader.checkAnyAttempt(worldRoot, uuid, order.txId());
                        if (check.status() == PlayerDatReader.MarkerStatus.FOUND) {
                            store.markOrderDelivered(order.txId());
                            delivered++;
                            continue;
                        }
                        if (check.status() == PlayerDatReader.MarkerStatus.UNREADABLE) {
                            remaining++;
                            continue;
                        }
                    }
                    if (deliverOrderInTick(player, order)) {
                        delivered++;
                    } else {
                        remaining++;
                    }
                } else if (MarketStore.ORDER_PENDING.equals(order.status())) {
                    remaining++;
                }
            }
            if (delivered > 0) {
                Msg.success(player, "미수령 구매품 " + delivered + "건을 지급했습니다.");
            } else if (remaining == 0) {
                Msg.info(player, "받을 미수령 구매품이 없습니다.");
            } else {
                Msg.warn(player, "아직 확인 대기 중이거나 인벤토리 빈칸이 부족한 주문이 " + remaining + "건 있습니다.");
            }
        } catch (SQLException e) {
            logger.severe("/market claim 오류 (" + player.getName() + "): " + e.getMessage());
            Msg.error(player, "미수령 품목 확인 중 오류가 발생했습니다.");
        } finally {
            inFlight.remove(uuid);
        }
    }

    // ------------------------------------------------------------------ 기동·접속·1분 주기 자동 복구

    /** 서버 기동 시 이전 기동에서 미완료로 남은 모든 주문·판매를 디스크·Bank 기준으로 복구 (ECONOMY.md 13.6, 14.3). */
    public void reconcileOnStartup() {
        if (dbLocked()) {
            return;
        }
        try {
            for (MarketStore.OrderRecord order : store.openOrders()) {
                reconcileOrderFromDisk(order, null);
            }
            for (MarketStore.SellOrderRecord sale : store.openSales()) {
                reconcileSaleFromDisk(sale);
            }
        } catch (SQLException e) {
            logger.severe("기동 복구 중 DB 오류: " + e.getMessage());
        }
    }

    /** 접속 직후(PlayerDataGuard .dat 일치 확인 후): 미완료 거래 복구 + 탈퇴 이관 보정. */
    public void onPlayerJoin(Player player) {
        if (dbLocked()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        try {
            for (MarketStore.OrderRecord order : store.openOrdersOf(uuid)) {
                if (!inProcessChargedOrders.contains(order.txId())) {
                    reconcileOrderFromDisk(order, player);
                } else if (MarketStore.ORDER_CHARGED.equals(order.status())) {
                    deliverOrderInTick(player, order);
                }
            }
            for (MarketStore.SellOrderRecord sale : store.openSalesOf(uuid)) {
                if (!inProcessPendingSales.contains(sale.txId())) {
                    reconcileSaleFromDisk(sale);
                }
            }
        } catch (SQLException e) {
            logger.severe("접속 복구 중 DB 오류 (" + player.getName() + "): " + e.getMessage());
        }
        // VJobs 가 접속 직후 비동기로 데이터를 읽으므로, 직업 정보가 보일 때까지 1초 간격으로 최대 30번 탈퇴 이관 보정
        scheduleJobReconcile(player, JOIN_RECONCILE_TRIES);
    }

    private void scheduleJobReconcile(Player player, int triesLeft) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || dbLocked()) {
                return;
            }
            if (reconcileJobQuotas(player)) {
                refreshOpenMenu(player);
            } else if (triesLeft > 1 && vjobs.available()) {
                scheduleJobReconcile(player, triesLeft - 1);
            } else if (vjobs.available()) {
                logger.warning("접속 후 30초 동안 VJobs 직업 정보를 읽지 못해 탈퇴 이관 보정을 미룹니다 ("
                        + player.getName() + "). /판매를 열거나 판매할 때 다시 보정합니다.");
            }
        }, 20L);
    }

    /** VJobs 는 켜져 있는데 이 플레이어의 직업 정보를 아직 못 읽었으면 true (로딩 중 판매 차단, ECONOMY.md 14.2). */
    public boolean jobsLoading(UUID uuid) {
        return vjobs.available() && vjobs.joinedJobIds(uuid) == null;
    }

    /** 로그아웃 시 실행 중 보류 플래그는 해제(Paper 가 퇴장 저장하므로 다음 접속/재기동 때 디스크 기준으로 판정). */
    public void onPlayerQuit(UUID uuid) {
        if (dbLocked()) {
            return;
        }
        flushQuota(uuid);
        try {
            for (MarketStore.OrderRecord o : store.openOrdersOf(uuid)) {
                inProcessChargedOrders.remove(o.txId());
            }
            for (MarketStore.SellOrderRecord s : store.openSalesOf(uuid)) {
                inProcessPendingSales.remove(s.txId());
            }
        } catch (SQLException ignored) {
            // 다음 기동/접속에서 복구
        }
    }

    /** 1분마다 실행되는 자동 복구 타이머 (메인 스레드). */
    public void tickRecovery() {
        if (dbLocked()) {
            return;
        }
        try {
            for (MarketStore.OrderRecord order : store.openOrders()) {
                if (inFlight.contains(order.uuid())) {
                    continue;
                }
                Player online = plugin.getServer().getPlayer(order.uuid());
                if (inProcessChargedOrders.contains(order.txId())) {
                    if (online != null && online.isOnline() && MarketStore.ORDER_CHARGED.equals(order.status())) {
                        if (deliverOrderInTick(online, order)) {
                            Msg.success(online, "보류 중이던 구매품(" + OrderPayload.summary(order.kind(), order.payload())
                                    + ")을 지급했습니다.");
                            refreshOpenMenu(online);
                        }
                    }
                    continue;
                }
                reconcileOrderFromDisk(order, online);
            }
            for (MarketStore.SellOrderRecord sale : store.openSales()) {
                if (inFlight.contains(sale.uuid())) {
                    continue;
                }
                if (inProcessPendingSales.contains(sale.txId()) && MarketStore.SALE_PENDING.equals(sale.status())) {
                    retryInProcessSale(sale);
                    continue;
                }
                reconcileSaleFromDisk(sale);
            }
        } catch (SQLException e) {
            logger.severe("1분 복구 타이머 DB 오류: " + e.getMessage());
        }
    }

    private void reconcileOrderFromDisk(MarketStore.OrderRecord order, Player online) throws SQLException {
        if (MarketStore.ORDER_PENDING.equals(order.status())) {
            BankBridge.LookupResult lookup = bank.findByExtRef(order.txId());
            switch (lookup.status()) {
                case FOUND -> {
                    store.markOrderCharged(order.txId(), lookup.bankTxId());
                    MarketStore.OrderRecord updated = store.findOrder(order.txId()).orElse(order);
                    reconcileChargedOrderFromDisk(updated, online);
                }
                case NOT_FOUND -> {
                    store.abortOrder(order.txId(), "Bank 출금 미존재 확인(복구)");
                    logger.info("미완료 주문 취소(출금 없음): " + order.txId());
                    refreshOpenMenu(online);
                }
                case ERROR -> {
                    int count = store.incrementOrderUnreadable(order.txId(), "Bank findByExtRef 조회 오류");
                    if (count >= ALERT_THRESHOLD) {
                        logger.severe("주문 " + order.txId() + " Bank 조회 " + count + "회 연속 실패 (/market pending 확인)");
                    }
                }
            }
        } else if (MarketStore.ORDER_CHARGED.equals(order.status())) {
            reconcileChargedOrderFromDisk(order, online);
        }
    }

    private void reconcileChargedOrderFromDisk(MarketStore.OrderRecord order, Player online) throws SQLException {
        PlayerDatReader.MarkerCheck check = PlayerDatReader.checkAnyAttempt(worldRoot, order.uuid(), order.txId());
        switch (check.status()) {
            case FOUND -> {
                store.raisePlayerSeq(order.uuid(), check.seq());
                store.markOrderDelivered(order.txId());
                logger.info("주문 디스크 확인 완료(DELIVERED): " + order.txId());
                refreshOpenMenu(online);
            }
            case ABSENT -> {
                if (online != null && online.isOnline()) {
                    if (deliverOrderInTick(online, order)) {
                        Msg.success(online, "이전 미수령 구매품(" + OrderPayload.summary(order.kind(), order.payload())
                                + ")을 자동으로 지급했습니다.");
                        refreshOpenMenu(online);
                    }
                }
            }
            case UNREADABLE -> {
                int count = store.incrementOrderUnreadable(order.txId(), check.reason());
                if (count >= ALERT_THRESHOLD) {
                    logger.severe("주문 " + order.txId() + " .dat 읽기 " + count + "회 연속 실패: " + check.reason());
                }
            }
        }
    }

    /** 실행 중 보류된 판매: saveData() 재시도 후 FOUND 일 때만 입금, 실행 중에는 절대 ABORTED 하지 않음 (ECONOMY.md 14.3). */
    private void retryInProcessSale(MarketStore.SellOrderRecord sale) throws SQLException {
        Player online = plugin.getServer().getPlayer(sale.uuid());
        if (online == null || !online.isOnline()) {
            return;
        }
        online.saveData();
        PlayerDatReader.MarkerCheck check = PlayerDatReader.checkExact(worldRoot, sale.uuid(), sale.txId(), sale.attempt());
        if (check.status() == PlayerDatReader.MarkerStatus.FOUND) {
            store.raisePlayerSeq(sale.uuid(), check.seq());
            store.markSaleItemsTaken(sale.txId());
            inProcessPendingSales.remove(sale.txId());
            depositForTakenSale(sale, online);
        } else if (check.status() == PlayerDatReader.MarkerStatus.UNREADABLE) {
            int count = store.incrementSaleUnreadable(sale.txId(), check.reason());
            if (count >= ALERT_THRESHOLD) {
                logger.severe("판매 " + sale.txId() + " 저장 확인 " + count + "회 연속 실패: " + check.reason());
            }
        }
    }

    private void reconcileSaleFromDisk(MarketStore.SellOrderRecord sale) throws SQLException {
        Player online = plugin.getServer().getPlayer(sale.uuid());
        if (MarketStore.SALE_ITEMS_TAKEN.equals(sale.status())) {
            depositForTakenSale(sale, online);
            return;
        }
        if (MarketStore.SALE_PENDING.equals(sale.status())) {
            PlayerDatReader.MarkerCheck check = PlayerDatReader.checkAnyAttempt(worldRoot, sale.uuid(), sale.txId());
            switch (check.status()) {
                case FOUND -> {
                    store.raisePlayerSeq(sale.uuid(), check.seq());
                    store.markSaleItemsTaken(sale.txId());
                    depositForTakenSale(sale, online);
                }
                case ABSENT -> {
                    store.abortSale(sale.txId(), "디스크에 제거 표시 없음(아이템 보존 확인)");
                    logger.info("미완료 판매 취소(디스크 아이템 보존): " + sale.txId());
                    refreshOpenMenu(online);
                }
                case UNREADABLE -> {
                    int count = store.incrementSaleUnreadable(sale.txId(), check.reason());
                    if (count >= ALERT_THRESHOLD) {
                        logger.severe("판매 " + sale.txId() + " .dat 읽기 " + count + "회 연속 실패: " + check.reason());
                    }
                }
            }
        }
    }

    private void depositForTakenSale(MarketStore.SellOrderRecord sale, Player online) throws SQLException {
        String context = "매입 복구 " + sale.item() + " x" + (sale.bonusAmount() + sale.baseAmount());
        BankBridge.TxResult dep = bank.deposit(sale.uuid(), sale.total(), sale.txId(), context);
        if (dep.status() == BankBridge.TxStatus.OK) {
            store.markSaleDone(sale.txId(), dep.bankTxId());
            logger.info("보류 판매 입금 완료: " + sale.txId() + " +" + sale.total());
            if (online != null && online.isOnline()) {
                Msg.success(online, "보류 중이던 판매 대금 " + MarketPeriods.won(sale.total()) + "이 입금되었습니다.");
                refreshOpenMenu(online);
            }
        }
    }

    // ------------------------------------------------------------------ 직업 탈퇴 이관 보정 (ECONOMY.md 14.2)

    /**
     * 현재 보유하지 않은 직업 카테고리에 {@code job_amount > 0}이 남아 있으면 {@code base_amount}로 이관합니다.
     * VJobs 가 아직 로딩 중({@code null})이면 건드리지 않습니다.
     *
     * @return 직업 정보를 읽어 보정을 마쳤으면(옮길 것이 없었던 경우 포함) true, VJobs 로딩 중·저장 실패면 false
     */
    public boolean reconcileJobQuotas(Player player) {
        if (dbLocked()) {
            return false;
        }
        flushQuota(player.getUniqueId());
        Set<String> joined = vjobs.joinedJobIds(player.getUniqueId());
        if (joined == null) {
            return false;
        }
        List<String> notJoinedItems = config.sell().itemIdsNotInJobs(joined);
        if (notJoinedItems.isEmpty()) {
            return true;
        }
        try {
            store.demoteJobQuota(player.getUniqueId(), notJoinedItems);
            return true;
        } catch (SQLException e) {
            logger.warning("탈퇴 이관 보정 실패 (" + player.getName() + "): " + e.getMessage());
            return false;
        }
    }

    /** VJobs JobLeaveEvent 발생 시 해당 직업 품목의 보너스 판매 가능량을 즉시 기본으로 이관. */
    public void onJobLeave(UUID uuid, String jobId) {
        if (dbLocked() || jobId == null) {
            return;
        }
        flushQuota(uuid);
        List<String> items = config.sell().itemIdsOfJob(jobId);
        if (items.isEmpty()) {
            return;
        }
        try {
            store.demoteJobQuota(uuid, items);
        } catch (SQLException e) {
            logger.warning("직업 탈퇴 판매 가능량 이관 실패 (" + uuid + ", " + jobId + "): " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 관리자 수동 해결 (/market resolve)

    /**
     * {@code /market resolve <tx_id> refund}: 미수령 주문의 관리자 환불. Market 출금의 유일한 환불 경로입니다
     * ({@code /bank refund}는 Market 출금을 거부, ECONOMY.md 2.4, 13.6).
     *
     * <ol>
     *   <li>플레이어 파일을 다시 확인합니다. 상품 표시가 있으면({@code FOUND}) 이미 받은 것이므로 {@code DELIVERED}로 바꾸고
     *       환불하지 않습니다. 읽지 못하면({@code UNREADABLE}) 환불하지 않습니다. 표시가 없을 때({@code ABSENT})만 진행합니다.
     *       접속 중이면 먼저 {@code saveData()}로 지금 메모리 상태를 디스크에 쓴 뒤 읽습니다.</li>
     *   <li>{@code CHARGED → REFUNDING}을 먼저 커밋해 지급·복구 경로가 이 주문을 더는 건드리지 않게 합니다.</li>
     *   <li>Bank {@code refundByExtRef}(거래당 1회). 성공·이미 환불이면 {@code REFUNDED} + 주간 한도·하루 뽑기 횟수·천장 반환.
     *       확실한 거부면 {@code CHARGED}로 되돌리고, 결과를 모르면 {@code REFUNDING}으로 둬서 같은 명령으로 다시 확인합니다.</li>
     * </ol>
     */
    public String resolveRefund(String txId, String adminName) {
        if (dbLocked()) {
            return "Market 이 잠금 모드입니다.";
        }
        try {
            Optional<MarketStore.OrderRecord> found = store.findOrder(txId);
            if (found.isEmpty()) {
                return "해당 거래 ID를 찾을 수 없습니다: " + txId;
            }
            MarketStore.OrderRecord order = found.get();
            UUID uuid = order.uuid();
            boolean isRetry = MarketStore.ORDER_REFUNDING.equals(order.status());
            if (MarketStore.ORDER_PENDING.equals(order.status())) {
                return "아직 Bank 출금 확인 전(PENDING)입니다. 기동 복구·1분 타이머가 출금을 확인해 CHARGED 로 바꾼 뒤 다시 실행해 주세요.";
            }
            if (MarketStore.ORDER_CHARGED.equals(order.status())) {
                if (inFlight.contains(uuid)) {
                    return "이 플레이어의 거래를 처리 중입니다. 잠시 후 다시 실행해 주세요.";
                }
                String blocked = refundDiskCheck(order);
                if (blocked != null) {
                    return blocked;
                }
                if (!store.beginRefund(txId, "관리자 환불 진행 (" + adminName + ")")) {
                    return "주문 상태가 바뀌어 환불을 시작하지 못했습니다. /market pending 으로 다시 확인해 주세요.";
                }
                inProcessChargedOrders.remove(txId);
            } else if (!isRetry) {
                return "환불 가능한 상태가 아닙니다 (현재 상태: " + order.status() + ").";
            }

            BankBridge.RefundResult res = bank.refundByExtRef(txId, "admin=" + adminName + " market_resolve");
            RefundPolicy.BankStatus bankStatus = switch (res.status()) {
                case OK -> RefundPolicy.BankStatus.OK;
                case ALREADY_REFUNDED -> RefundPolicy.BankStatus.ALREADY_REFUNDED;
                case REJECTED -> RefundPolicy.BankStatus.REJECTED;
                case UNKNOWN -> RefundPolicy.BankStatus.UNKNOWN;
            };
            RefundPolicy.Decision decision = RefundPolicy.decide(isRetry, bankStatus, res.message());
            switch (decision.action()) {
                case COMPLETE -> {
                    String note = res.status() == BankBridge.RefundStatus.OK
                            ? "Bank 환불 #" + res.refundTxId() : "Bank 에 이미 환불 기록 있음";
                    store.finishRefund(txId, "관리자 환불 (" + adminName + ", " + note + ")");
                    Player online = plugin.getServer().getPlayer(uuid);
                    if (online != null && online.isOnline()) {
                        Msg.success(online, "보류된 마켓 주문이 환불되었습니다: +" + MarketPeriods.won(order.price()));
                        refreshOpenMenu(online);
                    }
                    return "환불 완료: " + MarketPeriods.won(order.price()) + " (" + note + ", 한도·천장 반환)";
                }
                case RESTORE -> {
                    store.cancelRefund(txId, "Bank 환불 거부: " + res.message());
                    return decision.message();
                }
                case KEEP_REFUNDING -> {
                    return decision.message();
                }
                default -> throw new IllegalStateException("알 수 없는 환불 결정: " + decision.action());
            }
        } catch (SQLException e) {
            return "DB 오류: " + e.getMessage();
        }
    }

    /** 환불 전 플레이어 파일 확인. 환불해도 되면 null, 아니면 관리자에게 보여 줄 이유. */
    private String refundDiskCheck(MarketStore.OrderRecord order) throws SQLException {
        UUID uuid = order.uuid();
        Player online = plugin.getServer().getPlayer(uuid);
        boolean isOnline = online != null && online.isOnline();
        if (isOnline) {
            // 메모리 상태(같은 틱에 회수했으면 상품·표시 없음)를 먼저 디스크에 씀
            online.saveData();
        }
        PlayerDatReader.MarkerCheck check = PlayerDatReader.checkAnyAttempt(worldRoot, uuid, order.txId());
        switch (check.status()) {
            case FOUND -> {
                if (isOnline && inProcessChargedOrders.contains(order.txId())) {
                    // 이번 기동에서 회수한 주문인데 디스크에 표시가 남음 = 방금 저장이 실패. 판단 보류
                    return "방금 플레이어 저장을 확인하지 못했습니다(디스크에 이전 시도 표시가 남아 있음). 잠시 후 다시 실행해 주세요.";
                }
                store.raisePlayerSeq(uuid, check.seq());
                store.markOrderDelivered(order.txId());
                return "플레이어 파일에 이미 지급 표시가 있어 DELIVERED 로 처리했습니다. 환불하지 않습니다.";
            }
            case UNREADABLE -> {
                return "플레이어 파일을 읽지 못해 환불하지 않습니다 (" + check.reason() + ").";
            }
            default -> {
                return null;
            }
        }
    }

    /** 플레이어가 Market 메뉴를 열어 두었으면 제자리 갱신 (보류 해제·환불 뒤 회색 버튼이 남지 않게). */
    private void refreshOpenMenu(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof Menu menu) {
            menu.render(player);
        }
    }

    // ------------------------------------------------------------------ 공통 검사·안내

    private boolean ensureReady(Player player, boolean featureAvailable, String featureReason) {
        if (dbLocked()) {
            Msg.error(player, "마켓이 잠금 모드입니다. 관리자에게 문의해 주세요.");
            return false;
        }
        if (!bank.available()) {
            Msg.error(player, "은행(Bank)과 연결되지 않아 거래할 수 없습니다.");
            return false;
        }
        if (!featureAvailable) {
            Msg.error(player, "이 기능은 현재 잠겨 있습니다"
                    + (featureReason != null ? " (" + featureReason + ")" : "") + ".");
            return false;
        }
        return true;
    }

    private void handleReserveFailure(Player player, MarketStore.ReserveFailure failure) {
        switch (failure) {
            case PLAYER_LOCKED -> notifyPlayerLocked(player);
            case LIMIT_EXCEEDED -> Msg.error(player, "한도를 초과했습니다.");
            case INSUFFICIENT_QUOTA -> Msg.error(player, "판매 가능량이 부족합니다.");
            case STORAGE_ERROR -> Msg.error(player, "저장 오류로 거래를 처리하지 못했습니다.");
            case NONE -> { }
        }
    }

    private void notifyPlayerLocked(Player player) {
        if (hasClaimableOrder(player.getUniqueId())) {
            Msg.warn(player, "미수령 구매품이 있습니다. 인벤토리 빈칸을 비운 뒤 <#FFDF6B>/market claim<#AAAAAA> 으로 먼저 받아 주세요.");
        } else {
            Msg.warn(player, "이전 거래의 저장 확인이 진행 중입니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    public Map<String, MarketStore.Quota> quotasOf(UUID uuid) {
        if (dbLocked()) {
            return Map.of();
        }
        try {
            return store.allQuotas(uuid);
        } catch (SQLException e) {
            return Map.of();
        }
    }
}
