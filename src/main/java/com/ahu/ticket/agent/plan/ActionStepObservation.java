package com.ahu.ticket.agent.plan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ActionStepObservation {

    private String source;
    private String toolName;
    private Boolean success;
    private String code;
    private String message;
    private Map<String, Object> data = new LinkedHashMap<>();
    private Boolean terminal;
    private Boolean retryable;

    public static ActionStepObservation fromToolObservation(ToolObservation observation) {
        ActionStepObservation detail = new ActionStepObservation();
        if (observation == null) {
            detail.setSource("TOOL");
            detail.setSuccess(false);
            detail.setCode("TOOL_EXECUTION_EMPTY");
            detail.setMessage("tool_execution_returned_null");
            detail.setTerminal(true);
            detail.setRetryable(false);
            return detail;
        }
        detail.setSource("TOOL");
        detail.setToolName(observation.toolName());
        detail.setSuccess(observation.success());
        detail.setCode(observation.code());
        detail.setMessage(observation.userMessage());
        detail.setData(new LinkedHashMap<>(observation.data()));
        detail.setTerminal(observation.terminal());
        detail.setRetryable(observation.retryable());
        return detail;
    }

    public static ActionStepObservation system(String code, String message, boolean success,
                                               Map<String, Object> data, boolean terminal, boolean retryable) {
        ActionStepObservation detail = new ActionStepObservation();
        detail.setSource("SYSTEM");
        detail.setSuccess(success);
        detail.setCode(code);
        detail.setMessage(message);
        detail.setData(data == null ? new LinkedHashMap<>() : new LinkedHashMap<>(data));
        detail.setTerminal(terminal);
        detail.setRetryable(retryable);
        return detail;
    }

    public static ActionStepObservation respond(String code, String message, Map<String, Object> data) {
        ActionStepObservation detail = new ActionStepObservation();
        detail.setSource("RESPOND");
        detail.setSuccess(true);
        detail.setCode(code);
        detail.setMessage(message);
        detail.setData(data == null ? new LinkedHashMap<>() : new LinkedHashMap<>(data));
        detail.setTerminal(true);
        detail.setRetryable(false);
        return detail;
    }
}
