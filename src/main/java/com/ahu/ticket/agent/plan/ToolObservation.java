package com.ahu.ticket.agent.plan;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ToolObservation(
        String toolName,
        boolean success,
        String code,
        String userMessage,
        Map<String, Object> data,
        boolean terminal,
        boolean retryable
) {

    public ToolObservation {
        data = data == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }

    public static ToolObservation of(String toolName, boolean success, String code, String userMessage,
                                     Map<String, Object> data, boolean terminal, boolean retryable) {
        return new ToolObservation(toolName, success, code, userMessage, data, terminal, retryable);
    }
}
