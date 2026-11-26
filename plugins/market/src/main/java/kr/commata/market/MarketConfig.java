package kr.commata.market;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import kr.commata.market.core.GachaCatalog;
import kr.commata.market.core.MarketPeriods;
import kr.commata.market.core.RareCatalog;
import kr.commata.market.core.SellCatalog;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;

/**
 * {@code plugins/Market/config.yml} 읽기 및 검증 (ECONOMY.md 13.6, 14.7, AGENTS.md 8).
 *
 * <p>가격·가중치·한도는 정수만 허용합니다(소수 입력 거부). 희귀 상점·뽑기·매입은 서로 독립적으로 검증해
 * 설정 오류가 난 기능만 잠그고 경고 로그를 남깁니다.
 */
public final class MarketConfig {

    private final ZoneId zone;
    private final RareCatalog rare;
    private final GachaCatalog gacha;
    private final SellCatalog sell;

    private MarketConfig(ZoneId zone, RareCatalog rare, GachaCatalog gacha, SellCatalog sell) {
        this.zone = zone;
        this.rare = rare;
        this.gacha = gacha;
        this.sell = sell;
    }

    public static MarketConfig load(FileConfiguration config, Logger logger) {
        ZoneId zone = readZone(config.getString("timezone"), logger);
        RareCatalog rare = readRare(config.getConfigurationSection("rare-shop"), logger);
        GachaCatalog gacha = readGacha(config.getConfigurationSection("gacha"), logger);
        SellCatalog sell = readSell(config.getConfigurationSection("sell"), logger);
        return new MarketConfig(zone, rare, gacha, sell);
    }

    public ZoneId zone() {
        return zone;
    }

    public RareCatalog rare() {
        return rare;
    }

    public GachaCatalog gacha() {
        return gacha;
    }

    public SellCatalog sell() {
        return sell;
    }

    private static ZoneId readZone(String raw, Logger logger) {
        if (raw == null || raw.isBlank()) {
            return MarketPeriods.DEFAULT_ZONE;
        }
        try {
            return ZoneId.of(raw.trim());
        } catch (RuntimeException e) {
            logger.warning("config.yml timezone 잘못됨(" + raw + "), 기본값 Asia/Seoul 사용");
            return MarketPeriods.DEFAULT_ZONE;
        }
    }

    // ------------------------------------------------------------------ 희귀 상점

    private static RareCatalog readRare(ConfigurationSection sec, Logger logger) {
        if (sec == null) {
            return RareCatalog.locked("config.yml 에 rare-shop 섹션이 없습니다.");
        }
        boolean enabled = sec.getBoolean("enabled", true);
        if (!enabled) {
            return RareCatalog.of(false, 0, List.of());
        }
        Long bookLimitObj = strictInt(sec, "book-weekly-limit");
        if (bookLimitObj == null || bookLimitObj < 1 || bookLimitObj > Integer.MAX_VALUE) {
            String msg = "rare-shop.book-weekly-limit 는 1 이상의 정수여야 합니다.";
            logger.warning(msg);
            return RareCatalog.locked(msg);
        }
        int bookLimit = bookLimitObj.intValue();
        List<RareCatalog.Entry> entries = new ArrayList<>();

        ConfigurationSection resources = sec.getConfigurationSection("resources");
        if (resources != null) {
            for (String key : resources.getKeys(false)) {
                ConfigurationSection itemSec = resources.getConfigurationSection(key);
                if (itemSec == null) {
                    continue;
                }
                String matName = key.toUpperCase(Locale.ROOT);
                Material mat = Material.matchMaterial(matName);
                if (mat == null || !mat.isItem()) {
                    String msg = "rare-shop.resources." + key + ": 없는 아이템 재질 " + matName;
                    logger.warning(msg);
                    return RareCatalog.locked(msg);
                }
                Long slot = strictInt(itemSec, "slot");
                Long price = strictInt(itemSec, "price");
                Long limit = strictInt(itemSec, "weekly-limit");
                if (slot == null || price == null || limit == null) {
                    String msg = "rare-shop.resources." + key + ": slot·price·weekly-limit 은 정수여야 합니다.";
                    logger.warning(msg);
                    return RareCatalog.locked(msg);
                }
                String name = itemSec.getString("name", matName);
                entries.add(new RareCatalog.Entry(
                        matName, RareCatalog.Kind.RESOURCE, name, matName, null, 0,
                        slot.intValue(), price, limit.intValue()));
            }
        }

        ConfigurationSection books = sec.getConfigurationSection("books");
        if (books != null) {
            for (String key : books.getKeys(false)) {
                ConfigurationSection bookSec = books.getConfigurationSection(key);
                if (bookSec == null) {
                    continue;
                }
                String enchantKey = bookSec.getString("enchant", "").trim().toLowerCase(Locale.ROOT);
                if (findEnchantment(enchantKey) == null) {
                    String msg = "rare-shop.books." + key + ": 없는 인첸트 " + enchantKey;
                    logger.warning(msg);
                    return RareCatalog.locked(msg);
                }
                Long level = strictInt(bookSec, "level");
                Long slot = strictInt(bookSec, "slot");
                Long price = strictInt(bookSec, "price");
                if (level == null || slot == null || price == null) {
                    String msg = "rare-shop.books." + key + ": level·slot·price 는 정수여야 합니다.";
                    logger.warning(msg);
                    return RareCatalog.locked(msg);
                }
                String name = bookSec.getString("name", enchantKey + " " + level);
                entries.add(new RareCatalog.Entry(
                        key, RareCatalog.Kind.BOOK, name, null, enchantKey, level.intValue(),
                        slot.intValue(), price, bookLimit));
            }
        }

        RareCatalog catalog = RareCatalog.of(true, bookLimit, entries);
        if (!catalog.available()) {
            logger.warning("희귀 상점 잠김: " + catalog.lockReason());
        }
        return catalog;
    }

