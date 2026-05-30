package com.ahu.ticket.agent.plan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PolicyQuestionPlan(
        String goal,
        List<String> subQuestions,
        String synthesisInstruction
) {

    public PolicyQuestionPlan {
        subQuestions = subQuestions == null ? new ArrayList<>() : new ArrayList<>(subQuestions);
    }

    public static PolicyQuestionPlan fallback(String question) {
        return new PolicyQuestionPlan(
                "回答当前政策问题",
                question == null || question.isBlank() ? List.of() : List.of(question),
                "综合所有子问题的检索证据，直接回答用户原始问题。"
        );
    }
}
