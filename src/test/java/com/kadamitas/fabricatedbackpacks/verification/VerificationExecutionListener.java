package com.kadamitas.fabricatedbackpacks.verification;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Test-only execution identities complement Gradle XML, whose parameterized names omit their methods. */
public final class VerificationExecutionListener implements TestExecutionListener {
    private final Map<String, TestIdentifier> nodes = new LinkedHashMap<>();
    private final Map<String, String> outcomes = new LinkedHashMap<>();
    private Path output;
    private long started;

    @Override public synchronized void testPlanExecutionStarted(TestPlan plan) {
        String configured = System.getProperty("fabricated.backpacks.unitEvidence");
        if (configured == null || configured.isBlank()) return;
        output = Path.of(configured).toAbsolutePath().normalize();
        started = System.currentTimeMillis();
        nodes.clear();
        outcomes.clear();
        for (TestIdentifier root : plan.getRoots()) {
            nodes.put(root.getUniqueId(), root);
            for (TestIdentifier node : plan.getDescendants(root)) nodes.put(node.getUniqueId(), node);
        }
        try {
            Files.createDirectories(output.getParent());
            Files.deleteIfExists(output);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot invalidate prior unit execution evidence", failure);
        }
    }

    @Override public synchronized void dynamicTestRegistered(TestIdentifier node) {
        if (output != null) nodes.put(node.getUniqueId(), node);
    }

    @Override public synchronized void executionSkipped(TestIdentifier node, String reason) {
        if (output != null) {
            nodes.put(node.getUniqueId(), node);
            outcomes.put(node.getUniqueId(), "SKIPPED");
        }
    }

    @Override public synchronized void executionFinished(TestIdentifier node, TestExecutionResult result) {
        if (output != null) {
            nodes.put(node.getUniqueId(), node);
            outcomes.put(node.getUniqueId(), result.getStatus().name());
        }
    }

    @Override public synchronized void testPlanExecutionFinished(TestPlan plan) {
        if (output == null) return;
        Map<MethodIdentity, JsonObject> methods = new LinkedHashMap<>();
        JsonArray tests = new JsonArray();
        JsonArray problems = new JsonArray();
        for (TestIdentifier node : nodes.values()) {
            Optional<MethodSource> source = source(node);
            source.ifPresent(method -> methods.putIfAbsent(MethodIdentity.of(method), methodJson(method)));
            String status = outcomes.getOrDefault(node.getUniqueId(), "MISSING");
            if (node.isTest()) {
                JsonObject test = source.map(VerificationExecutionListener::methodJson).orElseGet(JsonObject::new);
                test.addProperty("id", node.getUniqueId());
                test.addProperty("display_name", node.getDisplayName());
                test.addProperty("legacy_name", node.getLegacyReportingName());
                test.addProperty("status", status);
                tests.add(test);
            }
            if (!status.equals("SUCCESSFUL")) {
                JsonObject problem = new JsonObject();
                problem.addProperty("id", node.getUniqueId());
                problem.addProperty("status", status);
                problems.add(problem);
            }
        }
        JsonObject record = new JsonObject();
        record.addProperty("schema", 1);
        record.addProperty("complete", true);
        record.addProperty("pid", ProcessHandle.current().pid());
        record.addProperty("started_at", started);
        record.addProperty("finished_at", System.currentTimeMillis());
        JsonArray declared = new JsonArray();
        methods.values().forEach(declared::add);
        record.add("methods", declared);
        record.add("tests", tests);
        record.add("problems", problems);
        Path temporary = output.resolveSibling("." + output.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(record) + "\n",
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot write complete unit execution evidence", failure);
        }
    }

    private Optional<MethodSource> source(TestIdentifier start) {
        TestIdentifier node = start;
        while (node != null) {
            if (node.getSource().orElse(null) instanceof MethodSource method) return Optional.of(method);
            node = node.getParentId().map(nodes::get).orElse(null);
        }
        return Optional.empty();
    }

    private static JsonObject methodJson(MethodSource source) {
        JsonObject result = new JsonObject();
        result.addProperty("class_name", source.getClassName());
        result.addProperty("method_name", source.getMethodName());
        result.addProperty("parameter_types", source.getMethodParameterTypes());
        return result;
    }

    private record MethodIdentity(String className, String methodName, String parameterTypes) {
        static MethodIdentity of(MethodSource source) {
            return new MethodIdentity(source.getClassName(), source.getMethodName(), source.getMethodParameterTypes());
        }
    }
}
