package kr.commata.travel.gui;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import kr.commata.common.time.Durations;
import kr.commata.travel.model.TeleportRequest;
import kr.commata.travel.service.TeleportRequestService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.potion.PotionEffectType;

/**
 * /tpa 접속자 목록. 위 5줄은 접속자 머리(한 페이지 45명), 맨 아랫줄은 페이지·설정 버튼.
 * 좌표는 보여 주지 않고 차원만 표시합니다. 자기 자신과 볼 수 없는(숨김 처리된) 플레이어는 뺍니다.
 */
public final class TpaMenu implements InventoryHolder {

    public static final int PER_PAGE = 45;
    static final int SLOT_PREV = 45;
    static final int SLOT_TOGGLE = 47;
    static final int SLOT_INFO = 49;
    static final int SLOT_CANCEL = 51;
    static final int SLOT_NEXT = 53;

    /** 클릭했을 때 할 일 */
    sealed interface Action {
        record Request(UUID target) implements Action {
        }

        record Page(int page) implements Action {
        }

        record Toggle() implements Action {
        }

        record CancelOutgoing() implements Action {
        }
    }

    private final Server server;
    private final TeleportRequestService requests;
    private final UUID viewer;
    private final Inventory inventory;
    private final Map<Integer, Action> actions = new HashMap<>();
    private int page;

    public TpaMenu(Server server, TeleportRequestService requests, Player viewer) {
        this.server = server;
        this.requests = requests;
        this.viewer = viewer.getUniqueId();
        this.inventory = server.createInventory(this, 54,
                Component.text("이동 요청 보내기", NamedTextColor.DARK_GRAY));
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    Optional<Action> actionAt(int slot) {
        return Optional.ofNullable(actions.get(slot));
    }

    public void open(Player player) {
        render(player);
        player.openInventory(inventory);
    }

    void render(Player player) {
        inventory.clear();
        actions.clear();
        long now = System.currentTimeMillis();

        List<Player> others = new ArrayList<>();
        for (Player p : server.getOnlinePlayers()) {
            if (!p.getUniqueId().equals(viewer) && player.canSee(p)) {
                others.add(p);
            }
        }
        others.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));

        int pages = Math.max(1, (others.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.clamp(page, 0, pages - 1);
        Optional<TeleportRequest> outgoing = requests.outgoing(player);

        int from = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && from + i < others.size(); i++) {
            Player target = others.get(from + i);
            inventory.setItem(i, head(target, outgoing, now));
            actions.put(i, new Action.Request(target.getUniqueId()));
        }

        if (others.isEmpty()) {
            inventory.setItem(22, item(Material.GRAY_STAINED_GLASS_PANE, "다른 접속자가 없습니다", NamedTextColor.GRAY));
        }
        if (page > 0) {
            inventory.setItem(SLOT_PREV, item(Material.ARROW, "이전 페이지", NamedTextColor.WHITE));
            actions.put(SLOT_PREV, new Action.Page(page - 1));
        }
        if (page < pages - 1) {
            inventory.setItem(SLOT_NEXT, item(Material.ARROW, "다음 페이지", NamedTextColor.WHITE));
            actions.put(SLOT_NEXT, new Action.Page(page + 1));
        }

        boolean on = requests.acceptsRequests(player);
        inventory.setItem(SLOT_TOGGLE, item(on ? Material.LIME_DYE : Material.GRAY_DYE,
                "요청 받기: " + (on ? "켜짐" : "꺼짐"), on ? NamedTextColor.GREEN : NamedTextColor.GRAY,
                "클릭해서 " + (on ? "끄기" : "켜기") + " (/tpatoggle)"));
        actions.put(SLOT_TOGGLE, new Action.Toggle());

        Duration cooldown = requests.cooldownLeft(player);
        inventory.setItem(SLOT_INFO, item(Material.COMPASS,
                "페이지 " + (page + 1) + " / " + pages, NamedTextColor.YELLOW,
                "접속자 " + others.size() + "명",
                cooldown.isZero() ? "지금 이동할 수 있습니다" : "다시 이동까지 " + Durations.korean(cooldown),
                "머리를 클릭하면 이동 요청을 보냅니다"));

        outgoing.ifPresent(r -> {
            inventory.setItem(SLOT_CANCEL, item(Material.BARRIER, "보낸 요청 취소", NamedTextColor.RED,
                    r.targetName() + "님에게 보냄 (" + Durations.korean(r.remaining(now)) + " 남음)"));
            actions.put(SLOT_CANCEL, new Action.CancelOutgoing());
        });
    }

    void setPage(int page) {
        this.page = page;
    }

    private ItemStack head(Player target, Optional<TeleportRequest> outgoing, long now) {
        ItemStack stack = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) stack.getItemMeta();
        meta.setPlayerProfile(target.getPlayerProfile()); // 접속 중인 플레이어의 실제 스킨

        boolean off = !requests.acceptsRequests(target);
        boolean pendingHere = outgoing.filter(r -> r.target().equals(target.getUniqueId())).isPresent();
        meta.displayName(plain(target.getName(), off ? NamedTextColor.GRAY : NamedTextColor.AQUA));

        List<Component> lore = new ArrayList<>();
        // 투명화 물약을 마신 사람은 어디 있는지도 숨김
        String dimension = target.hasPotionEffect(PotionEffectType.INVISIBILITY)
                ? "비공개" : dimensionName(target.getWorld());
        lore.add(plain("차원: " + dimension, NamedTextColor.GRAY));
        if (off) {
            lore.add(plain("요청을 받지 않는 중", NamedTextColor.DARK_GRAY));
        } else if (pendingHere) {
            lore.add(plain("요청 보냄 · " + Durations.korean(outgoing.get().remaining(now)) + " 남음",
                    NamedTextColor.GOLD));
        } else {
            lore.add(plain("클릭: 이동 요청 보내기", NamedTextColor.GREEN));
        }
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    static String dimensionName(World world) {
        return switch (world.getEnvironment()) {
            case NORMAL -> "오버월드";
            case NETHER -> "네더";
            case THE_END -> "엔드";
            default -> world.getName();
        };
    }

    private static ItemStack item(Material material, String name, NamedTextColor color, String... lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(plain(name, color));
        if (lore.length > 0) {
            List<Component> lines = new ArrayList<>();
            for (String line : lore) {
                lines.add(plain(line, NamedTextColor.GRAY));
            }
            meta.lore(lines);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private static Component plain(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }
}
