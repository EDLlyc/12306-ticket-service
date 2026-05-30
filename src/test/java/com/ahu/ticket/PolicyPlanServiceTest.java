package com.ahu.ticket;

import com.ahu.ticket.agent.ZhipuOfficialAgent;
import com.ahu.ticket.agent.plan.PolicyPlanService;
import com.ahu.ticket.agent.plan.PolicyQuestionPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class PolicyPlanServiceTest {

    @Mock
    private ZhipuOfficialAgent zhipuOfficialAgent;

    @InjectMocks
    private PolicyPlanService policyPlanService;

    @Test
    public void testPlan_parseJsonPlan() {
        when(zhipuOfficialAgent.generatePolicyText(anyString(), anyString(), eq(false)))
                .thenReturn("""
                        {
                          "goal":"对比学生票与儿童票规则",
                          "subQuestions":[
                            "学生票的适用条件是什么",
                            "儿童票的适用条件是什么",
                            "学生票和儿童票的核心区别是什么"
                          ],
                          "synthesisInstruction":"按适用对象、条件和差异总结。"
                        }
                        """);

        PolicyQuestionPlan plan = policyPlanService.plan("学生票和儿童票有什么区别");

        assertEquals(3, plan.subQuestions().size());
        assertEquals("学生票的适用条件是什么", plan.subQuestions().get(0));
        assertTrue(plan.synthesisInstruction().contains("差异"));
    }

    @Test
    public void testPlan_fallbackToSingleQuestionWhenOutputInvalid() {
        when(zhipuOfficialAgent.generatePolicyText(anyString(), anyString(), eq(false)))
                .thenReturn("这不是 json");

        PolicyQuestionPlan plan = policyPlanService.plan("报销凭证和电子票报销有什么区别");

        assertEquals(1, plan.subQuestions().size());
        assertEquals("报销凭证和电子票报销有什么区别", plan.subQuestions().get(0));
    }
}
