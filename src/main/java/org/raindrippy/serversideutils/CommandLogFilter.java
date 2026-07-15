package org.raindrippy.serversideutils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.ParameterizedMessage;

/**
 * Log4j2 filter that suppresses console/log-file lines echoing sensitive commands
 * (e.g. {@code /sync}, whose arguments are a username + password) so credentials never
 * land in the logs.
 *
 * <p>Paper/Spigot route all console logging through Log4j2, and the
 * "{@code issued server command:}" line is emitted by the server core on a Log4j2 logger.
 * A {@link java.util.logging.Filter} never sees those records — the previous implementation
 * hooked JUL and silently suppressed nothing. Filtering therefore has to happen at the
 * Log4j2 layer, which is what this class does.
 *
 * <p>Install it by adding it to the root {@link org.apache.logging.log4j.core.Logger}. When
 * attached to a logger, Log4j2 evaluates filters <em>before</em> the message is formatted, so
 * every overload below reconstitutes the fully-formatted text before matching.
 */
public class CommandLogFilter extends AbstractFilter {

    /** Only command-echo lines are candidates for suppression; kept literal, case-insensitive. */
    private static final Pattern MARKER = Pattern.compile(
            "issued server command:|executed command:", Pattern.CASE_INSENSITIVE);

    private final List<Pattern> hiddenPatterns;

    public CommandLogFilter(Set<String> hiddenCommands) {
        // Precompile once — the hot path runs on every single log event.
        List<Pattern> patterns = new ArrayList<>(hiddenCommands.size());
        for (String hiddenCmd : hiddenCommands) {
            // Whole-command match: "/sync" as its own token, not a prefix of "/syncfoo".
            patterns.add(Pattern.compile("/" + Pattern.quote(hiddenCmd) + "($|\\s)",
                    Pattern.CASE_INSENSITIVE));
        }
        this.hiddenPatterns = List.copyOf(patterns);
    }

    /**
     * Pure matching logic — package-visible and free of any running-server dependency so it can
     * be unit tested directly. Returns {@code true} when {@code message} must NOT be logged.
     */
    boolean shouldSuppress(String message) {
        if (message == null || !MARKER.matcher(message).find()) {
            return false;
        }
        for (Pattern p : hiddenPatterns) {
            if (p.matcher(message).find()) {
                return true;
            }
        }
        return false;
    }

    private Result decide(String message) {
        return shouldSuppress(message) ? Result.DENY : Result.NEUTRAL;
    }

    // ---- Log4j2 Filter surface -------------------------------------------------------------
    // A filter attached to a Logger is consulted pre-format via the (msg, params) overloads;
    // appender/rewrite paths hand us a fully-built LogEvent. Cover both so suppression holds
    // wherever the filter ends up wired.

    @Override
    public Result filter(LogEvent event) {
        if (event == null || event.getMessage() == null) {
            return Result.NEUTRAL;
        }
        return decide(event.getMessage().getFormattedMessage());
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, Message msg, Throwable t) {
        return msg == null ? Result.NEUTRAL : decide(msg.getFormattedMessage());
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, Object msg, Throwable t) {
        return msg == null ? Result.NEUTRAL : decide(String.valueOf(msg));
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg, Object... params) {
        if (msg == null) {
            return Result.NEUTRAL;
        }
        // `msg` is the raw pattern ("{} issued server command: {}") with the command in `params`;
        // format it so the argument text is actually matched. Works for plain strings too
        // (empty params -> pattern returned unchanged).
        String formatted = (params == null || params.length == 0)
                ? msg
                : ParameterizedMessage.format(msg, params);
        return decide(formatted);
    }
}
