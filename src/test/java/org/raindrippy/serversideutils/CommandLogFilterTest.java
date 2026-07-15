package org.raindrippy.serversideutils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for {@link CommandLogFilter}: matching logic plus the real Log4j2 delivery paths. */
class CommandLogFilterTest {

    private final CommandLogFilter filter =
            new CommandLogFilter(Collections.singleton("sync"));

    // ---- pure matching logic -----------------------------------------------------------------

    @Nested
    @DisplayName("shouldSuppress (matching logic)")
    class Matching {

        @Test
        @DisplayName("null message -> not suppressed")
        void nullMessage() {
            assertFalse(filter.shouldSuppress(null));
        }

        @Test
        @DisplayName("hidden command in an 'issued server command' line -> suppressed")
        void hiddenIssuedCommandSuppressed() {
            assertTrue(filter.shouldSuppress("Bob issued server command: /sync user pass"));
        }

        @Test
        @DisplayName("hidden command in an 'executed command' line -> suppressed")
        void hiddenExecutedCommandSuppressed() {
            assertTrue(filter.shouldSuppress("Bob executed command: /sync user pass"));
        }

        @Test
        @DisplayName("hidden command WITHOUT the marker phrase -> not suppressed")
        void hiddenWithoutMarkerNotSuppressed() {
            assertFalse(filter.shouldSuppress("player typed /sync in chat"));
        }

        @Test
        @DisplayName("non-hidden command in a command line -> not suppressed")
        void nonHiddenCommandNotSuppressed() {
            assertFalse(filter.shouldSuppress("Bob issued server command: /warn Alice spam"));
        }

        @Test
        @DisplayName("the command name is matched case-insensitively")
        void commandNameCaseInsensitive() {
            assertTrue(filter.shouldSuppress("Bob issued server command: /SYNC user pass"));
        }

        @Test
        @DisplayName("an unrelated command sharing a prefix ('/syncfoo') is not suppressed")
        void doesNotOverMatchPrefix() {
            assertFalse(filter.shouldSuppress("Bob issued server command: /syncfoo bar"),
                    "filter should not suppress an unrelated command that shares a prefix");
        }
    }

    // ---- Log4j2 delivery paths ---------------------------------------------------------------
    // These guard the actual integration surface: the previous version passed its unit tests
    // while suppressing nothing at runtime because it was wired into the wrong logging pipeline.

    @Nested
    @DisplayName("Log4j2 filter surface")
    class Log4jSurface {

        private LogEvent eventWith(String formattedMessage) {
            // Mock rather than build a concrete Log4jLogEvent: the test classpath pulls a
            // log4j-api from paper-api that skews against log4j-core's event impl. The filter
            // only reads getMessage(), so a stub exercises the real code path faithfully.
            LogEvent event = mock(LogEvent.class);
            when(event.getMessage()).thenReturn(new SimpleMessage(formattedMessage));
            return event;
        }

        @Test
        @DisplayName("fully-built LogEvent (appender path): hidden command -> DENY")
        void logEventHiddenDenied() {
            assertEquals(Filter.Result.DENY,
                    filter.filter(eventWith("Bob issued server command: /sync user pass")));
        }

        @Test
        @DisplayName("fully-built LogEvent: unrelated line -> NEUTRAL (pass through)")
        void logEventOtherNeutral() {
            assertEquals(Filter.Result.NEUTRAL,
                    filter.filter(eventWith("Bob issued server command: /warn Alice spam")));
        }

        @Test
        @DisplayName("pre-format logger path ({msg}, params): command in a param -> DENY")
        void preFormatParamsDenied() {
            // Mirrors how the server core logs the echo: a parameterized pattern with the
            // command supplied as an argument. The filter must format before matching.
            Filter.Result result = filter.filter(
                    null, Level.INFO, null,
                    "{} issued server command: {}", "Bob", "/sync user pass");
            assertEquals(Filter.Result.DENY, result);
        }

        @Test
        @DisplayName("pre-format logger path: unrelated command in a param -> NEUTRAL")
        void preFormatParamsNeutral() {
            Filter.Result result = filter.filter(
                    null, Level.INFO, null,
                    "{} issued server command: {}", "Bob", "/warn Alice spam");
            assertEquals(Filter.Result.NEUTRAL, result);
        }
    }
}
