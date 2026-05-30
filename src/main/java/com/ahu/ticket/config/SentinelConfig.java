package com.ahu.ticket.config;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowItem;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRuleManager;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Sentinel 规则配置类
 * 亮点：即便是没有控制台，我们也可以通过代码硬编码规则，或者从 Nacos 动态加载。
 * 面试时可以作为“如果不看控制台，如何证明你用了 Sentinel”的方案。
 */
@Configuration
public class SentinelConfig {

    private final SentinelRuleProperties ruleProperties;

    public SentinelConfig(SentinelRuleProperties ruleProperties) {
        this.ruleProperties = ruleProperties;
    }

    @PostConstruct
    public void initFlowRules() {
        List<FlowRule> flowRules = new ArrayList<>();
        flowRules.add(buildInterfaceFlowRule("queryTrain", ruleProperties.getQueryTrain().getInterfaceQps()));
        flowRules.add(buildInterfaceFlowRule("bookTicket", ruleProperties.getBookTicket().getInterfaceQps()));
        FlowRuleManager.loadRules(flowRules);

        List<ParamFlowRule> paramRules = new ArrayList<>();
        paramRules.add(buildPerTrainParamFlowRule("queryTrain",
                ruleProperties.getQueryTrain().getDefaultPerTrainQps(),
                ruleProperties.getQueryTrain().getHotTrainQps()));
        paramRules.add(buildPerTrainParamFlowRule("bookTicket",
                ruleProperties.getBookTicket().getDefaultPerTrainQps(),
                ruleProperties.getBookTicket().getHotTrainQps()));
        ParamFlowRuleManager.loadRules(paramRules);
    }

    private FlowRule buildInterfaceFlowRule(String resource, double interfaceQps) {
        FlowRule interfaceRule = new FlowRule();
        interfaceRule.setResource(resource);
        interfaceRule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        interfaceRule.setCount(interfaceQps);
        return interfaceRule;
    }

    private ParamFlowRule buildPerTrainParamFlowRule(String resource,
                                                     double defaultPerTrainQps,
                                                     Map<String, Integer> hotTrainQps) {
        ParamFlowRule paramRule = new ParamFlowRule(resource);
        paramRule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        paramRule.setParamIdx(0);
        paramRule.setCount(defaultPerTrainQps);

        List<ParamFlowItem> hotItems = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : hotTrainQps.entrySet()) {
            ParamFlowItem item = new ParamFlowItem();
            item.setClassType(String.class.getName());
            item.setObject(entry.getKey());
            item.setCount(entry.getValue());
            hotItems.add(item);
        }
        paramRule.setParamFlowItemList(hotItems);
        return paramRule;
    }
}
