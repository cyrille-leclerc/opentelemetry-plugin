/*
 * Copyright The Original Author or Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.jenkins.plugins.opentelemetry;

import com.github.rutledgepaulv.prune.Tree;
import com.google.common.base.Preconditions;
import hudson.ExtensionList;
import hudson.Functions;
import hudson.model.Result;
import io.jenkins.plugins.casc.misc.ConfiguredWithCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;
import io.jenkins.plugins.opentelemetry.semconv.JenkinsOtelSemanticAttributes;
import io.jenkins.plugins.opentelemetry.semconv.JenkinsSemanticMetrics;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.data.MetricDataType;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricExporter;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import jenkins.model.Jenkins;
import org.hamcrest.CoreMatchers;
import org.hamcrest.MatcherAssert;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.*;
import org.jvnet.hudson.test.BuildWatcher;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static com.google.common.base.Verify.verify;

/**
 * Note usage of `def xsh(cmd) {if (isUnix()) {sh cmd} else {bat cmd}}` is inspired by
 * https://github.com/jenkinsci/workflow-basic-steps-plugin/blob/474cea2a53753e1fb9b166fa1ca0f6184b5cee4a/src/test/java/org/jenkinsci/plugins/workflow/steps/IsUnixStepTest.java#L39
 */
public class JenkinsOtelPluginIntegrationTest {
    static {
        OpenTelemetrySdkProvider.TESTING_INMEMORY_MODE = true;
        OpenTelemetrySdkProvider.TESTING_SPAN_EXPORTER = InMemorySpanExporter.create();
        OpenTelemetrySdkProvider.TESTING_METRICS_EXPORTER = InMemoryMetricExporter.create();
    }

    private final static Logger LOGGER = Logger.getLogger(JenkinsOtelPluginIntegrationTest.class.getName());

    final static AtomicInteger jobNameSuffix = new AtomicInteger();

    @ClassRule
    public static BuildWatcher buildWatcher = new BuildWatcher();

    @ClassRule
    @ConfiguredWithCode("jcasc-elastic-backend.yml")
    public static JenkinsConfiguredWithCodeRule jenkinsRule = new JenkinsConfiguredWithCodeRule();

    static OpenTelemetrySdkProvider openTelemetrySdkProvider;


    @BeforeClass
    public static void beforeClass() throws Exception {
        System.out.println("beforeClass()");
        System.out.println("Wait for jenkins to start...");
        jenkinsRule.waitUntilNoActivity();
        System.out.println("Jenkins started");

        ExtensionList<OpenTelemetrySdkProvider> openTelemetrySdkProviders = jenkinsRule.getInstance().getExtensionList(OpenTelemetrySdkProvider.class);
        verify(openTelemetrySdkProviders.size() == 1, "Number of openTelemetrySdkProviders: %s", openTelemetrySdkProviders.size());
        openTelemetrySdkProvider = openTelemetrySdkProviders.get(0);

        // verify(openTelemetrySdkProvider.openTelemetry == null, "OpenTelemetrySdkProvider has already been configured");
        openTelemetrySdkProvider.initializeForTesting();

        // openTelemetrySdkProvider.tracer.setDelegate(openTelemetrySdkProvider.openTelemetry.getTracer("jenkins"));
    }

    @Before
    public void before() throws Exception {
    }

    @After
    public void after() throws Exception {
        jenkinsRule.waitUntilNoActivity();
        ((InMemorySpanExporter) OpenTelemetrySdkProvider.TESTING_SPAN_EXPORTER).reset();
        ((InMemoryMetricExporter) OpenTelemetrySdkProvider.TESTING_METRICS_EXPORTER).reset();
    }

    @Test
    public void testSimplePipeline() throws Exception {
        // BEFORE


        String pipelineScript = "def xsh(cmd) {if (isUnix()) {sh cmd} else {bat cmd}};\n" +
                "node() {\n" +
                "    stage('ze-stage1') {\n" +
                "       xsh (label: 'shell-1', script: 'echo ze-echo') \n" +
                "    }\n" +
                "    stage('ze-stage2') {\n" +
                "       xsh 'echo ze-echo-2' \n" +
                "    }\n" +
                "}";
        WorkflowJob pipeline = jenkinsRule.createProject(WorkflowJob.class, "test-simple-pipeline-" + jobNameSuffix.incrementAndGet());
        pipeline.setDefinition(new CpsFlowDefinition(pipelineScript, true));
        WorkflowRun build = jenkinsRule.assertBuildStatus(Result.SUCCESS, pipeline.scheduleBuild2(0));

        List<SpanData> finishedSpanItems = flush();
        dumpSpans(finishedSpanItems);
        MatcherAssert.assertThat(finishedSpanItems.size(), CoreMatchers.is(8));

        // WORKAROUND because we don't know how to force the IntervalMetricReader to collect metrics
        Thread.sleep(600);
        Map<String, MetricData> exportedMetrics = ((InMemoryMetricExporter) OpenTelemetrySdkProvider.TESTING_METRICS_EXPORTER).getLastExportedMetricByMetricName();
        dumpMetrics(exportedMetrics);
        MetricData runCompletedCounterData = exportedMetrics.get(JenkinsSemanticMetrics.CI_PIPELINE_RUN_COMPLETED);
        MatcherAssert.assertThat(runCompletedCounterData, CoreMatchers.notNullValue());
        // TODO TEST METRICS WITH PROPER RESET BETWEEN TESTS
        MatcherAssert.assertThat(runCompletedCounterData.getType(), CoreMatchers.is(MetricDataType.LONG_SUM));
        Collection<LongPointData> metricPoints = runCompletedCounterData.getLongSumData().getPoints();
        //MatcherAssert.assertThat(Iterables.getLast(metricPoints).getValue(), CoreMatchers.is(1L));
    }

