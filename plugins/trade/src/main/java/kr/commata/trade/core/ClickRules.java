package kr.commata.trade.core;

/**
 * 거래 창(9×6) 슬롯 배치와 클릭 해석 순수 규칙 (ECONOMY.md 7.4.6, TRADE.md 3·7장).
 *
 * <p>거래 창은 모든 클릭·드래그(창 밖 OUTSIDE·크리에이티브 포함)를 취소한 뒤,
 * 허용된 일반 좌·우클릭만 값으로 변환해 이 클래스로 해석합니다.
 * Shift·숫자키·F·Q·더블클릭·드래그·꾸러미 우클릭은 모두 {@link Button#OTHER} 로 들어와 {@link Ignore} 됩니다.
 */
public final class ClickRules {

    /** 내 제시 칸 4×4 (좌측, 16칸) */
    public static final int[] MY_OFFER_SLOTS = {
            0, 1, 2, 3,
            9, 10, 11, 12,
            18, 19, 20, 21,
            27, 28, 29, 30
    };

    /** 상대 제시 칸 4×4 (우측, 16칸, 읽기 전용) */
    public static final int[] PARTNER_OFFER_SLOTS = {
            5, 6, 7, 8,
            14, 15, 16, 17,
            23, 24, 25, 26,
            32, 33, 34, 35
    };

    /** 중앙 열 및 하단 슬롯 번호 (ECONOMY.md 7.4.6) */
    public static final int SLOT_MY_MONEY = 4;
    public static final int SLOT_PARTNER_MONEY = 13;
    public static final int SLOT_COUNTDOWN = 22;
    public static final int SLOT_DIVIDER = 31;
    public static final int SLOT_READY = 40;
    public static final int SLOT_CANCEL = 49;

    /** 5줄 상태 바 */
    public static final int[] MY_STATUS_BAR = {36, 37, 38, 39};
    public static final int[] PARTNER_STATUS_BAR = {41, 42, 43, 44};

    /** 6줄 양쪽 정보 */
    public static final int SLOT_MY_INFO = 45;
    public static final int SLOT_PARTNER_INFO = 53;

    public enum Area {
        TOP, BOTTOM, OUTSIDE
    }

    public enum Button {
        LEFT, RIGHT, OTHER
    }

    public sealed interface Action permits Ignore, AddOfferAll, AddOfferOne,
            RemoveOfferAll, RemoveOfferOne, OpenMoneyDialog, ToggleReady, CancelTrade {
    }

    public record Ignore() implements Action {
        public static final Ignore INSTANCE = new Ignore();
    }

    /** 아래 내 인벤토리 칸 좌클릭: 그 칸 전부 제시 (이미 제시 중이면 전부로 맞춤) */
    public record AddOfferAll(int invSlot) implements Action {
    }

    /** 아래 내 인벤토리 칸 우클릭: 1개 추가 */
    public record AddOfferOne(int invSlot) implements Action {
    }

    /** 위 내 제시 칸 좌클릭: 빼기 */
    public record RemoveOfferAll(int offerIndex) implements Action {
    }

    /** 위 내 제시 칸 우클릭: 1개 빼기 */
    public record RemoveOfferOne(int offerIndex) implements Action {
    }

    public record OpenMoneyDialog() implements Action {
        public static final OpenMoneyDialog INSTANCE = new OpenMoneyDialog();
    }

    public record ToggleReady() implements Action {
        public static final ToggleReady INSTANCE = new ToggleReady();
    }

    public record CancelTrade() implements Action {
        public static final CancelTrade INSTANCE = new CancelTrade();
    }

    private ClickRules() {
    }

    /**
     * 클릭 영역·칸 번호·버튼을 순수 동작으로 해석합니다.
     *
     * @param area   클릭한 영역 ({@code TOP}: 거래 창 0..53, {@code BOTTOM}: 내 보관함 0..35, {@code OUTSIDE}: 창 밖)
     * @param slot   해당 인벤토리의 슬롯 번호
     * @param button 일반 좌/우클릭 외 모든 입력은 {@link Button#OTHER}
     */
    public static Action interpret(Area area, int slot, Button button) {
        if (area == null || button == null || area == Area.OUTSIDE || button == Button.OTHER) {
            return Ignore.INSTANCE;
        }
        if (area == Area.BOTTOM) {
            if (slot < 0 || slot >= OfferValidation.STORAGE_SIZE) {
                return Ignore.INSTANCE;
            }
            return button == Button.LEFT ? new AddOfferAll(slot) : new AddOfferOne(slot);
        }
        // TOP
        int offerIdx = myOfferIndex(slot);
        if (offerIdx >= 0) {
            return button == Button.LEFT ? new RemoveOfferAll(offerIdx) : new RemoveOfferOne(offerIdx);
        }
        return switch (slot) {
            case SLOT_MY_MONEY -> OpenMoneyDialog.INSTANCE;
            case SLOT_READY -> ToggleReady.INSTANCE;
            case SLOT_CANCEL -> CancelTrade.INSTANCE;
            default -> Ignore.INSTANCE;
        };
    }

    /** 위쪽 슬롯 번호가 내 제시 칸이면 0..15, 아니면 -1. */
    public static int myOfferIndex(int topSlot) {
        for (int i = 0; i < MY_OFFER_SLOTS.length; i++) {
            if (MY_OFFER_SLOTS[i] == topSlot) {
                return i;
            }
        }
        return -1;
    }

    /** 위쪽 슬롯 번호가 상대 제시 칸이면 0..15, 아니면 -1. */
    public static int partnerOfferIndex(int topSlot) {
        for (int i = 0; i < PARTNER_OFFER_SLOTS.length; i++) {
            if (PARTNER_OFFER_SLOTS[i] == topSlot) {
                return i;
            }
        }
        return -1;
    }
}
