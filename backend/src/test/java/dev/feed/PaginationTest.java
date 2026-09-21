package dev.feed;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

class PaginationTest {
    @Test
    void rejectsInvalidCursorsAndLimits() {
        assertThrows(ResponseStatusException.class, () -> Api.validatePage(0, 20));
        assertThrows(ResponseStatusException.class, () -> Api.validatePage(9007199254740992L, 20));
        assertThrows(ResponseStatusException.class, () -> Api.validatePage(10, 101));
        assertThrows(ResponseStatusException.class, () -> Api.validatePage(10, 0));
    }

    @Test
    void acceptsBounds() {
        assertDoesNotThrow(() -> Api.validatePage(9007199254740991L, 100));
        assertDoesNotThrow(() -> Api.validatePage(1, 1));
    }
}