    @Test
    public void testTraceEnvironmentVariablesInjectedInShellSteps() throws Exception {
        if (Functions.isWindows()) {
            // TODO test on windows
        } else {
            String pipelineScript = "node() {\n" +
                    "    stage('ze-stage1') {\n" +
                    "       sh '''\n" +
                    "if [ -z $TRACEPARENT ]\n" +
                    "then\n" +
                    "   echo TRACEPARENT NOT FOUND\n" +
                    "   exit 1\n" +
                    "fi\n" +
                    "'''\n" +
                    "    }\n" +
                    "}";
            WorkflowJob pipeline = jenkinsRule.createProject(WorkflowJob.class, "test-trace-environment-variables-injected-in-shell-steps-" + jobNameSuffix.incrementAndGet());
            pipeline.setDefinition(new CpsFlowDefinition(pipelineScript, true));
            WorkflowRun build = jenkinsRule.assertBuildStatus(Result.SUCCESS, pipeline.scheduleBuild2(0));

            List<SpanData> finishedSpanItems = flush();
            dumpSpans(finishedSpanItems);
            MatcherAssert.assertThat(finishedSpanItems.size(), CoreMatchers.is(6));
        }
    }


    @Test
    public void testPipelineWithSkippedSteps() throws Exception {
        String pipelineScript = "def xsh(cmd) {if (isUnix()) {sh cmd} else {bat cmd}};\n" +
                "node() {\n" +
                "    stage('ze-stage1') {\n" +
                "       xsh 'echo ze-echo' \n" +
                "       echo 'ze-echo-step' \n" +
                "    }\n" +
                "    stage('ze-stage2') {\n" +
                "       xsh 'echo ze-echo-2' \n" +
                "    }\n" +
                "}";
        WorkflowJob pipeline = jenkinsRule.createProject(WorkflowJob.class, "test-simple-pipeline-" + jobNameSuffix.incrementAndGet());
        pipeline.setDefinition(new CpsFlowDefinition(pipelineScript, true));
        WorkflowRun build = jenkinsRule.assertBuildStatus(Result.SUCCESS, pipeline.scheduleBuild2(0));

        List<SpanData> finishedSpanItems = flush();
        dumpSpans(finishedSpanItems);
        MatcherAssert.assertThat(finishedSpanItems.size(), CoreMatchers.is(8));
    }

    private void dumpMetrics(Map<String, MetricData> exportedMetrics) {
        System.out.println("Metrics: " + exportedMetrics.size());
        System.out.println(exportedMetrics.values().stream().sorted(Comparator.comparing(MetricData::getName)).map(metric -> {
            MetricDataType metricType = metric.getType();
            String s = metric.getName() + "   " + metricType + " ";
            switch (metricType) {
                case LONG_SUM:
                    s += metric.getLongSumData().getPoints().stream().map(point -> String.valueOf(point.getValue())).collect(Collectors.joining(", ")) + "";
                    break;
                case DOUBLE_SUM:
                    s += metric.getDoubleSumData().getPoints().stream().map(point -> String.valueOf(point.getValue())).collect(Collectors.joining(", ")) + "";
                    break;
                case DOUBLE_GAUGE:
                    s += metric.getDoubleGaugeData().getPoints().stream().map(point -> String.valueOf(point.getValue())).collect(Collectors.joining(", ")) + "";
                    break;
                case LONG_GAUGE:
                    s += metric.getLongGaugeData().getPoints().stream().map(point -> String.valueOf(point.getValue())).collect(Collectors.joining(", ")) + "";
                    break;
                case SUMMARY:
                    break;
                default:

            }
            return s;
        }).collect(Collectors.joining(" \n")));
    }

