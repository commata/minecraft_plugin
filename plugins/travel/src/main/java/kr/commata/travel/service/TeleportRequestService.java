package kr.commata.travel.service;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import kr.commata.common.time.Durations;
import kr.commata.travel.Chat;
import kr.commata.travel.TravelPlugin;
import kr.commata.travel.cast.BukkitSafety;
import kr.commata.travel.cast.CastJob;
import kr.commata.travel.cast.CastManager;
import kr.commata.travel.logic.PlayerSafety;
import kr.commata.travel.logic.RequestBook;
import kr.commata.travel.logic.Timers;
import kr.commata.travel.logic.TravelSettings;
import kr.commata.travel.model.TeleportRequest;
import kr.commata.travel.storage.TravelStore;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * /tpa 요청을 보내고, 받고, 수락·거절·취소·만료를 처리합니다.
 * 수락하면 요청자가 {@link CastManager} 로 시전하고, 시전이 끝나는 순간 수락자의 위치로 이동합니다.
 * 모든 메서드는 메인 스레드에서 호출합니다.
 */
public final class TeleportRequestService {

    public static final String BYPASS_COOLDOWN_PERMISSION = "tpa.bypass.cooldown";

    private static final Chat CHAT = Chat.TPA;
    private static final Sound REQUEST_SOUND =
            Sound.sound(Key.key("minecraft", "block.note_block.pling"), Sound.Source.MASTER, 1f, 1.4f);
    private static final Sound ACCEPT_SOUND =
            Sound.sound(Key.key("minecraft", "entity.experience_orb.pickup"), Sound.Source.MASTER, 0.8f, 1.2f);

    private final TravelPlugin plugin;
    private final TravelStore store;
    private final CastManager casts;
    private final RequestBook book = new RequestBook();
    /** 요청자 UUID → 수락자에게 보이는 보스바 */
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private BukkitTask ticker;

    public TeleportRequestService(TravelPlugin plugin, TravelStore store, CastManager casts) {
        this.plugin = plugin;
        this.store = store;
        this.casts = casts;
    }

