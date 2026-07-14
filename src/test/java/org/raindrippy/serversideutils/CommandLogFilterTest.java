package org.raindrippy.serversideutils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Pure-logic tests for {@link CommandLogFilter#isLoggable}. */
class CommandLogFilterTest {

    private final CommandLogFilter filter =
            new CommandLogFilter(Collections.singleton("sync"));

    private static LogRecord record(String message) {
        return new LogRecord(Level.INFO, message);
    }

    @Test
    @DisplayName("null message -> loggable")
    void nullMessage() {
        assertTrue(filter.isLoggable(record(null)));
    }

    @Test
    @DisplayName("hidden command in a 'issued server command' line -> suppressed")
    void hiddenIssuedCommandSuppressed() {
        assertFalse(filter.isLoggable(record("Bob issued server command: /sync user pass")));
    }

    @Test
    @DisplayName("hidden command in an 'executed command' line -> suppressed")
    void hiddenExecutedCommandSuppressed() {
        assertFalse(filter.isLoggable(record("Bob executed command: /sync user pass")));
    }

    @Test
    @DisplayName("hidden command WITHOUT the marker phrase -> loggable")
    void hiddenWithoutMarkerLoggable() {
        assertTrue(filter.isLoggable(record("player typed /sync in chat")));
    }

    @Test
    @DisplayName("non-hidden command in a command line -> loggable")
    void nonHiddenCommandLoggable() {
        assertTrue(filter.isLoggable(record("Bob issued server command: /warn Alice spam")));
    }

    @Test
    @DisplayName("the command name is matched case-insensitively (marker phrase kept literal)")
    void commandNameCaseInsensitive() {
        assertFalse(filter.isLoggable(record("Bob issued server command: /SYNC user pass")));
    }

    @Test
    @DisplayName("an unrelated command sharing a prefix ('/syncfoo') is not suppressed")
    void doesNotOverMatchPrefix() {
        // A distinct command that merely starts with a hidden command's name should still be logged.
        Set<String> hidden = Collections.singleton("sync");
        CommandLogFilter f = new CommandLogFilter(hidden);
        // Correct behavior: /syncfoo is not the hidden /sync command -> should be loggable.
        assertTrue(f.isLoggable(record("Bob issued server command: /syncfoo bar")),
                "filter should not suppress an unrelated command that shares a prefix");
    }
}