    protected List<SpanData> flush() {
        CompletableResultCode completableResultCode = this.openTelemetrySdkProvider.getOpenTelemetrySdk().getSdkTracerProvider().forceFlush();
        completableResultCode.join(1, TimeUnit.SECONDS);
        return ((InMemorySpanExporter) OpenTelemetrySdkProvider.TESTING_SPAN_EXPORTER).getFinishedSpanItems();
    }

    @Test
    public void testPipelineWithWrappingStep() throws Exception {
        String pipelineScript = "def xsh(cmd) {if (isUnix()) {sh cmd} else {bat cmd}};\n" +
                "node() {\n" +
                "    stage('ze-stage1') {\n" +
                "       withEnv(['MY_VARIABLE=MY_VALUE']) {\n" +
                "          xsh 'echo ze-echo' \n" +
                "       }\n" +
                "       xsh 'echo ze-echo' \n" +
                "    }\n" +
                "    stage('ze-stage2') {\n" +
                "       xsh 'echo ze-echo2' \n" +
                "    }\n" +
                "}";
        WorkflowJob pipeline = jenkinsRule.createProject(WorkflowJob.class, "test-simple-pipeline-" + jobNameSuffix.incrementAndGet());
        pipeline.setDefinition(new CpsFlowDefinition(pipelineScript, true));
        WorkflowRun build = jenkinsRule.assertBuildStatus(Result.SUCCESS, pipeline.scheduleBuild2(0));

        List<SpanData> finishedSpanItems = flush();
        dumpSpans(finishedSpanItems);
        MatcherAssert.assertThat(finishedSpanItems.size(), CoreMatchers.is(9));
    }

    @Test
    public void testPipelineWithError() throws Exception {
        String pipelineScript = "def xsh(cmd) {if (isUnix()) {sh cmd} else {bat cmd}};\n" +
                "node() {\n" +
                "    stage('ze-stage1') {\n" +
                "       xsh 'echo ze-echo' \n" +
                "    }\n" +
                "    stage('ze-stage2') {\n" +
                "       xsh 'echo ze-echo2' \n" +
                "       error 'ze-pipeline-error' \n" +
                "    }\n" +
                "}";
        WorkflowJob pipeline = jenkinsRule.createProject(WorkflowJob.class, "test-pipeline-with-failure" + jobNameSuffix.incrementAndGet());
        pipeline.setDefinition(new CpsFlowDefinition(pipelineScript, true));
        WorkflowRun build = jenkinsRule.assertBuildStatus(Result.FAILURE, pipeline.scheduleBuild2(0));

        List<SpanData> finishedSpanItems = flush();
        dumpSpans(finishedSpanItems);
        MatcherAssert.assertThat(finishedSpanItems.size(), CoreMatchers.is(9));
    }

    @Test
    public void testPipelineWithParallelStep() throws Exception {
        String pipelineScript = "def xsh(cmd) {if (isUnix()) {sh cmd} else {bat cmd}};\n" +
                "node() {\n" +
                "    stage('ze-parallel-stage') {\n" +
                "        parallel parallelBranch1: {\n" +
                "            stage('ze-parallel-stage-1') {\n" +
                "                xsh 'echo this-is-the-parallel-branch-1'\n" +
                "            }\n" +
                "        } ,parallelBranch2: {\n" +
                "            stage('ze-parallel-stage-2') {\n" +
                "                xsh 'echo this-is-the-parallel-branch-2'\n" +
                "            }\n" +
                "        } ,parallelBranch3: {\n" +
                "            stage('ze-parallel-stage-3') {\n" +
                "                xsh 'echo this-is-the-parallel-branch-3'\n" +
                "            }\n" +
                "        }\n" +
                "    }\n" +
                "}";
        System.out.println(pipelineScript);
        WorkflowJob pipeline = jenkinsRule.createProject(WorkflowJob.class, "test-pipeline-with-parallel-step" + jobNameSuffix.incrementAndGet());
        pipeline.setDefinition(new CpsFlowDefinition(pipelineScript, true));
        WorkflowRun build = jenkinsRule.assertBuildStatus(Result.SUCCESS, pipeline.scheduleBuild2(0));

        List<SpanData> finishedSpanItems = flush();
        dumpSpans(finishedSpanItems);
        MatcherAssert.assertThat(finishedSpanItems.size(), CoreMatchers.is(14));

        SpanData spanZeParallelStage = getSpanBySpanName("Stage: ze-parallel-stage", finishedSpanItems);
        SpanData spanZeParallelBranch1 = getSpanBySpanName("Parallel branch: parallelBranch1", finishedSpanItems);
        SpanData spanZeParallelStage1 = getSpanBySpanName("Stage: ze-parallel-stage-1", finishedSpanItems);

        SpanData actualParentOfSpanZeParallelBranch1 = getSpanBySpanId(spanZeParallelBranch1.getParentSpanId(), finishedSpanItems);
        SpanData expectedParentOfSpanZeParallelBranch = spanZeParallelStage;
        SpanData actualParentOfSpanZeParallelStage1 = getSpanBySpanId(spanZeParallelStage1.getParentSpanId(), finishedSpanItems);
        SpanData expectedParentOfSpanZeParallelStage1 = spanZeParallelBranch1;

        System.out.println("####");
        System.out.println("Parent of span ZeParallelBranch1:");
        System.out.println("\tactual:\t\t" + actualParentOfSpanZeParallelBranch1.getName() + "\t\t\t\t\t\t" + actualParentOfSpanZeParallelBranch1);
        System.out.println("\texpected:\t" + expectedParentOfSpanZeParallelBranch.getName() + "\t\t\t\t\t\t" + expectedParentOfSpanZeParallelBranch);

        System.out.println("####");
        System.out.println("Parent of span ZeParallelStage1:");
        System.out.println("\tactual:\t\t" + actualParentOfSpanZeParallelStage1.getName() + "\t\t\t\t\t\t" + actualParentOfSpanZeParallelStage1);
        System.out.println("\texpected:\t" + expectedParentOfSpanZeParallelStage1.getName() + "\t\t\t\t\t\t" + expectedParentOfSpanZeParallelStage1);

        System.out.println(jenkinsRule.jenkins.getRootUrl());
        //Thread.sleep(TimeUnit.MILLISECONDS.convert(10, TimeUnit.MINUTES));
        Assert.assertEquals(spanZeParallelStage1.getParentSpanId(), spanZeParallelBranch1.getSpanId());
        Assert.assertEquals(spanZeParallelBranch1.getParentSpanId(), spanZeParallelStage.getSpanId());
    }


