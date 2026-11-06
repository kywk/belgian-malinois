package com.bpm.core.staff;

import com.bpm.core.dto.TaskActionRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Private per-run barrier after real holder/Staff checks and before the engine mutation. */
@TestConfiguration(proxyBeanMethods = false)
class WaveRaceConfig {
    @Primary @Bean
    StaffTaskAuthorization raceAuthorization(StaffIntegrationProperties properties, StaffAuthzClient authz,
            StaffDirectoryClient directory, RepositoryService repository, TaskService tasks,
            @org.springframework.beans.factory.annotation.Qualifier("primaryDataSource") javax.sql.DataSource source) {
        var root = Path.of(System.getenv("WAVE_RACE_DIR"));
        return new StaffTaskAuthorization(properties, authz, directory, repository, tasks, source) {
            @Override public void require(Task task, TaskActionRequest request) {
                super.require(task, request);
                if (request == null || !("complete".equals(request.action()) || "reassign".equals(request.action()))) return;
                var gate = root.resolve(UUID.fromString(task.getId()) + "." + request.action());
                if (!Files.isDirectory(gate)) return;
                if (!TransactionSynchronizationManager.isActualTransactionActive())
                    throw new IllegalStateException("Missing task transaction");
                try {
                    Files.createFile(gate.resolve("entered"));
                    long start = System.nanoTime();
                    while (!Files.exists(gate.resolve("release"))) {
                        if (System.nanoTime() - start > Duration.ofSeconds(25).toNanos())
                            throw new IllegalStateException("Race gate timed out");
                        Thread.sleep(15);
                    }
                } catch (java.nio.file.FileAlreadyExistsException alreadyEntered) {
                    // Only the first request for this task/action participates in the barrier.
                    return;
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                } catch (java.io.IOException ex) { throw new IllegalStateException(ex); }
            }
        };
    }
}