    // ------------------------------------------------------------------ 뽑기

    private static GachaCatalog readGacha(ConfigurationSection sec, Logger logger) {
        if (sec == null) {
            return GachaCatalog.locked("config.yml 에 gacha 섹션이 없습니다.");
        }
        boolean enabled = sec.getBoolean("enabled", true);
        if (!enabled) {
            return GachaCatalog.of(false, 0, 0, 1, List.of());
        }
        Long price = strictInt(sec, "price");
        Long dailyLimit = strictInt(sec, "daily-limit");
        Long pityThreshold = strictInt(sec, "pity-threshold");
        if (price == null || dailyLimit == null || pityThreshold == null) {
            String msg = "gacha 의 price·daily-limit·pity-threshold 는 정수여야 합니다.";
            logger.warning(msg);
            return GachaCatalog.locked(msg);
        }
        ConfigurationSection tiersSec = sec.getConfigurationSection("tiers");
        if (tiersSec == null) {
            String msg = "gacha.tiers 섹션이 없습니다.";
            logger.warning(msg);
            return GachaCatalog.locked(msg);
        }
        List<GachaCatalog.Tier> tiers = new ArrayList<>();
        for (String tierId : tiersSec.getKeys(false)) {
            ConfigurationSection tSec = tiersSec.getConfigurationSection(tierId);
            if (tSec == null) {
                continue;
            }
            Long weight = strictInt(tSec, "weight");
            Long slot = strictInt(tSec, "slot");
            if (weight == null || slot == null) {
                String msg = "gacha.tiers." + tierId + ": weight·slot 은 정수여야 합니다.";
                logger.warning(msg);
                return GachaCatalog.locked(msg);
            }
            String iconName = tSec.getString("icon", "BOOK").toUpperCase(Locale.ROOT);
            Material icon = Material.matchMaterial(iconName);
            if (icon == null || !icon.isItem()) {
                String msg = "gacha.tiers." + tierId + ": 없는 아이콘 재질 " + iconName;
                logger.warning(msg);
                return GachaCatalog.locked(msg);
            }
            List<Map<?, ?>> rawBooks = tSec.getMapList("books");
            List<GachaCatalog.Book> books = new ArrayList<>(rawBooks.size());
            for (Map<?, ?> raw : rawBooks) {
                Object enchObj = raw.get("enchant");
                Object lvlObj = raw.get("level");
                Object nameObj = raw.get("name");
                String enchKey = enchObj == null ? "" : enchObj.toString().trim().toLowerCase(Locale.ROOT);
                Long lvl = asStrictInt(lvlObj);
                if (findEnchantment(enchKey) == null || lvl == null || lvl < 1) {
                    String msg = "gacha.tiers." + tierId + ": 잘못된 책 정의 (enchant=" + enchKey + ", level=" + lvlObj + ")";
                    logger.warning(msg);
                    return GachaCatalog.locked(msg);
                }
                String display = nameObj == null ? enchKey + " " + lvl : nameObj.toString();
                books.add(new GachaCatalog.Book(enchKey, lvl.intValue(), display));
            }
            tiers.add(new GachaCatalog.Tier(
                    tierId,
                    tSec.getString("name", tierId),
                    weight.intValue(),
                    tSec.getString("color", "#FFFFFF"),
                    iconName,
                    slot.intValue(),
                    tSec.getBoolean("pity-target", false),
                    books
            ));
        }
        GachaCatalog catalog = GachaCatalog.of(true, price, dailyLimit.intValue(), pityThreshold.intValue(), tiers);
        if (!catalog.available()) {
            logger.warning("뽑기 잠김: " + catalog.lockReason());
        }
        return catalog;
    }

    // ------------------------------------------------------------------ 매입

