package kr.commata.trade.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import kr.commata.trade.core.Journal;

/**
 * 하루 단위 거래 로그({@code plugins/Trade/trades-YYYY-MM-DD.log}, 한 줄 JSON) 기록기 (TRADE.md 9·10장 7).
 */
public final class TradeLogger {

    private final Path dataDir;
    private final Logger logger;

    public TradeLogger(Path dataDir, Logger logger) {
        this.dataDir = dataDir;
        this.logger = logger;
    }

    public void log(Journal.Entry entry, String result, String reason, long nowMs, ZoneId zone) {
        String line = formatJsonLine(
                nowMs,
                entry.tradeId(),
                entry.playerA(), entry.nameA(),
                entry.playerB(), entry.nameB(),
                entry.summaryFromA(), entry.summaryFromB(),
                entry.aPays(), entry.bPays(),
                entry.bankTxAId(), entry.bankTxBId(),
                result,
                reason == null ? entry.cancelReason() : reason,
                entry.recovered()
        );
        LocalDate date = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate();
        Path file = dataDir.resolve("trades-" + date + ".log");
        try {
            Files.createDirectories(dataDir);
            Files.writeString(file, line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        } catch (IOException e) {
            logger.warning("거래 로그 기록 실패 (" + file.getFileName() + ", tradeId=" + entry.tradeId() + "): "
                    + e.getMessage());
        }
    }

    public static String formatJsonLine(
            long timeMs,
            UUID tradeId,
            UUID aUuid, String aName,
            UUID bUuid, String bName,
            List<String> aItems, List<String> bItems,
            long aPays, long bPays,
            long txAId, long txBId,
            String result,
            String reason,
            boolean recovered
    ) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        field(sb, "time", Instant.ofEpochMilli(timeMs).toString());
        sb.append(',');
        field(sb, "tradeId", tradeId.toString());
        sb.append(',');
        field(sb, "aUuid", aUuid.toString());
        sb.append(',');
        field(sb, "aName", aName);
        sb.append(',');
        field(sb, "bUuid", bUuid.toString());
        sb.append(',');
        field(sb, "bName", bName);
        sb.append(',');
        arrayField(sb, "aItems", aItems);
        sb.append(',');
        arrayField(sb, "bItems", bItems);
        sb.append(',');
        numField(sb, "aPays", aPays);
        sb.append(',');
        numField(sb, "bPays", bPays);
        sb.append(',');
        numField(sb, "txAId", txAId);
        sb.append(',');
        numField(sb, "txBId", txBId);
        sb.append(',');
        field(sb, "result", result);
        sb.append(',');
        field(sb, "reason", reason == null ? "" : reason);
        sb.append(',');
        sb.append("\"recovered\":").append(recovered);
        sb.append('}');
        return sb.toString();
    }

    private static void field(StringBuilder sb, String key, String value) {
        sb.append('"').append(escape(key)).append("\":\"").append(escape(value == null ? "" : value)).append('"');
    }

    private static void numField(StringBuilder sb, String key, long value) {
        sb.append('"').append(escape(key)).append("\":").append(value);
    }

    private static void arrayField(StringBuilder sb, String key, List<String> items) {
        sb.append('"').append(escape(key)).append("\":[");
        if (items != null) {
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append('"').append(escape(items.get(i))).append('"');
            }
        }
        sb.append(']');
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
