package com.github.krpkbhdn.ccmanager.claude.proposal;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.krpkbhdn.ccmanager.project.TrustLevel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class TrustPolicyTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @TempDir
    Path project;

    private static PermissionProposal proposal(String tool, String inputJson) {
        return new PermissionProposal("p", "s", Instant.EPOCH, "", tool, MAPPER.readTree(inputJson), List.of());
    }

    private SessionTrust.Grant grant(TrustLevel level) {
        return new SessionTrust.Grant(level, project);
    }

    private static String quoted(Path path) {
        return MAPPER.writeValueAsString(path.toString());
    }

    @Test
    void askNeverAllows() {
        assertThat(TrustPolicy.autoAllows(grant(TrustLevel.ASK), proposal("Read", "{}"))).isFalse();
    }

    @Test
    void trustedAllowsReadsInsideTheProjectOnly() throws IOException {
        var inside = Files.writeString(project.resolve("a.txt"), "x");
        var outside = Files.createTempFile("trust-policy", ".txt");
        try {
            assertThat(TrustPolicy.autoAllows(grant(TrustLevel.TRUSTED), proposal("Read", "{\"file_path\":" + quoted(inside) + "}"))).isTrue();
            assertThat(TrustPolicy.autoAllows(grant(TrustLevel.TRUSTED), proposal("Read", "{\"file_path\":\"a.txt\"}"))).isTrue();
            assertThat(TrustPolicy.autoAllows(grant(TrustLevel.TRUSTED), proposal("Read", "{\"file_path\":" + quoted(outside) + "}"))).isFalse();
            assertThat(TrustPolicy.autoAllows(grant(TrustLevel.TRUSTED), proposal("Read", "{}"))).isFalse();
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void trustedHoldsDotDotTraversalOutOfTheProject() {
        assertThat(TrustPolicy.autoAllows(grant(TrustLevel.TRUSTED), proposal("Read", "{\"file_path\":\"../outside.txt\"}"))).isFalse();
        assertThat(TrustPolicy.autoAllows(grant(TrustLevel.TRUSTED), proposal("Read", "{\"file_path\":" + quoted(project.resolve("sub").resolve("..").resolve("..").resolve("x")) + "}"))).isFalse();
        assertThat(TrustPolicy.autoAllows(grant(TrustLevel.TRUSTED), proposal("Grep", "{\"pattern\":\"x\",\"path\":\"..\"}"))).isFalse();
        assertThat(TrustPolicy.autoAllows(grant(TrustLevel.TRUSTED), proposal("Glob", "{\"pattern\":\"../**/*.txt\"}"))).isFalse();
        assertThat(TrustPolicy.autoAllows(grant(TrustLevel.TRUSTED), proposal("Glob", "{\"pattern\":\"/etc/*\"}"))).isFalse();
    }

    @Test
    void trustedAllowsSearchesWithoutAPathAndHoldsOtherTools() {
        assertThat(TrustPolicy.autoAllows(grant(TrustLevel.TRUSTED), proposal("Grep", "{\"pattern\":\"x\"}"))).isTrue();
        assertThat(TrustPolicy.autoAllows(grant(TrustLevel.TRUSTED), proposal("Glob", "{\"pattern\":\"**/*.java\"}"))).isTrue();
        assertThat(TrustPolicy.autoAllows(grant(TrustLevel.TRUSTED), proposal("Bash", "{}"))).isFalse();
        assertThat(TrustPolicy.autoAllows(grant(TrustLevel.TRUSTED), proposal("Write", "{}"))).isFalse();
    }

    @Test
    void trustedWithoutAKnownFolderHoldsEverything() {
        assertThat(TrustPolicy.autoAllows(new SessionTrust.Grant(TrustLevel.TRUSTED, null), proposal("Grep", "{}"))).isFalse();
    }

    @Test
    void autonomousAllowsEveryToolExceptTheAppsOwn() {
        assertThat(TrustPolicy.autoAllows(grant(TrustLevel.AUTONOMOUS), proposal("Bash", "{}"))).isTrue();
        assertThat(TrustPolicy.autoAllows(grant(TrustLevel.AUTONOMOUS), proposal("mcp__jira__create", "{}"))).isTrue();
        for (var app : List.of("mcp__cc-manager__set_run_configuration", "mcp__cc-manager-app__remove_project", "mcp__cc-manager-code__execute")) {
            assertThat(TrustPolicy.autoAllows(grant(TrustLevel.AUTONOMOUS), proposal(app, "{}"))).as(app).isFalse();
        }
    }
}
