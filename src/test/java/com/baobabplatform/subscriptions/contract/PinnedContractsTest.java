package com.baobabplatform.subscriptions.contract;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The vendored contracts are byte-for-byte the Shared commit contracts.lock.yaml
 * pins. SHARED_CONTRACTS_DIR names a baobab-platform/shared checkout at that
 * commit (CI checks it out). Bundle/inventory/golden checks always run;
 * only the independent owner read requires a checkout.
 */
class PinnedContractsTest {
    @Test
    void vendoredContractsMatchThePinnedSharedCommit() throws Exception {
        String shared = System.getenv("SHARED_CONTRACTS_DIR");
        Contracts.verifyBundle();
        Path vendored = Path.of(System.getenv().getOrDefault("BAOBAB_TEST_CONTRACTS", "src/main/resources/contracts"));
        List<Path> files;
        try (Stream<Path> walk = Files.walk(vendored)) {
            files = walk.filter(Files::isRegularFile).toList();
        }
        var manifest = Contracts.provenance();
        assertEquals(manifest.path("files").size() + 1, files.size(), "complete generated inventory");
        String lock = Files.readString(Path.of("contracts.lock.yaml"));
        assertTrue(lock.contains("  commit: " + manifest.path("commit").asText() + "\n"));
        assertEquals(manifest.path("files").size(), lock.lines().filter(l -> l.startsWith("  - ")).count());
        if (shared != null && !shared.isBlank()) {
            Process git = new ProcessBuilder("git", "-C", shared, "rev-parse", "HEAD").start();
            assertEquals(manifest.path("commit").asText(), new String(git.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8).trim());
            assertEquals(0, git.waitFor());
        }
        for (Path file : files) {
            String relative = vendored.relativize(file).toString().replace('\\', '/');
            if (relative.equals("provenance.json")) continue;
            assertTrue(manifest.path("files").has(relative), "unexpected generated path");
            assertTrue(lock.contains("- contracts/" + relative + "\n"), relative + " is vendored but not declared in contracts.lock.yaml");
            assertArrayEquals(Files.readAllBytes(file), Contracts.resource(relative), "compiled original bytes");
            if (shared != null && !shared.isBlank()) {
                Process git = new ProcessBuilder("git", "-C", shared, "show",
                        manifest.path("commit").asText() + ":contracts/" + relative).start();
                assertArrayEquals(git.getInputStream().readAllBytes(), Files.readAllBytes(file),
                        relative + " differs from the immutable owner blob");
                assertEquals(0, git.waitFor());
            }
        }
    }

    @Test
    void ownerGoldenMatrixAndFormatsMatchTheRuntime() throws Exception {
        try (var input = getClass().getResourceAsStream("/authority-contract-cases.json")) {
            assertTrue(ContractProbe.conformance(com.baobabplatform.subscriptions.json.Json.mapper().readTree(input)) > 100);
        }
        ContractProbe.main(new String[]{"contract-self-test"});
    }

    @Test
    void unknownReferencesAndSensitiveDiagnosticsCannotEscapeTheBundle() {
        var secret = com.baobabplatform.subscriptions.json.Json.mapper().createObjectNode()
                .put("synthetic-secret-never-echo", "synthetic-secret-value");
        assertFalse(Contracts.problems(Contracts.def(Contracts.BILLING, "EnsureBillingProjectionRequest"), secret).isEmpty());
        assertTrue(Contracts.problems(Contracts.def(Contracts.BILLING, "EnsureBillingProjectionRequest"), secret)
                .stream().noneMatch(p -> p.contains("synthetic-secret")));
        assertThrows(IllegalArgumentException.class, () -> Contracts.problems("../../unowned.json", secret));
    }
}