    public void verifyChainOfSpansFromAncestorToDescendants(List<String> expectedChainOfSpanNameFromAncestorToDescendant, List<SpanData> allSpans) {

    }

    @Nonnull
    public SpanData getSpanBySpanName(String spanName, List<SpanData> spans) throws Exception {
        for (SpanData span : spans) {
            if (Objects.equals(span.getName(), spanName)) {
                return span;
            }
        }
        throw new Exception("Span '" + spanName + "' not found");
    }

    @Nonnull
    public SpanData getSpanBySpanId(String spanId, List<SpanData> spans) throws Exception {
        for (SpanData span : spans) {
            if (Objects.equals(span.getSpanId(), spanId)) {
                return span;
            }
        }
        throw new Exception("Span with id '" + spanId + "' not found");
    }

    protected void dumpSpans(List<SpanData> spans) {
        // System.out.println(spans.size());
        // List<String> spansAsString = spans.stream().map(spanData ->
        //         "   " + spanData.getStartEpochNanos() + " - " + spanData.getName() + ", id: " + spanData.getSpanId() + ", parentId: " + spanData.getParentSpanId() + ", attributes: " + spanData.getAttributes().asMap()
        // ).collect(Collectors.toList());
        // Collections.sort(spansAsString);
        //
        // System.out.println(spansAsString.stream().collect(Collectors.joining(", \n")));


        final BiPredicate<Tree.Node<SpanDataWrapper>, Tree.Node<SpanDataWrapper>> parentChildMatcher = (spanDataNode1, spanDataNode2) -> {
            final SpanData spanData1 = spanDataNode1.getData().spanData;
            final SpanData spanData2 = spanDataNode2.getData().spanData;
            return Objects.equals(spanData1.getSpanId(), spanData2.getParentSpanId());
        };
        final List<Tree<SpanDataWrapper>> trees = Tree.of(spans.stream().map(span -> new SpanDataWrapper(span)).collect(Collectors.toList()), parentChildMatcher);
        System.out.println("## TREE VIEW OF SPANS ## ");
        for(Tree<SpanDataWrapper> tree: trees) {
            System.out.println(tree);
        }

    }

    @AfterClass
    public static void afterClass() {
        GlobalOpenTelemetry.resetForTest();
    }

    static class SpanDataWrapper {
        final SpanData spanData;

        public SpanDataWrapper(SpanData spanData) {
            this.spanData = spanData;
        }

        @Override
        public String toString() {
             String result = spanData.getName();

            final Attributes attributes = spanData.getAttributes();
            if (attributes.get(JenkinsOtelSemanticAttributes.JENKINS_STEP_TYPE) != null) {
                result += ", function: " +  attributes.get(JenkinsOtelSemanticAttributes.JENKINS_STEP_TYPE);
            }
            if (attributes.get(JenkinsOtelSemanticAttributes.JENKINS_STEP_ID) != null) {
                result += ", id: " +  attributes.get(JenkinsOtelSemanticAttributes.JENKINS_STEP_ID);
            }
            return result;
        }
    }

}
