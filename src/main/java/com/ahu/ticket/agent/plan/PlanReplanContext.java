package com.ahu.ticket.agent.plan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PlanReplanContext {
    private String stepId;
    private ActionStepType stepType;
    private String tool;
    private ActionStepStatus status;
    private ActionStepObservation observationDetail;
}
