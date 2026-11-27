package kr.commata.trade.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.commata.common.gui.Items;
import kr.commata.common.gui.Menu;
import kr.commata.trade.core.TradeSession;
import kr.commata.trade.service.TradeService;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * {@code /trade} 접속자 선택 GUI (ECONOMY.md 7.4.6a, Travel {@code TpaMenu} 구조 재사용).
 * 0–44 접속자 머리, 45 이전 쪽, 48 메인 허브, 49 안내/보낸 요청 취소, 50 다음 쪽, 53 닫기.
 */
public final class PlayerSelectMenu extends Menu {

    private static final int PER_PAGE = 45;
    private static final int SLOT_EMPTY = 22;
    private static final int SLOT_PREV = 45;
    private static final int SLOT_HUB = 48;
    private static final int SLOT_CENTER = 49;
    private static final int SLOT_NEXT = 50;
    private static final int SLOT_CLOSE = 53;

    private final Plugin plugin;
    private final Server server;
    private final TradeService service;
    private final UUID viewerId;
    private int page;
    private BukkitTask refreshTask;

    public PlayerSelectMenu(Plugin plugin, TradeService service, Player viewer) {
        super(6, Items.text("<#F5C542>거래할 플레이어 선택"));
        this.plugin = plugin;
        this.server = plugin.getServer();
        this.service = service;
        this.viewerId = viewer.getUniqueId();
    }

    @Override
    protected void opened(Player viewer) {
        refreshTask = server.getScheduler().runTaskTimer(plugin, () -> {
            if (!isViewing(viewer)) {
                cancelRefresh();
                return;
            }
            render(viewer);
        }, 20L, 20L);
    }

    @Override
    protected void closed(Player viewer) {
        cancelRefresh();
    }

    private void cancelRefresh() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
    }

    @Override
    protected void draw(Player viewer) {
        long nowMs = System.currentTimeMillis();

        List<Player> others = new ArrayList<>();
        for (Player p : server.getOnlinePlayers()) {
            if (!p.getUniqueId().equals(viewerId) && viewer.canSee(p)) {
                others.add(p);
            }
        }
        others.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));

        int pages = Math.max(1, (others.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.clamp(page, 0, pages - 1);

        if (others.isEmpty()) {
            set(SLOT_EMPTY, Items.of(Material.GRAY_STAINED_GLASS_PANE)
                    .name("<#AAAAAA>다른 접속자가 없습니다")
                    .build());
        } else {
            int from = page * PER_PAGE;
            for (int i = 0; i < PER_PAGE && from + i < others.size(); i++) {
                Player target = others.get(from + i);
                String reason = service.unavailableReason(viewer, target, nowMs);
                if (reason == null) {
                    set(i, Items.of(Material.PLAYER_HEAD)
                                    .head(target)
                                    .name("<#55FFFF>" + target.getName())
                                    .lore("<#55FF55>▶ 클릭하여 거래 요청")
                                    .build(),
                            (p, click) -> {
                                service.sendRequest(p, target);
                                if (isViewing(p)) {
                                    render(p);
                                }
                            });
                } else {
                    set(i, Items.of(Material.PLAYER_HEAD)
                            .head(target)
                            .name("<#AAAAAA>" + target.getName())
                            .lore("<#FF5555>" + reason)
                            .build());
                }
            }
        }

        if (page > 0) {
            set(SLOT_PREV, Items.of(Material.ARROW)
                            .name("<#F5C542>이전 페이지")
                            .lore("<#AAAAAA>" + page + " / " + pages + " 페이지")
                            .build(),
                    (p, click) -> {
                        page--;
                        render(p);
                    });
        }

        if (server.getPluginManager().isPluginEnabled("EconomyHub")) {
            set(SLOT_HUB, Items.of(Material.OAK_DOOR)
                            .name("<#F5C542>메인 허브")
                            .lore("<#AAAAAA>서버 경제 메뉴로 돌아갑니다.", "<#55FF55>▶ 클릭하여 이동")
                            .build(),
                    (p, click) -> server.getScheduler().runTask(plugin, () -> {
                        if (p.isOnline()) {
                            p.performCommand("economy");
                        }
                    }));
        }

        Optional<TradeSession> outgoing = service.sessions().sessionOf(viewerId)
                .filter(s -> s.state() == TradeSession.State.REQUESTED && s.sideA().uuid().equals(viewerId));
        if (outgoing.isPresent()) {
            TradeSession req = outgoing.get();
            long remainSec = Math.max(1L, (req.requestRemainingMs(nowMs) + 999L) / 1000L);
            set(SLOT_CENTER, Items.of(Material.CLOCK)
                            .name("<#FFDF6B>보낸 요청: " + req.sideB().name() + " (" + remainSec + "초 남음)")
                            .lore("<#AAAAAA>상대가 수락하면 거래 창이 열립니다.",
                                    "<#FF5555>▶ 클릭하여 요청 취소")
                            .build(),
                    (p, click) -> {
                        service.cancelOutgoingOrActive(p);
                        if (isViewing(p)) {
                            render(p);
                        }
                    });
        } else {
            set(SLOT_CENTER, Items.of(Material.BOOK)
                    .name("<#F5C542>거래 안내")
                    .lore("<#AAAAAA>요청은 <#FFDF6B>" + service.config().requestTimeoutSeconds()
                                    + "초<#AAAAAA> 안에 수락해야 합니다.",
                            "<#AAAAAA>거리 제한 없음 · 전투 중에는 거래 불가",
                            "<#AAAAAA>받을 물건 대기 시 <#FFDF6B>/trade claim")
                    .build());
        }

        if (page < pages - 1) {
            set(SLOT_NEXT, Items.of(Material.ARROW)
                            .name("<#F5C542>다음 페이지")
                            .lore("<#AAAAAA>" + (page + 2) + " / " + pages + " 페이지")
                            .build(),
                    (p, click) -> {
                        page++;
                        render(p);
                    });
        }

        set(SLOT_CLOSE, Items.close(), (p, click) -> server.getScheduler().runTask(plugin, () -> p.closeInventory()));
    }
}
