package kr.commata.common.gui;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;

/**
 * 자체 GUI 의 바탕 (ECONOMY.md 7.4). 전용 {@link InventoryHolder} 라서 {@link MenuListener} 가 이 창의 클릭·드래그를
 * 전부 막고, 위쪽 창의 버튼 클릭만 {@link Click} 으로 넘깁니다.
 *
 * <p>{@link #render(Player)} 는 매번 모든 버튼을 다시 놓고, 이번에 놓지 않은 칸은 빈칸 채움으로 바꿉니다. 창을 다시 열지 않고
 * 슬롯만 바꾸므로 거래 뒤에도 창이 닫히지 않습니다.
 */
public abstract class Menu implements InventoryHolder {

    /** 버튼 클릭. 메인 스레드, 인벤토리 이벤트 안에서 불립니다(창을 닫거나 명령을 실행하려면 다음 틱으로 미룰 것). */
    @FunctionalInterface
    public interface Click {
        void handle(Player player, ClickType type);
    }

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .hexColors().useUnusualXRepeatedCharacterHexFormat().build();

    private final Inventory inventory;
    private final Map<Integer, Click> clicks = new HashMap<>();
    /** Shift+좌클릭도 버튼으로 받는 칸 (ECONOMY.md 7.4 공통 규칙의 예외, 칸마다 명시적으로 켬) */
    private final Set<Integer> shiftLeftSlots = new HashSet<>();
    private final boolean[] placed;

    protected Menu(int rows, Component title) {
        this.inventory = Bukkit.createInventory(this, rows * 9, title);
        this.placed = new boolean[rows * 9];
    }

    @Override
    public final Inventory getInventory() {
        return inventory;
    }

    /** 모든 칸을 그립니다. {@link #set} 으로 놓은 칸 말고는 빈칸 채움이 됩니다. */
    protected abstract void draw(Player viewer);

    /** 창이 열린 직후 (타이머 시작 등) */
    protected void opened(Player viewer) {
    }

    /** 창이 닫힌 직후 (타이머 정리 등) */
    protected void closed(Player viewer) {
    }

    public final void open(Player player) {
        render(player);
        player.openInventory(inventory);
        opened(player);
    }

    /** 창을 다시 그립니다. 바뀐 칸만 클라이언트에 전송됩니다. */
    public final void render(Player viewer) {
        clicks.clear();
        shiftLeftSlots.clear();
        java.util.Arrays.fill(placed, false);
        draw(viewer);
        ItemStack filler = null;
        for (int slot = 0; slot < placed.length; slot++) {
            if (!placed[slot]) {
                if (filler == null) {
                    filler = Items.filler();
                }
                inventory.setItem(slot, filler);
            }
        }
    }

    protected final void set(int slot, ItemStack item) {
        set(slot, item, null);
    }

    protected final void set(int slot, ItemStack item, Click click) {
        inventory.setItem(slot, item);
        placed[slot] = true;
        shiftLeftSlots.remove(slot);
        if (click != null) {
            clicks.put(slot, click);
        } else {
            clicks.remove(slot);
        }
    }

    /**
     * {@link #set(int, ItemStack, Click)} 와 같고, 이 칸은 Shift+좌클릭({@link ClickType#SHIFT_LEFT})도 버튼으로 넘깁니다.
     * 아이템 이동은 여전히 {@link MenuListener} 가 취소합니다. 핸들러는 {@code type} 으로 클릭을 구분해야 합니다.
     */
    protected final void setShiftable(int slot, ItemStack item, Click click) {
        set(slot, item, click);
        if (click != null) {
            shiftLeftSlots.add(slot);
        }
    }

    final boolean acceptsShiftLeft(int slot) {
        return shiftLeftSlots.contains(slot);
    }

    /** 이 창을 보고 있으면 true */
    public final boolean isViewing(Player player) {
        return player.isOnline() && player.getOpenInventory().getTopInventory().getHolder() == this;
    }

    /**
     * 제목만 바꿉니다(창은 그대로). 버튼만 있는 창에서 쓰세요. 보고 있지 않으면 아무것도 하지 않습니다.
     * 서버가 창 제목 변경을 지원하지 않으면 새 창 없이 그대로 둡니다.
     */
    @SuppressWarnings("deprecation")
    protected final void retitle(Player viewer, Component title) {
        if (!isViewing(viewer)) {
            return;
        }
        InventoryView view = viewer.getOpenInventory();
        try {
            view.setTitle(LEGACY.serialize(title));
        } catch (UnsupportedOperationException | IllegalStateException e) {
            // 제목 갱신이 안 되는 창: 내용은 이미 갱신됐으니 그대로 둠
        }
    }

    /**
     * 아래(플레이어) 인벤토리의 좌·우클릭을 버튼 해석으로 받을지 여부. 기본값은 {@code false} 로 기존 메뉴 동작은 바뀌지 않습니다.
     *
     * <p>{@code Trade} 거래 창처럼 아이템 이동은 전부 취소한 채 플레이어 인벤토리 클릭만 해석해야 하는 메뉴(ECONOMY.md 7.4.6,
     * TRADE.md 3·7장)만 {@code true} 로 켜고 {@link #bottomClick(Player, int, ClickType)} 를 구현합니다.
     */
    protected boolean acceptsBottomClicks() {
        return false;
    }

    /**
     * {@link #acceptsBottomClicks()} 가 {@code true} 일 때 아래 인벤토리의 좌·우클릭만 전달됩니다.
     * 아이템 이동은 {@link MenuListener} 가 이미 취소한 상태이며, {@code slot} 은 {@code PlayerInventory} 슬롯(0–35 등)입니다.
     */
    protected void bottomClick(Player player, int slot, ClickType type) {
    }

    final void click(Player player, int slot, ClickType type) {
        Click click = clicks.get(slot);
        if (click != null) {
            click.handle(player, type);
        }
    }

    final void handleBottomClick(Player player, int slot, ClickType type) {
        bottomClick(player, slot, type);
    }

    final void close(Player player) {
        closed(player);
    }
}
