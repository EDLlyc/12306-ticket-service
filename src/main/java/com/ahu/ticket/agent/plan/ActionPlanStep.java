package com.ahu.ticket.agent.plan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ActionPlanStep {
    private String id;
    private ActionStepType type;
    private String tool;
    private Map<String, Object> args = new LinkedHashMap<>();
    private String instruction;
    private ActionStepStatus status = ActionStepStatus.PENDING;
    private String observation;
    private ActionStepObservation observationDetail;
}
