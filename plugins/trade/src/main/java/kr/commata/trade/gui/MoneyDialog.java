package kr.commata.trade.gui;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import kr.commata.common.gui.Items;
import kr.commata.trade.core.OfferValidation;
import kr.commata.trade.core.TradeSession;
import kr.commata.trade.service.TradeService;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Paper 26.3-258 Dialog API 기반 제시 금액 입력 대화창 (ECONOMY.md 7.4.6a, TRADE.md 7·10장).
 *
 * <p>Paper 26.3-258 동작 확인:
 * <ul>
 *   <li>{@link DialogType#confirmation(ActionButton, ActionButton)} 은 확인/취소 두 버튼을 표시하며,
 *       {@code canCloseWithEscape(true)} 에서 ESC 로 닫을 때 바닐라 클라이언트는 {@code noButton} 액션을 보냅니다.</li>
 *   <li>{@link DialogAction#customClick} 콜백은 {@link ClickCallback.Options} 의 {@code uses(1)} + {@code lifetime}
 *       으로 일회성·만료 제한을 걸고, 추가로 {@link AtomicBoolean} 과 {@code (tradeId, dialogToken, capturedGen)} 으로
 *       중복·지연 응답을 이중 차단합니다.</li>
 *   <li>거래 창에서 대화창으로 넘어갈 때는 {@code TradeService} 가 먼저 {@code player.closeInventory()} 를 호출해
 *       {@code InventoryCloseEvent(Reason.PLUGIN)} 를 동기 소비(1회 예외)한 직후 {@code player.showDialog(...)} 를 부릅니다.</li>
 * </ul>
 */
public final class MoneyDialog {

    private static final String INPUT_KEY = "amount";
    private static final int MAX_INPUT_LENGTH = 13;
    private static final int INPUT_WIDTH = 200;

    private MoneyDialog() {
    }

    public static void open(Plugin plugin, TradeService service, Player player, TradeSession session,
                            TradeSession.Side side, UUID dialogToken, long currentBalance) {
        UUID tradeId = session.tradeId();
        long capturedGen = session.gen();
        long currentOffer = session.participant(side).money();
        int timeoutSec = service.config().dialogTimeoutSeconds();
        Server server = plugin.getServer();

        AtomicBoolean handled = new AtomicBoolean(false);
        ClickCallback.Options options = ClickCallback.Options.builder()
                .uses(1)
                .lifetime(Duration.ofSeconds(timeoutSec))
                .build();

        ActionButton confirmButton = ActionButton.builder(Items.text("<#55FF55>확인"))
                .action(DialogAction.customClick((response, audience) -> {
                    if (!handled.compareAndSet(false, true)) {
                        return;
                    }
                    String raw = response.getText(INPUT_KEY);
                    runOnMain(plugin, server, () -> {
                        Player online = server.getPlayer(player.getUniqueId());
                        if (online != null && online.isOnline()) {
                            service.onDialogSubmit(online, tradeId, dialogToken, capturedGen, raw);
                        }
                    });
                }, options))
                .build();

        ActionButton cancelButton = ActionButton.builder(Items.text("<#AAAAAA>취소"))
                .action(DialogAction.customClick((response, audience) -> {
                    if (!handled.compareAndSet(false, true)) {
                        return;
                    }
                    runOnMain(plugin, server, () -> {
                        Player online = server.getPlayer(player.getUniqueId());
                        if (online != null && online.isOnline()) {
                            service.onDialogCancel(online, tradeId, dialogToken);
                        }
                    });
                }, options))
                .build();

        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Items.text("<#F5C542>제시 금액 입력"))
                        .canCloseWithEscape(true)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .body(List.of(
                                DialogBody.plainMessage(Items.text(
                                        "<#AAAAAA>내 잔액: <#FFDF6B>" + OfferValidation.formatWon(currentBalance))),
                                DialogBody.plainMessage(Items.text(
                                        "<#AAAAAA>0을 입력하면 금액 없이 거래합니다."))
                        ))
                        .inputs(List.of(
                                DialogInput.text(INPUT_KEY, Items.text("<#F5C542>금액 (원)"))
                                        .width(INPUT_WIDTH)
                                        .labelVisible(true)
                                        .initial(Long.toString(currentOffer))
                                        .maxLength(MAX_INPUT_LENGTH)
                                        .build()
                        ))
                        .build())
                .type(DialogType.confirmation(confirmButton, cancelButton)));

        player.showDialog(dialog);
    }

    private static void runOnMain(Plugin plugin, Server server, Runnable task) {
        if (server.isPrimaryThread()) {
            task.run();
        } else {
            server.getScheduler().runTask(plugin, task);
        }
    }
}
