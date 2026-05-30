package com.ahu.ticket.agent.plan;

import java.util.stream.Collectors;

public record ActionExecutionResult(String finalAnswer, ActionPlan plan, boolean replanned, boolean fallback) {

    public String planSummary() {
        if (plan == null || plan.getSteps() == null || plan.getSteps().isEmpty()) {
            return "-";
        }
        return plan.getSteps().stream()
                .map(step -> {
                    String label = step.getType() == ActionStepType.TOOL
                            ? step.getTool()
                            : "RESPOND";
                    String status = step.getStatus() == null ? "PENDING" : step.getStatus().name();
                    return (step.getId() == null ? "step" : step.getId()) + ":" + label + "[" + status + "]";
                })
                .collect(Collectors.joining(" -> "));
    }
}
