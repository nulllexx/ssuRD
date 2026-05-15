package org.raindrippy.serversideutils;

import java.util.Set;
import java.util.logging.Filter;
import java.util.logging.LogRecord;

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
                    if (message.toLowerCase().contains("/" + hiddenCmd)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }
}
