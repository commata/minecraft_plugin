package kr.commata.market.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class CatalogsAndMathTest {

    // ------------------------------------------------------------------ 뽑기 검증·천장

    private static GachaCatalog sampleGacha(int commonWeight) {
        return GachaCatalog.of(true, 2000, 20, 80, List.of(
                new GachaCatalog.Tier("common", "일반", commonWeight, "#AAAAAA", "BOOK", 10, false,
                        List.of(new GachaCatalog.Book("protection", 1, "보호 I"))),
                new GachaCatalog.Tier("rare", "희귀", 3000, "#55FFFF", "ENCHANTED_BOOK", 12, false,
                        List.of(new GachaCatalog.Book("efficiency", 4, "효율 IV"))),
                new GachaCatalog.Tier("epic", "영웅", 900, "#D47BFF", "AMETHYST_SHARD", 14, false,
                        List.of(new GachaCatalog.Book("efficiency", 5, "효율 V"))),
                new GachaCatalog.Tier("legendary", "전설", 100, "#F5C542", "NETHER_STAR", 16, true,
                        List.of(new GachaCatalog.Book("mending", 1, "수선"),
                                new GachaCatalog.Book("soul_speed", 3, "영혼 가속 III")))
        ));
    }

    @Test
    void gachaLocksWhenWeightSumIsNot10000() {
        assertTrue(sampleGacha(6000).available());
        GachaCatalog broken = sampleGacha(5999);
        assertFalse(broken.available());
        assertTrue(broken.lockReason().contains("10000"));
    }

    @Test
    void gachaLocksWhenTierHasNoBooks() {
        GachaCatalog broken = GachaCatalog.of(true, 2000, 20, 80, List.of(
                new GachaCatalog.Tier("legendary", "전설", 10_000, "#F5C542", "NETHER_STAR", 16, true, List.of())
        ));
        assertFalse(broken.available());
    }

    @Test
    void gachaPityTriggersOn80thPullAndResetsCounterInTenPull() {
        GachaCatalog gacha = sampleGacha(6000);
        // 항상 일반(roll=0)이 나오는 난수로 테스트 → 78에서 시작해 10회 뽑으면 2번째(인덱스 1)에 천장 확정 후 0으로 리셋
        Random alwaysCommon = new Random() {
            @Override
            public int nextInt(int bound) {
                return 0;
            }
        };
        List<GachaCatalog.Draw> draws = gacha.roll(10, 78, alwaysCommon);
        assertEquals(10, draws.size());
        assertEquals("common", draws.get(0).tierId());
        assertEquals(79, draws.get(0).pityAfter());
        assertFalse(draws.get(0).pityTriggered());

        assertEquals("legendary", draws.get(1).tierId());
        assertEquals(0, draws.get(1).pityAfter());
        assertTrue(draws.get(1).pityTriggered());

        // 남은 8회는 0부터 다시 누적 → 최종 8
        assertEquals(8, draws.get(9).pityAfter());
    }

    // ------------------------------------------------------------------ 매입 검증·묶음 계산

    @Test
    void sellCatalogRejectsBonusAboveMaxPrice() {
        SellCatalog bad = SellCatalog.of(true, 5000, List.of(
                new SellCatalog.Category("miner", "광부", "+100%", 2, "IRON_PICKAXE", "DIAMOND_PICKAXE", false,
                        List.of(new SellCatalog.Item("EMERALD", "miner", "에메랄드", 1, 25, 76, 75)))
        ));
        assertFalse(bad.available());
    }

    @Test
    void sellMathCalculatesBundlesSeparatelyBonusFirstWithoutMixing() {
        // 감자 16개 단위: 기본 4원, 보너스 8원.
        // 보너스 10개 + 기본 10개 = 합 20개여도 한 묶음 안에서 섞지 않으므로 0묶음 (ECONOMY.md 14.3)
        SellMath.Plan mixedTooSmall = SellMath.calculate(64, 10, 10, 16, 4, 8, 5000, Integer.MAX_VALUE);
        assertTrue(mixedTooSmall.isEmpty());

        // 보너스 20개(1묶음), 기본 35개(2묶음), 인벤토리 40개(최대 2묶음) → 보너스 1묶음(8원) + 기본 1묶음(4원) = 12원
        SellMath.Plan both = SellMath.calculate(40, 20, 35, 16, 4, 8, 5000, Integer.MAX_VALUE);
        assertEquals(1, both.bonusBundles());
        assertEquals(1, both.baseBundles());
        assertEquals(16, both.bonusAmount());
        assertEquals(16, both.baseAmount());
        assertEquals(8L, both.bonusTotal());
        assertEquals(4L, both.baseTotal());
        assertEquals(12L, both.totalMoney());

        // 좌클릭 1묶음은 보너스 1묶음만
        SellMath.Plan one = SellMath.calculate(40, 20, 35, 16, 4, 8, 5000, 1);
        assertEquals(1, one.bonusBundles());
        assertEquals(0, one.baseBundles());
        assertEquals(8L, one.totalMoney());

        // 남은 한도 10원: 보너스 1묶음(8원) 후 2원 남음 → 기본 1묶음(4원) 불가 → 보너스 1묶음만
        SellMath.Plan capped = SellMath.calculate(64, 64, 64, 16, 4, 8, 10, Integer.MAX_VALUE);
        assertEquals(1, capped.bonusBundles());
        assertEquals(0, capped.baseBundles());
        assertEquals(8L, capped.totalMoney());
    }

    @Test
    void gaugeFilledUsesFloorOfSevenSlots() {
        assertEquals(0, SellMath.gaugeFilled(0, 5000));
        assertEquals(0, SellMath.gaugeFilled(714, 5000));
        assertEquals(1, SellMath.gaugeFilled(715, 5000));
        assertEquals(3, SellMath.gaugeFilled(2500, 5000));
        assertEquals(7, SellMath.gaugeFilled(5000, 5000));
        assertEquals(7, SellMath.gaugeFilled(6000, 5000));
    }

    // ------------------------------------------------------------------ 토큰·payload

    @Test
    void appliedTxTokensKeepLatest32AndSupportExactAndAnyAttempt() {
        String raw = "";
        for (int i = 1; i <= 40; i++) {
            raw = AppliedTxTokens.append(raw, AppliedTxTokens.token("tx-" + i, 1));
        }
        assertEquals(AppliedTxTokens.MAX_ENTRIES, AppliedTxTokens.parse(raw).size());
        assertFalse(AppliedTxTokens.containsAnyAttempt(raw, "tx-1"));
        assertTrue(AppliedTxTokens.containsExact(raw, "tx-40", 1));
        assertFalse(AppliedTxTokens.containsExact(raw, "tx-40", 2));
        assertTrue(AppliedTxTokens.containsAnyAttempt(raw, "tx-40"));

        String removed = AppliedTxTokens.remove(raw, AppliedTxTokens.token("tx-40", 1));
        assertFalse(AppliedTxTokens.containsAnyAttempt(removed, "tx-40"));
    }

    @Test
    void orderPayloadRoundTripsRareAndGacha() {
        RareCatalog.Entry res = new RareCatalog.Entry(
                "DIAMOND", RareCatalog.Kind.RESOURCE, "다이아몬드", "DIAMOND", null, 0, 11, 1500, 16);
        String encRes = OrderPayload.encodeRareResource(res);
        assertTrue(OrderPayload.isRareResource(encRes));
        assertEquals("DIAMOND", OrderPayload.decodeRareResource(encRes).material());

        RareCatalog.Entry book = new RareCatalog.Entry(
                "efficiency_4", RareCatalog.Kind.BOOK, "효율 IV", null, "efficiency", 4, 19, 3000, 5);
        String encBook = OrderPayload.encodeRareBook(book);
        assertTrue(OrderPayload.isRareBook(encBook));
        assertEquals(4, OrderPayload.decodeRareBook(encBook).level());

        List<GachaCatalog.Draw> draws = sampleGacha(6000).roll(3, 0, new Random(42));
        String encGacha = OrderPayload.encodeGacha(draws, 2000);
        List<OrderPayload.GachaEntry> decoded = OrderPayload.decodeGacha(encGacha);
        assertEquals(3, decoded.size());
        assertEquals(draws.get(0).enchantKey(), decoded.get(0).enchantKey());
        assertEquals(2000L, decoded.get(2).unitPrice());
    }
}
