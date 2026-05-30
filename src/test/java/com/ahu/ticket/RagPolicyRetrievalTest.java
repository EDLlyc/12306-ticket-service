package com.ahu.ticket;

import com.ahu.ticket.service.impl.RagServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RagPolicyRetrievalTest {

    private RagServiceImpl ragService;
    private Method scorePolicyContextMethod;
    private Method resolveBootstrapRulesPathMethod;
    private Method splitByNumberedSummariesMethod;
    private Method shouldUsePolicyPlanMethod;

    @BeforeEach
    public void setup() throws Exception {
        ragService = new RagServiceImpl();
        scorePolicyContextMethod = RagServiceImpl.class.getDeclaredMethod("scorePolicyContext", String.class, String.class);
        scorePolicyContextMethod.setAccessible(true);
        resolveBootstrapRulesPathMethod = RagServiceImpl.class.getDeclaredMethod("resolveBootstrapRulesPath");
        resolveBootstrapRulesPathMethod.setAccessible(true);
        splitByNumberedSummariesMethod = RagServiceImpl.class.getDeclaredMethod("splitByNumberedSummaries", String.class);
        splitByNumberedSummariesMethod.setAccessible(true);
        shouldUsePolicyPlanMethod = RagServiceImpl.class.getDeclaredMethod("shouldUsePolicyPlan", String.class);
        shouldUsePolicyPlanMethod.setAccessible(true);
    }

    @Test
    public void testScorePolicyContext_prefersPetRulesOverBaggageRules() throws Exception {
        String question = "高铁上可以带宠物吗？";
        String petContext = "第二十七条 视力残疾旅客可携带持证导盲犬乘车。第五十六条 禁带活动物，导盲犬除外。";
        String baggageContext = "第五十五条 携带品自行看管，儿童10kg，其他旅客20kg，每件长宽高之和不超过160cm。";

        int petScore = (int) scorePolicyContextMethod.invoke(ragService, question, petContext);
        int baggageScore = (int) scorePolicyContextMethod.invoke(ragService, question, baggageContext);

        assertTrue(petScore > baggageScore, "宠物问题应优先命中动物/导盲犬条款");
    }

    @Test
    public void testScorePolicyContext_prefersCarryOnRulesOverCheckedBaggageRules() throws Exception {
        String question = "可以带多少行李上高铁？";
        String carryOnContext = "第五十五条 携带品自行看管，儿童10kg，外交人员35kg，其他旅客20kg。每件长宽高之和不超过160cm，动车组不超过130cm。";
        String checkedContext = "第六十五条 行李每件最大50kg，长宽高之和60-200cm，一般随旅客列车或提前运送。";

        int carryOnScore = (int) scorePolicyContextMethod.invoke(ragService, question, carryOnContext);
        int checkedScore = (int) scorePolicyContextMethod.invoke(ragService, question, checkedContext);

        assertTrue(carryOnScore > checkedScore, "随身携带行李问题不应优先命中托运行李条款");
    }

    @Test
    public void testResolveBootstrapRulesPath_prefersWorkspaceRulesFile() throws Exception {
        Path workspaceRules = Files.createTempFile("workspace-rules", ".txt");
        Path legacyRules = Files.createTempFile("legacy-rules", ".txt");
        setField("devBootstrapFile", workspaceRules.toString());
        setField("devBootstrapLegacyFile", legacyRules.toString());

        Path resolved = (Path) resolveBootstrapRulesPathMethod.invoke(ragService);

        assertEquals(workspaceRules, resolved);
    }

    @Test
    public void testResolveBootstrapRulesPath_fallsBackToLegacyRulesFile() throws Exception {
        Path missingWorkspaceRules = Path.of("target", "missing-workspace-rules.txt");
        Path legacyRules = Files.createTempFile("legacy-rules", ".txt");
        setField("devBootstrapFile", missingWorkspaceRules.toString());
        setField("devBootstrapLegacyFile", legacyRules.toString());

        Path resolved = (Path) resolveBootstrapRulesPathMethod.invoke(ragService);

        assertEquals(legacyRules, resolved);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testSplitByNumberedSummaries_splitsGoldenRulesIntoIndependentSegments() throws Exception {
        String text = """
                1. 儿童票购买规定：
                儿童票可凭有效身份证件购买。

                2. 宠物携带规定：
                旅客不得携带任何动物乘车。

                3. 退票费计算规则：
                开车前15天以上退票不收退票费。
                """;

        java.util.List<Object> segments = (java.util.List<Object>) splitByNumberedSummariesMethod.invoke(ragService, text);

        assertEquals(3, segments.size());
    }

    @Test
    public void testShouldUsePolicyPlan_fastFalseForSimplePolicyQuestion() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        setPolicyComplexityClassifier((question) -> {
            calls.incrementAndGet();
            return "PLANNED_RAG";
        });

        boolean result = (boolean) shouldUsePolicyPlanMethod.invoke(ragService, "学生票是什么");

        assertTrue(!result);
        assertEquals(0, calls.get());
    }

    @Test
    public void testShouldUsePolicyPlan_fastTrueForClearlyComplexPolicyQuestion() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        setPolicyComplexityClassifier((question) -> {
            calls.incrementAndGet();
            return "SIMPLE_RAG";
        });

        boolean result = (boolean) shouldUsePolicyPlanMethod.invoke(ragService, "学生票和儿童票的区别，以及各自的适用条件是什么");

        assertTrue(result);
        assertEquals(0, calls.get());
    }

    @Test
    public void testShouldUsePolicyPlan_usesClassifierForAmbiguousQuestion() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        setPolicyComplexityClassifier((question) -> {
            calls.incrementAndGet();
            return "PLANNED_RAG";
        });

        boolean result = (boolean) shouldUsePolicyPlanMethod.invoke(ragService, "学生票和资质核验有关吗");

        assertTrue(result);
        assertEquals(1, calls.get());
    }

    @Test
    public void testShouldUsePolicyPlan_fallsBackToRulesWhenClassifierFails() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        setPolicyComplexityClassifier((question) -> {
            calls.incrementAndGet();
            throw new IllegalStateException("classifier unavailable");
        });

        boolean result = (boolean) shouldUsePolicyPlanMethod.invoke(ragService, "学生票和资质核验有关吗");

        assertTrue(result);
        assertEquals(1, calls.get());
    }

    private void setField(String fieldName, String value) throws Exception {
        Field field = RagServiceImpl.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(ragService, value);
    }

    private void setPolicyComplexityClassifier(PolicyClassifierBehavior behavior) throws Exception {
        Field field = RagServiceImpl.class.getDeclaredField("policyComplexityClassifier");
        field.setAccessible(true);
        Class<?> classifierType = field.getType();
        Object proxy = Proxy.newProxyInstance(
                classifierType.getClassLoader(),
                new Class<?>[] { classifierType },
                (obj, method, args) -> {
                    if ("classify".equals(method.getName())) {
                        return behavior.classify((String) args[0]);
                    }
                    if ("toString".equals(method.getName())) {
                        return "PolicyComplexityClassifierProxy";
                    }
                    return null;
                });
        field.set(ragService, proxy);
    }

    @FunctionalInterface
    private interface PolicyClassifierBehavior {
        String classify(String question) throws Exception;
    }
}
