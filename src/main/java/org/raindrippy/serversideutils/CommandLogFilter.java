package org.raindrippy.serversideutils;

import java.util.Set;
import java.util.logging.Filter;
import java.util.logging.LogRecord;
import java.util.regex.Pattern;

public class CommandLogFilter implements Filter {
    private final Set<String> hiddenCommands;

    public CommandLogFilter(Set<String> hiddenCommands) {
        this.hiddenCommands = hiddenCommands;
    }

    @Override
    public boolean isLoggable(LogRecord record) {
        String message = record.getMessage();
        if (message != null) {
            if (message.contains("issued server command:") ||
                    message.contains("executed command:")) {
                for (String hiddenCmd : hiddenCommands) {
                    // Whole-command match: "/sync" as its own token, not a prefix of "/syncfoo".
                    Pattern p = Pattern.compile("/" + Pattern.quote(hiddenCmd) + "($|\\s)",
                            Pattern.CASE_INSENSITIVE);
                    if (p.matcher(message).find()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }
}
