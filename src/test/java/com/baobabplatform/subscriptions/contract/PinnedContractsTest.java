package com.baobabplatform.subscriptions.contract;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The vendored contracts are byte-for-byte the Shared commit contracts.lock.yaml
 * pins. SHARED_CONTRACTS_DIR names a baobab-platform/shared checkout at that
 * commit (CI checks it out); the test is skipped without one.
 */
class PinnedContractsTest {
    @Test
    void vendoredContractsMatchThePinnedSharedCommit() throws IOException {
        String shared = System.getenv("SHARED_CONTRACTS_DIR");
        assumeTrue(shared != null && !shared.isBlank(), "SHARED_CONTRACTS_DIR not set");
        Path vendored = Path.of(System.getenv().getOrDefault("BAOBAB_TEST_CONTRACTS", "src/main/resources/contracts"));
        List<Path> files;
        try (Stream<Path> walk = Files.walk(vendored)) {
            files = walk.filter(Files::isRegularFile).toList();
        }
        assertTrue(files.size() >= 10, "vendored contracts are missing");
        String lock = Files.readString(Path.of("contracts.lock.yaml"));
        for (Path file : files) {
            String relative = vendored.relativize(file).toString().replace('\\', '/');
            assertTrue(lock.contains("- contracts/" + relative + "\n"), relative + " is vendored but not declared in contracts.lock.yaml");
            assertArrayEquals(Files.readAllBytes(Path.of(shared, "contracts", relative)), Files.readAllBytes(file),
                    relative + " differs from the pinned Shared commit");
        }
    }
}
