package aussie.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.quarkiverse.httpproblem.HttpProblem;
import org.junit.jupiter.api.Test;

class AdminPaginationTest {

    @Test
    void validatesBounds() {
        assertDoesNotThrow(() -> AdminPagination.validate(1, 0));
        assertDoesNotThrow(() -> AdminPagination.validate(100, 100_000));
        assertThrows(HttpProblem.class, () -> AdminPagination.validate(0, 0));
        assertThrows(HttpProblem.class, () -> AdminPagination.validate(101, 0));
        assertThrows(HttpProblem.class, () -> AdminPagination.validate(1, -1));
        assertThrows(HttpProblem.class, () -> AdminPagination.validate(1, 100_001));
    }

    @Test
    void boundsOptionalLimits() {
        assertEquals(100, AdminPagination.boundedLimit(null));
        assertEquals(100, AdminPagination.boundedLimit(0));
        assertEquals(25, AdminPagination.boundedLimit(25));
        assertEquals(100, AdminPagination.boundedLimit(101));
    }
}