    private static SellCatalog readSell(ConfigurationSection sec, Logger logger) {
        if (sec == null) {
            return SellCatalog.locked("config.yml 에 sell 섹션이 없습니다.");
        }
        boolean enabled = sec.getBoolean("enabled", true);
        if (!enabled) {
            return SellCatalog.of(false, 0, List.of());
        }
        Long dailyLimit = strictInt(sec, "daily-limit");
        if (dailyLimit == null) {
            String msg = "sell.daily-limit 는 정수여야 합니다.";
            logger.warning(msg);
            return SellCatalog.locked(msg);
        }
        ConfigurationSection catsSec = sec.getConfigurationSection("categories");
        if (catsSec == null) {
            String msg = "sell.categories 섹션이 없습니다.";
            logger.warning(msg);
            return SellCatalog.locked(msg);
        }
        List<SellCatalog.Category> categories = new ArrayList<>();
        for (String jobId : catsSec.getKeys(false)) {
            ConfigurationSection cSec = catsSec.getConfigurationSection(jobId);
            if (cSec == null) {
                continue;
            }
            Long tabSlot = strictInt(cSec, "tab-slot");
            if (tabSlot == null) {
                String msg = "sell.categories." + jobId + ".tab-slot 는 정수여야 합니다.";
                logger.warning(msg);
                return SellCatalog.locked(msg);
            }
            String iconName = cSec.getString("icon", "IRON_PICKAXE").toUpperCase(Locale.ROOT);
            String joinedIconName = cSec.getString("joined-icon", iconName).toUpperCase(Locale.ROOT);
            if (Material.matchMaterial(iconName) == null || Material.matchMaterial(joinedIconName) == null) {
                String msg = "sell.categories." + jobId + ": 없는 탭 아이콘 재질";
                logger.warning(msg);
                return SellCatalog.locked(msg);
            }
            ConfigurationSection itemsSec = cSec.getConfigurationSection("items");
            if (itemsSec == null) {
                String msg = "sell.categories." + jobId + ".items 가 없습니다.";
                logger.warning(msg);
                return SellCatalog.locked(msg);
            }
            List<SellCatalog.Item> items = new ArrayList<>();
            for (String itemKey : itemsSec.getKeys(false)) {
                ConfigurationSection iSec = itemsSec.getConfigurationSection(itemKey);
                if (iSec == null) {
                    continue;
                }
                String matName = itemKey.toUpperCase(Locale.ROOT);
                Material mat = Material.matchMaterial(matName);
                if (mat == null || !mat.isItem()) {
                    String msg = "sell.categories." + jobId + ".items." + itemKey + ": 없는 아이템 재질";
                    logger.warning(msg);
                    return SellCatalog.locked(msg);
                }
                Long unit = strictInt(iSec, "unit");
                Long basePrice = strictInt(iSec, "base-price");
                Long bonusPrice = strictInt(iSec, "bonus-price");
                Long maxPrice = strictInt(iSec, "max-price");
                if (unit == null || basePrice == null || bonusPrice == null || maxPrice == null) {
                    String msg = "sell.categories." + jobId + ".items." + itemKey
                            + ": unit·base-price·bonus-price·max-price 는 모두 정수여야 합니다.";
                    logger.warning(msg);
                    return SellCatalog.locked(msg);
                }
                items.add(new SellCatalog.Item(
                        matName, jobId, iSec.getString("name", matName),
                        unit.intValue(), basePrice, bonusPrice, maxPrice));
            }
            categories.add(new SellCatalog.Category(
                    jobId,
                    cSec.getString("name", jobId),
                    cSec.getString("bonus-label", "+100%"),
                    tabSlot.intValue(),
                    iconName,
                    joinedIconName,
                    cSec.getBoolean("glow-when-joined", false),
                    items
            ));
        }
        SellCatalog catalog = SellCatalog.of(true, dailyLimit, categories);
        if (!catalog.available()) {
            logger.warning("서버 매입 잠김: " + catalog.lockReason());
        }
        return catalog;
    }

    /** Paper 26.3 Registry 기반 인첸트 조회. */
    public static Enchantment findEnchantment(String minecraftKey) {
        if (minecraftKey == null || minecraftKey.isBlank()) {
            return null;
        }
        Registry<Enchantment> registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT);
        return registry.get(NamespacedKey.minecraft(minecraftKey.toLowerCase(Locale.ROOT)));
    }

    private static Long strictInt(ConfigurationSection sec, String path) {
        if (!sec.contains(path)) {
            return null;
        }
        return asStrictInt(sec.get(path));
    }

    /** YAML 값이 소수·문자열이 아닌 정확한 정수(Integer/Long)인지 검사 (ECONOMY.md 2.2, 14.7). */
    private static Long asStrictInt(Object raw) {
        if (raw instanceof Integer i) {
            return i.longValue();
        }
        if (raw instanceof Long l) {
            return l;
        }
        return null;
    }
}