    public void startTicker() {
        ticker = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void shutdown() {
        if (ticker != null) {
            ticker.cancel();
        }
        for (TeleportRequest r : book.all()) {
            hideBar(r);
        }
        book.clear();
    }

    // ------------------------------------------------------------------ 조회 (GUI 용)

    public Optional<TeleportRequest> outgoing(Player player) {
        return book.outgoing(player.getUniqueId()).filter(r -> !r.isExpired(System.currentTimeMillis()));
    }

    public List<TeleportRequest> incoming(Player player) {
        return book.incoming(player.getUniqueId());
    }

    public boolean acceptsRequests(Player player) {
        return !store.tpaOff(player);
    }

    public Duration cooldownLeft(Player player) {
        if (player.hasPermission(BYPASS_COOLDOWN_PERMISSION)) {
            return Duration.ZERO;
        }
        return Timers.remaining(System.currentTimeMillis(), store.tpaReadyAt(player));
    }

    // ------------------------------------------------------------------ 보내기

    public void send(Player from, Player to) {
        TravelSettings settings = plugin.settings();
        if (from.equals(to)) {
            CHAT.error(from, "자기 자신에게는 요청할 수 없습니다.");
            return;
        }
        if (store.tpaOff(to)) {
            CHAT.error(from, to.getName() + "님은 이동 요청을 받지 않고 있습니다.");
            return;
        }
        if (casts.isBusy(from)) {
            CHAT.error(from, "귀환 또는 이동 중에는 요청을 보낼 수 없습니다.");
            return;
        }
        Duration combat = casts.combatLeft(from);
        if (!combat.isZero()) {
            CHAT.error(from, "전투 중에는 이동 요청을 보낼 수 없습니다. (" + Durations.korean(combat) + " 남음)");
            return;
        }
        Duration cooldown = cooldownLeft(from);
        if (!cooldown.isZero()) {
            CHAT.error(from, Durations.korean(cooldown) + " 후에 다시 이동할 수 있습니다.");
            return;
        }
        Optional<PlayerSafety.Unsafe> selfUnsafe = BukkitSafety.checkPlayer(from, settings.maxFallDistance());
        if (selfUnsafe.isPresent()) {
            CHAT.error(from, selfUnsafe.get().phrase() + " 이동 요청을 보낼 수 없습니다.");
            return;
        }
        // 받는 사람이 떨어지는 중·전투 중·용암 속 등이면 보내지 않음. 상대 상태가 드러나지 않게 이유는 말하지 않음.
        if (targetUnavailable(to).isPresent()) {
            CHAT.error(from, to.getName() + "님은 현재 요청을 받을 수 없는 상황입니다. 잠시 후 다시 시도해 주세요.");
            return;
        }

        long now = System.currentTimeMillis();
        RequestBook.SendResult result = book.send(from.getUniqueId(), from.getName(), to.getUniqueId(), to.getName(),
                now, settings.requestTimeout(), settings.resendInterval());
        switch (result) {
            case RequestBook.SendResult.AlreadyPending p -> CHAT.error(from, to.getName() + "님에게 보낸 요청이 아직 대기 중입니다. ("
                    + Durations.korean(p.request().remaining(now)) + " 남음)");
            case RequestBook.SendResult.TooSoon t -> CHAT.error(from, to.getName() + "님에게는 "
                    + Durations.korean(t.retryIn()) + " 후에 다시 요청할 수 있습니다.");
            case RequestBook.SendResult.Sent s -> {
                s.replaced().ifPresent(old -> {
                    hideBar(old);
                    withPlayer(old.target(), p -> CHAT.hint(p, old.requesterName() + "님이 이동 요청을 취소했습니다."));
                    CHAT.hint(from, old.targetName() + "님에게 보낸 이전 요청은 취소했습니다.");
                });
                notifySent(from, to, s.request());
            }
        }
    }

    private void notifySent(Player from, Player to, TeleportRequest r) {
        long secs = plugin.settings().requestTimeout().toSeconds();
        from.sendMessage(CHAT.prefix()
                .append(Component.text(to.getName() + "님에게 이동 요청을 보냈습니다. (" + secs + "초) ", NamedTextColor.YELLOW))
                .append(button("[취소]", NamedTextColor.RED, "/tpcancel", "보낸 요청 취소")));

        to.sendMessage(CHAT.prefix()
                .append(Component.text(from.getName(), NamedTextColor.AQUA))
                .append(Component.text("님이 회원님에게 이동하려고 합니다. ", NamedTextColor.YELLOW))
                .append(button("[수락]", NamedTextColor.GREEN, "/tpaccept " + from.getName(),
                        from.getName() + "님의 요청 수락"))
                .append(Component.space())
                .append(button("[거절]", NamedTextColor.RED, "/tpdeny " + from.getName(),
                        from.getName() + "님의 요청 거절")));
        CHAT.hint(to, "T 를 눌러 버튼을 클릭하거나 /tpaccept, /tpdeny 를 입력하세요. " + secs + "초 후 자동으로 만료됩니다.");
        to.playSound(REQUEST_SOUND);

        BossBar bar = BossBar.bossBar(barTitle(r, System.currentTimeMillis()), 1f,
                BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        bars.put(r.requester(), bar);
        to.showBossBar(bar);
    }

    private static Component button(String label, NamedTextColor color, String command, String hover) {
        return Component.text(label, color, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(hover + " (" + command + ")", NamedTextColor.GRAY)));
    }

    private static Component barTitle(TeleportRequest r, long now) {
        return Component.text(r.requesterName() + "님의 이동 요청  ·  /tpaccept 수락  ·  "
                + Durations.korean(r.remaining(now)), NamedTextColor.YELLOW);
    }

    // ------------------------------------------------------------------ 수락·거절·취소

    public void accept(Player target, Optional<String> fromName) {
        Optional<TeleportRequest> found = findIncoming(target, fromName);
        if (found.isEmpty()) {
            return;
        }
        TeleportRequest r = found.get();
        Player requester = plugin.getServer().getPlayer(r.requester());
        if (requester == null || !requester.isOnline()) {
            dropRequest(r);
            CHAT.error(target, r.requesterName() + "님이 접속 중이 아닙니다.");
            return;
        }

        // 수락하는 사람 상태: 지금 수락할 수 없으면 요청은 그대로 두고 나중에 수락할 수 있게 함
        Duration targetCombat = casts.combatLeft(target);
        if (!targetCombat.isZero()) {
            CHAT.error(target, "전투 중에는 요청을 수락할 수 없습니다. (" + Durations.korean(targetCombat) + " 남음)");
            return;
        }
        if (casts.isBusy(target)) {
            CHAT.error(target, "귀환 또는 이동 중에는 요청을 수락할 수 없습니다.");
            return;
        }
        Optional<String> targetProblem = targetUnavailable(target);
        if (targetProblem.isPresent()) {
            CHAT.error(target, targetProblem.get() + "에는 요청을 수락할 수 없습니다. 안전한 곳에 선 뒤 다시 수락해 주세요.");
            return;
        }

        // 요청자 상태
        if (casts.isBusy(requester)) {
            CHAT.error(target, r.requesterName() + "님이 지금 다른 이동 중이라 수락할 수 없습니다. 잠시 후 다시 시도해 주세요.");
            return;
        }
        Duration requesterCombat = casts.combatLeft(requester);
        if (!requesterCombat.isZero()) {
            CHAT.error(target, r.requesterName() + "님이 지금은 이동할 수 없는 상황입니다. 잠시 후 다시 시도해 주세요.");
            CHAT.hint(requester, target.getName() + "님이 수락하려 했지만 전투 중이라 이동할 수 없습니다. ("
                    + Durations.korean(requesterCombat) + " 남음)");
            return;
        }
        Optional<PlayerSafety.Unsafe> requesterUnsafe =
                BukkitSafety.checkPlayer(requester, plugin.settings().maxFallDistance());
        if (requesterUnsafe.isPresent()) {
            CHAT.error(target, r.requesterName() + "님이 지금은 이동할 수 없는 상황입니다. 잠시 후 다시 시도해 주세요.");
            CHAT.hint(requester, target.getName() + "님이 수락하려 했지만 " + requesterUnsafe.get().phrase()
                    + " 이동할 수 없습니다.");
            return;
        }
        Duration cooldown = cooldownLeft(requester);
        if (!cooldown.isZero()) {
            dropRequest(r);
            CHAT.error(target, r.requesterName() + "님은 아직 이동할 수 없어 요청이 취소되었습니다.");
            CHAT.error(requester, Durations.korean(cooldown) + " 후에 다시 이동할 수 있어 요청이 취소되었습니다.");
            return;
        }

        dropRequest(r);
        int seconds = plugin.settings().tpa().castSeconds();
        requester.playSound(ACCEPT_SOUND);
        if (seconds <= 0) {
            CHAT.success(target, r.requesterName() + "님의 요청을 수락했습니다.");
            CHAT.info(requester, target.getName() + "님이 요청을 수락했습니다.");
        } else {
            CHAT.success(target, r.requesterName() + "님의 요청을 수락했습니다. " + seconds + "초 후 도착합니다.");
        }
        casts.start(requester, new Job(target.getUniqueId(), target.getName(), seconds),
                target.getName() + "님이 요청을 수락했습니다. " + seconds + "초 후 이동합니다.");
    }

    public void deny(Player target, Optional<String> fromName) {
        findIncoming(target, fromName).ifPresent(r -> {
            dropRequest(r);
            CHAT.info(target, r.requesterName() + "님의 요청을 거절했습니다.");
            withPlayer(r.requester(), p -> CHAT.error(p, target.getName() + "님이 이동 요청을 거절했습니다."));
        });
    }

    /** /tpcancel: 보낸 요청을 취소하거나, 수락 후 시전 중이면 시전을 취소합니다. */
    public void cancel(Player from) {
        Optional<TeleportRequest> out = book.cancelOutgoing(from.getUniqueId());
        if (out.isPresent()) {
            TeleportRequest r = out.get();
            hideBar(r);
            CHAT.info(from, r.targetName() + "님에게 보낸 요청을 취소했습니다.");
            withPlayer(r.target(), p -> CHAT.hint(p, from.getName() + "님이 이동 요청을 취소했습니다."));
            return;
        }
        if (casts.job(from).filter(j -> j instanceof Job).isPresent()) {
            casts.abort(from, "이동을 취소했습니다.");
            return;
        }
        CHAT.error(from, "보낸 이동 요청이 없습니다.");
    }

    /** /tpatoggle */
    public void toggle(Player player) {
        boolean nowOff = !store.tpaOff(player);
        store.setTpaOff(player, nowOff);
        if (nowOff) {
            for (TeleportRequest r : book.removeIncoming(player.getUniqueId())) {
                hideBar(r);
                withPlayer(r.requester(), p -> CHAT.hint(p, player.getName() + "님에게 보낸 요청이 취소되었습니다."));
            }
            CHAT.info(player, "이제 이동 요청을 받지 않습니다. 다시 받으려면 /tpatoggle");
        } else {
            CHAT.success(player, "이제 이동 요청을 받습니다.");
        }
    }

    public void onQuit(Player player) {
        for (TeleportRequest r : book.removeInvolving(player.getUniqueId())) {
            hideBar(r);
            UUID other = r.requester().equals(player.getUniqueId()) ? r.target() : r.requester();
            withPlayer(other, p -> CHAT.hint(p, player.getName() + "님이 접속을 종료해서 이동 요청이 취소되었습니다."));
        }
        casts.abortInvolving(player.getUniqueId(), player.getName() + "님이 접속을 종료해서 이동이 취소되었습니다.");
    }

    // ------------------------------------------------------------------ 내부

    private Optional<TeleportRequest> findIncoming(Player target, Optional<String> fromName) {
        Optional<TeleportRequest> found = book.find(target.getUniqueId(), fromName)
                .filter(r -> !r.isExpired(System.currentTimeMillis()));
        if (found.isEmpty()) {
            CHAT.error(target, fromName.map(n -> n + "님에게 받은 이동 요청이 없습니다.").orElse("받은 이동 요청이 없습니다."));
        }
        return found;
    }

    /**
     * 이 사람에게 지금 순간이동해 갈 수 없는 이유 (전투 중, 떨어지는 중, 용암 속, 탈것 위 등).
     * 요청을 보낼 때, 수락할 때, 시전이 끝나 이동하기 직전에 검사합니다.
     */
    private Optional<String> targetUnavailable(Player target) {
        if (target.isDead()) {
            return Optional.of("사망한 상태");
        }
        if (BukkitSafety.isSpectator(target)) {
            return Optional.of("관전 모드");
        }
        if (!casts.combatLeft(target).isZero()) {
            return Optional.of("전투 중");
        }
        return BukkitSafety.checkDestinationPlayer(target, plugin.settings().maxFallDistance())
                .map(PlayerSafety.Unsafe::what);
    }

    private void dropRequest(TeleportRequest r) {
        book.remove(r);
        hideBar(r);
    }

    private void hideBar(TeleportRequest r) {
        BossBar bar = bars.remove(r.requester());
        if (bar != null) {
            withPlayer(r.target(), p -> p.hideBossBar(bar));
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (TeleportRequest r : book.expire(now, plugin.settings().resendInterval())) {
            hideBar(r);
            withPlayer(r.requester(), p -> CHAT.hint(p, r.targetName() + "님에게 보낸 이동 요청이 만료되었습니다."));
            withPlayer(r.target(), p -> CHAT.hint(p, r.requesterName() + "님의 이동 요청이 만료되었습니다."));
        }
        for (TeleportRequest r : book.all()) {
            BossBar bar = bars.get(r.requester());
            if (bar != null) {
                bar.progress(r.progress(now));
                bar.name(barTitle(r, now));
            }
        }
    }

    private void withPlayer(UUID id, java.util.function.Consumer<Player> action) {
        Player p = plugin.getServer().getPlayer(id);
        if (p != null && p.isOnline()) {
            action.accept(p);
        }
    }

    /** 수락 후 요청자의 시전. 시전이 끝나는 순간의 수락자 위치로 이동합니다. */
    private final class Job implements CastJob {
        private final UUID targetId;
        private final String targetName;
        private final int seconds;

        Job(UUID targetId, String targetName, int seconds) {
            this.targetId = targetId;
            this.targetName = targetName;
            this.seconds = seconds;
        }

        @Override
        public Chat chat() {
            return CHAT;
        }

        @Override
        public String noun() {
            return "이동";
        }

        @Override
        public int seconds() {
            return seconds;
        }

        @Override
        public String actionBar(int secondsLeft) {
            return targetName + "님에게 이동까지 " + secondsLeft + "초";
        }

        @Override
        public boolean involves(UUID player) {
            return targetId.equals(player);
        }

        @Override
        public Destination destination(Player player) {
            Player target = plugin.getServer().getPlayer(targetId);
            if (target == null || !target.isOnline()) {
                return Destination.fail(targetName + "님이 접속 중이 아니라 이동하지 않았습니다.");
            }
            Optional<String> problem = targetUnavailable(target);
            if (problem.isPresent()) {
                return Destination.fail(targetName + "님이 지금 이동해 갈 수 없는 상황이라 이동하지 않았습니다. ("
                        + problem.get() + ")");
            }
            return Destination.to(target.getLocation(),
                    targetName + "님이 서 있는 곳이 막혀 있거나 위험해서 이동하지 않았습니다.");
        }

        @Override
        public void onSuccess(Player player) {
            store.setTpaReadyAt(player,
                    System.currentTimeMillis() + plugin.settings().tpa().cooldown().toMillis());
            CHAT.success(player, targetName + "님에게 이동했습니다!");
            withPlayer(targetId, t -> CHAT.success(t, player.getName() + "님이 도착했습니다."));
        }

        @Override
        public void onAborted(Player player, String why) {
            String name = player != null ? player.getName() : "상대";
            withPlayer(targetId, t -> CHAT.hint(t, name + "님의 이동이 취소되었습니다."));
        }
    }
}
