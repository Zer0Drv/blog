package com.zer0drv.blog.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Release policy regressions use the actual workflow graph and gate/deploy shell bodies. */
class ReleaseWorkflowTest {
    @TempDir
    Path temporary;

    @Test
    void publishingMustDependOnTheWholeReusableCi() throws IOException {
        Map<?, ?> ci = workflow("ci");
        // SnakeYAML follows YAML 1.1, where an unquoted 'on' key is a boolean.
        Map<?, ?> triggers = map(ci.containsKey("on") ? ci.get("on") : ci.get(true));
        assertTrue(triggers.containsKey("workflow_call"));
        Map<?, ?> jobs = map(workflow("cd").get("jobs"));
        assertEquals("./.github/workflows/ci.yml", map(jobs.get("ci")).get("uses"));
        assertNeeds(map(jobs.get("image")), "gate", "ci");
        assertNeeds(map(jobs.get("deploy")), "gate", "ci", "image");
        assertEquals("production", map(jobs.get("deploy")).get("environment"));
        assertTrue(map(jobs.get("image")).get("if").toString().contains("needs.ci.result == 'success'"));
        String deployCondition = map(jobs.get("deploy")).get("if").toString();
        assertTrue(deployCondition.contains("!cancelled()"));
        assertTrue(deployCondition.contains("needs.gate.result == 'success'"));
        assertTrue(deployCondition.contains("needs.ci.result == 'success' && needs.image.result == 'success'"));
        assertTrue(deployCondition.contains("needs.gate.outputs.rollback == 'true' && needs.ci.result == 'skipped' && needs.image.result == 'skipped'"));
    }

    @Test
    void allRequiredChecksMustRunAndUseTheSameImmutableCommit() throws IOException {
        Map<?, ?> jobs = map(workflow("ci").get("jobs"));
        assertTrue(jobs.keySet().containsAll(List.of("build", "flyway-migration", "real-dependencies", "browser-e2e")));
        for (Object value : jobs.values()) {
            Map<?, ?> job = map(value);
            assertFalse(job.containsKey("if"), "Required CI jobs must not be conditionally skipped");
            assertFalse(job.containsKey("continue-on-error"));
            assertPinnedCheckout(job);
            for (Object entry : (List<?>) job.get("steps")) {
                Map<?, ?> step = map(entry);
                assertFalse(step.containsKey("continue-on-error"));
                if (step.containsKey("if")) {
                    assertEquals("always()", step.get("if"), "Required steps must not be conditionally skipped");
                }
            }
        }
        assertPinnedCheckout(job("image"));
        Map<?, ?> ciTriggers = map(workflow("ci").get(true));
        assertFalse(map(ciTriggers.get("push")).containsKey("paths"), "A called release CI must remain complete");
    }

    @ParameterizedTest
    @CsvSource(value = {
            "refs/heads/main|push|ordinary commit||0|true|false",
            "refs/heads/main|push|chore: [SKIP CD]||0|false|false",
            "refs/heads/main|workflow_dispatch|[skip cd]||0|true|false",
            "refs/heads/main|workflow_dispatch||sha-1a2b3c4d|0|true|true",
            "refs/heads/feature|push|||1||",
            "refs/heads/feature|workflow_dispatch||sha-1a2b3c4d|1||",
            "refs/heads/main|workflow_dispatch||latest|1||",
            "refs/heads/main|workflow_dispatch||sha-DEADBEEF|1||",
            "refs/heads/main|workflow_dispatch||sha-1234567|1||",
            "refs/heads/main|workflow_dispatch||sha-12345678;echo bad|1||"
    }, delimiter = '|', nullValues = "")
    void gateRejectsUnsafeSourcesAndPreservesSkipAndRollback(String ref, String event, String message,
            String tag, int expectedExit, String deploy, String rollback) throws Exception {
        Path output = temporary.resolve("outputs");
        String script = map(((List<?>) job("gate").get("steps")).getFirst()).get("run").toString();
        int exit = run(script, Map.of("RELEASE_REF", ref, "EVENT_NAME", event,
                "MSG", orEmpty(message), "ROLLBACK_TAG", orEmpty(tag), "GITHUB_OUTPUT", shellPath(output)));
        assertEquals(expectedExit, exit, Files.readString(temporary.resolve("log")));
        if (expectedExit == 0) {
            String flags = Files.readString(output);
            assertTrue(flags.lines().anyMatch(line -> line.equals("deploy=" + deploy)));
            assertTrue(flags.lines().anyMatch(line -> line.equals("rollback=" + rollback)));
        } else {
            assertFalse(Files.exists(output), "A rejected gate must not expose deploy outputs");
        }
    }

