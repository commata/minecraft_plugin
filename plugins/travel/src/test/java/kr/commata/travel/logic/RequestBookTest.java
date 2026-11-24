package kr.commata.travel.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.commata.travel.model.TeleportRequest;
import org.junit.jupiter.api.Test;

class RequestBookTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final Duration RESEND = Duration.ofSeconds(10);

    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID c = UUID.randomUUID();
    private final RequestBook book = new RequestBook();

    private RequestBook.SendResult send(UUID from, UUID to, long now) {
        return book.send(from, name(from), to, name(to), now, TIMEOUT, RESEND);
    }

    private String name(UUID id) {
        return id.equals(a) ? "Alice" : id.equals(b) ? "Bob" : "Carol";
    }

    @Test
    void sendAndAccept() {
        var sent = assertInstanceOf(RequestBook.SendResult.Sent.class, send(a, b, 1_000));
        assertEquals(Optional.empty(), sent.replaced());
        assertEquals(61_000, sent.request().expiresAt());
        assertEquals(List.of(sent.request()), book.incoming(b));

        assertEquals(Optional.of(sent.request()), book.take(b, Optional.empty()));
        assertEquals(Optional.empty(), book.outgoing(a));
        assertEquals(List.of(), book.incoming(b));
    }

    @Test
    void cannotRequestSelf() {
        assertThrows(IllegalArgumentException.class, () -> send(a, a, 0));
    }

    @Test
    void onlyOneOutgoingRequestAtATime() {
        var first = assertInstanceOf(RequestBook.SendResult.Sent.class, send(a, b, 0)).request();
        var second = assertInstanceOf(RequestBook.SendResult.Sent.class, send(a, c, 1_000));
        assertEquals(Optional.of(first), second.replaced());
        assertEquals(List.of(), book.incoming(b));
        assertEquals(1, book.incoming(c).size());
    }

    @Test
    void sameTargetTwiceIsPendingThenRateLimited() {
        send(a, b, 0);
        assertInstanceOf(RequestBook.SendResult.AlreadyPending.class, send(a, b, 2_000));

        book.take(b, Optional.empty()); // 거절했다고 치고
        var tooSoon = assertInstanceOf(RequestBook.SendResult.TooSoon.class, send(a, b, 4_000));
        assertEquals(Duration.ofSeconds(6), tooSoon.retryIn());

        assertInstanceOf(RequestBook.SendResult.Sent.class, send(a, b, 10_000));
    }

    @Test
    void rateLimitIsPerTarget() {
        send(a, b, 0);
        assertInstanceOf(RequestBook.SendResult.Sent.class, send(a, c, 1_000));
    }

    @Test
    void acceptLatestOrByName() {
        TeleportRequest fromA = ((RequestBook.SendResult.Sent) send(a, c, 1_000)).request();
        TeleportRequest fromB = ((RequestBook.SendResult.Sent) send(b, c, 2_000)).request();
        assertEquals(List.of(fromB, fromA), book.incoming(c));

        assertEquals(Optional.of(fromA), book.take(c, Optional.of("alice")));
        assertEquals(Optional.empty(), book.take(c, Optional.of("Alice")));
        assertEquals(Optional.of(fromB), book.take(c, Optional.empty()));
    }

    @Test
    void expiresAfterTimeout() {
        var req = ((RequestBook.SendResult.Sent) send(a, b, 0)).request();
        assertEquals(List.of(), book.expire(59_999, RESEND));
        assertEquals(List.of(req), book.expire(60_000, RESEND));
        assertEquals(Optional.empty(), book.outgoing(a));
        // 만료 뒤에는 같은 상대에게 바로 다시 보낼 수 있음 (10초 제한은 이미 지남)
        assertInstanceOf(RequestBook.SendResult.Sent.class, send(a, b, 60_000));
    }

    @Test
    void logoutRemovesBothDirections() {
        send(a, b, 0);
        send(b, c, 0);
        send(c, a, 0);
        assertEquals(2, book.removeInvolving(b).size());
        assertEquals(1, book.all().size());
    }

    @Test
    void toggleOffRemovesOnlyIncoming() {
        send(a, c, 0);
        send(b, c, 0);
        send(c, a, 0);
        assertEquals(2, book.removeIncoming(c).size());
        assertTrue(book.outgoing(c).isPresent());
    }

    @Test
    void progressShrinksToZero() {
        var req = ((RequestBook.SendResult.Sent) send(a, b, 0)).request();
        assertEquals(1f, req.progress(0));
        assertEquals(0.5f, req.progress(30_000), 1e-6);
        assertEquals(0f, req.progress(90_000));
        assertEquals(Duration.ofSeconds(15), req.remaining(45_000));
    }
}
