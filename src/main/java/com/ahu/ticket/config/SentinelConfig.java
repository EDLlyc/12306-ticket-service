package com.ahu.ticket.config;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Sentinel 规则配置类
 * 亮点：即便是没有控制台，我们也可以通过代码硬编码规则，或者从 Nacos 动态加载。
 * 面试时可以作为“如果不看控制台，如何证明你用了 Sentinel”的方案。
 */
@Configuration
public class SentinelConfig {

    @PostConstruct
    public void initFlowRules() {
        List<FlowRule> rules = new ArrayList<>();

        // 为 queryTrain 接口定义一个硬编码的限流规则
        FlowRule rule = new FlowRule();
        rule.setResource("queryTrain");
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        // 为了演示，我们将 QPS 限制为 100000（一秒只能查一次）
        rule.setCount(100000);

        rules.add(rule);
        FlowRuleManager.loadRules(rules);
    }
}
