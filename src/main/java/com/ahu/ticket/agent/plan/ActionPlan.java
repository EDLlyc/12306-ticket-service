package com.ahu.ticket.agent.plan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ActionPlan {
    private String goal;
    private List<ActionPlanStep> steps = new ArrayList<>();
}
