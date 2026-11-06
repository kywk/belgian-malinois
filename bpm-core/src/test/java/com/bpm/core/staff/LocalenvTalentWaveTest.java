package com.bpm.core.staff;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalenvTalentWaveTest {

    @TempDir Path temp;

    @Test
    void vhostMustMatchReadyParentRunId() throws Exception {
        String runId = "0123456789abcdef0123456789abcdef";
        Path run = Files.createDirectory(temp.resolve(runId));
        Path ready = run.resolve("bindings.json");
        String expected = "flashlight-wave-" + runId;

        assertThat(LocalenvTalentWave.expectedVhost(ready)).isEqualTo(expected);
        LocalenvTalentWave.requireVhost(expected, expected);
        assertThatThrownBy(() -> LocalenvTalentWave.requireVhost("flashlight-wave", expected))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> LocalenvTalentWave.requireVhost("flashlight-wave-ffffffffffffffffffffffffffffffff", expected))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsNonRunReadyParent() {
        assertThatThrownBy(() -> LocalenvTalentWave.expectedVhost(temp.resolve("bindings.json")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void callbackPortCanTargetLoopbackProxyAndDefaultsToAgent() {
        assertThat(LocalenvTalentWave.callbackUrl("32123", "18002"))
                .isEqualTo("http://localhost:32123/internal/bpm/v1/webhook");
        assertThat(LocalenvTalentWave.callbackUrl(null, "18002"))
                .isEqualTo("http://localhost:18002/internal/bpm/v1/webhook");
        assertThatThrownBy(() -> LocalenvTalentWave.callbackUrl("65536", "18002"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LocalenvTalentWave.callbackUrl("127.0.0.1:1234", "18002"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void syntheticReplayPrincipalOnlyGetsManageForAdminRoutes() {
        Set<String> admins = Set.of("wave-dlq-admin");
        List<String> adminSubjects = List.of("staff-wave-dlq-admin", "org-50310001");
        List<String> ordinarySubjects = List.of("staff-w9000002", "org-50310001");

        assertThat(StaffTestProvider.canReplayDlq(adminSubjects, StaffPermissions.MANAGE, admins)).isTrue();
        assertThat(StaffTestProvider.canReplayDlq(adminSubjects, StaffPermissions.READ, admins)).isFalse();
        assertThat(StaffTestProvider.canReplayDlq(ordinarySubjects, StaffPermissions.MANAGE, admins)).isFalse();
    }
}