    @ParameterizedTest
    @CsvSource(value = {
            "false|0123456789abcdef0123456789abcdef01234567|0|true",
            "false|ffffffffffffffffffffffffffffffffffffffff|1|false",
            "false||1|false",
            "true||0|true"
    }, delimiter = '|', nullValues = "")
    void wrongOrUnmarkedImagesCannotUpdateContainers(String rollback, String imageRevision,
            int expectedExit, boolean shouldUpdate) throws Exception {
        Path calls = temporary.resolve("compose-calls");
        Path summary = temporary.resolve("summary");
        String script = map(((List<?>) job("deploy").get("steps")).getFirst()).get("run").toString();
        // No Docker daemon or production service is used: only the real shell's ordering/guard is exercised.
        String stubs = """
                docker() {
                  if [[ "$*" == *'.Config.Labels'* ]]; then printf '%s\\n' "$FAKE_REVISION";
                  else printf '%s\\n' '["ghcr.io/zer0drv/blog-backend@sha256:fixture"]'; fi
                }
                docker-compose() { printf '%s\\n' "$*" >> "$COMPOSE_CALLS"; }
                """;
        int exit = run(stubs + script, Map.of("ROLLBACK", rollback,
                "BACKEND_TAG", "sha-01234567", "RELEASE_SHA", "0123456789abcdef0123456789abcdef01234567",
                "FAKE_REVISION", orEmpty(imageRevision), "COMPOSE_CALLS", shellPath(calls),
                "GITHUB_STEP_SUMMARY", shellPath(summary)));
        assertEquals(expectedExit, exit, Files.readString(temporary.resolve("log")));
        assertEquals(shouldUpdate, Files.readString(calls).contains("up -d backend"));
    }

    private int run(String script, Map<String, String> environment) throws Exception {
        String bash = System.getProperty("workflow.test.bash", "bash");
        if (System.getProperty("os.name").startsWith("Windows") && bash.equals("bash")) {
            String deploymentShell = map(map(job("deploy").get("defaults")).get("run")).get("shell").toString();
            bash = deploymentShell.substring(0, deploymentShell.indexOf(' '));
            assumeTrue(Files.isRegularFile(Path.of(bash)), "Set -Dworkflow.test.bash to a Git Bash executable");
        }
        Path scriptFile = temporary.resolve("fixture.sh");
        Files.writeString(scriptFile, script);
        ProcessBuilder builder = new ProcessBuilder(bash, "--noprofile", "--norc", "-eo", "pipefail", shellPath(scriptFile));
        builder.environment().putAll(environment);
        builder.redirectErrorStream(true).redirectOutput(temporary.resolve("log").toFile());
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS), "Shell fixture timed out");
            return process.exitValue();
        } finally {
            process.destroyForcibly();
        }
    }

    private static void assertNeeds(Map<?, ?> job, String... required) {
        List<?> dependencies = job.get("needs") instanceof List<?> list ? list : List.of(job.get("needs"));
        assertTrue(dependencies.containsAll(List.of(required)));
    }

    private static void assertPinnedCheckout(Map<?, ?> job) {
        List<?> checkouts = ((List<?>) job.get("steps")).stream().map(ReleaseWorkflowTest::map)
                .filter(step -> step.get("uses") != null && step.get("uses").toString().startsWith("actions/checkout@"))
                .toList();
        assertFalse(checkouts.isEmpty());
        boolean currentCommit = false;
        for (Object value : checkouts) {
            Map<?,?> settings = map(map(value).get("with"));
            if (settings.containsKey("repository")) {
                assertEquals("Zer0Drv/blog-ui", settings.get("repository"));
                assertTrue(settings.get("ref").toString().matches("[a-f0-9]{40}"), "External browser suite must use a full immutable SHA");
                assertEquals("browser-ui", settings.get("path"));
                assertEquals(false, settings.get("persist-credentials"));
            } else {
                assertEquals("${{ github.sha }}", settings.get("ref"));
                currentCommit = true;
            }
        }
        assertTrue(currentCommit, "Every build/test job must check out the current backend commit");
    }

    private static Map<?, ?> job(String name) throws IOException {
        return map(map(workflow("cd").get("jobs")).get(name));
    }

    private static Map<?, ?> workflow(String name) throws IOException {
        try (var reader = Files.newBufferedReader(Path.of(".github/workflows/" + name + ".yml"))) {
            return map(new Yaml(new SafeConstructor(new LoaderOptions())).load(reader));
        }
    }

    private static Map<?, ?> map(Object value) {
        return assertInstanceOf(Map.class, value);
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String shellPath(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }
}
