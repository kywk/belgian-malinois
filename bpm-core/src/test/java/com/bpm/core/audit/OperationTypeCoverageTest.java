package com.bpm.core.audit;

import com.bpm.core.audit.model.OperationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 每個 {@link OperationType} 要嘛真的被發出，要嘛明確宣告為未實作
 * （security-audit P2-4）。
 *
 * <h2>這個測試在防什麼</h2>
 *
 * <p>一個存在於 enum 卻沒有任何程式碼發出的值，會讓讀清單的人
 * （包含稽核人員）以為那個操作有被記錄。而「未使用」有兩種完全不同的意義：
 * 操作還沒實作（沒有缺口），或操作在跑但沒留軌跡（真的缺口）。
 *
 * <p>P2-4 修掉的三個都屬於後者：{@code CONFIG_CHANGE}（六個通知端點、
 * 四個外部系統端點、兩個變數規格端點全都零稽核）、{@code DATA_ACCESS}
 * （查稽核本身沒有紀錄）、{@code PROCESS_COMPLETE}（結案不在軌跡上）。
 * 三者的功能全都在跑。
 *
 * <p>光靠註解區分不住，因為註解不會在事情變化時提醒任何人。這個測試會。
 *
 * <h2>為什麼掃原始碼而不是跑一輪操作</h2>
 *
 * <p>「跑一輪所有操作再檢查稽核表」需要涵蓋每一條業務路徑，實務上做不到，
 * 而且漏掉一條就會誤判為「未使用」。掃原始碼找 {@code OperationType.X}
 * 的引用是保守的方向：可能把「有引用但實際上到不了」誤判為已使用，
 * 但不會把真正沒有引用的漏掉 —— 而後者才是這個測試要抓的。
 */
class OperationTypeCoverageTest {

    /** 只掃 main，測試裡的引用不算「系統會發出它」。 */
    private static final Path MAIN = Path.of("src/main/java");
    private static final Path ENUM_FILE =
            MAIN.resolve("com/bpm/core/audit/model/OperationType.java");

    private static Set<OperationType> referencedInMainCode() throws IOException {
        List<String> sources = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path f : files.filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> !f.equals(ENUM_FILE))   // enum 自己的宣告不算引用
                    .toList()) {
                sources.add(Files.readString(f, StandardCharsets.UTF_8));
            }
        }

        var referenced = EnumSet.noneOf(OperationType.class);
        for (OperationType t : OperationType.values()) {
            // 兩種發出方式都算：OperationType.X（型別安全）與 "X"（字串）。
            // 字串形式仍存在於少數地方（例如 TaskController 的 TASK_COMMENT），
            // 那是 P1-1 的成因，但就「有沒有被發出」而言它確實算。
            String typed = "OperationType." + t.name();
            String literal = '"' + t.name() + '"';
            if (sources.stream().anyMatch(s -> s.contains(typed) || s.contains(literal))) {
                referenced.add(t);
            }
        }
        return referenced;
    }

    @Test
    @DisplayName("每個 OperationType 要嘛被使用，要嘛列在 NOT_YET_IMPLEMENTED")
    void everyOperationTypeIsEitherUsedOrDeclaredUnimplemented() throws IOException {
        var referenced = referencedInMainCode();

        var unaccounted = EnumSet.allOf(OperationType.class);
        unaccounted.removeAll(referenced);
        unaccounted.removeAll(OperationType.NOT_YET_IMPLEMENTED);

        assertThat(unaccounted)
                .as("這些型別沒有任何程式碼發出，也沒有宣告為未實作。"
                        + "請確認是「操作還沒做」（加進 NOT_YET_IMPLEMENTED）"
                        + "還是「操作在跑但沒留軌跡」（那是稽核缺口，要補發稽核）")
                .isEmpty();
    }

    @Test
    @DisplayName("NOT_YET_IMPLEMENTED 裡的型別不該已經在使用")
    void unimplementedListDoesNotGoStale() throws IOException {
        // 反方向同樣重要：某個預留操作被實作並加上稽核之後，
        // 它應該從清單移除。留在清單裡會讓下一個人以為那個操作仍沒有軌跡。
        var referenced = referencedInMainCode();

        var stale = EnumSet.copyOf(OperationType.NOT_YET_IMPLEMENTED);
        stale.retainAll(referenced);

        assertThat(stale)
                .as("這些型別已經有程式碼在發出，請從 NOT_YET_IMPLEMENTED 移除")
                .isEmpty();
    }

    @Test
    @DisplayName("P2-4 修掉的三個缺口必須真的被發出")
    void gapsClosedByP24AreActuallyEmitted() throws IOException {
        // 這三個原本都是「功能在跑但沒留軌跡」。寫死在測試裡，
        // 這樣即使日後有人把它們加回 NOT_YET_IMPLEMENTED（誤判為未實作），
        // 上面那個測試也不會紅，但這個會。
        var referenced = referencedInMainCode();

        assertThat(referenced)
                .as("CONFIG_CHANGE：通知設定／外部系統／變數規格的變更")
                .contains(OperationType.CONFIG_CHANGE)
                .as("DATA_ACCESS：稽核查詢本身")
                .contains(OperationType.DATA_ACCESS)
                .as("PROCESS_COMPLETE：案件結案")
                .contains(OperationType.PROCESS_COMPLETE);
    }

    @Test
    @DisplayName("測試本身不可空轉 —— 掃得到原始碼且清單非空")
    void testIsNotVacuous() throws IOException {
        assertThat(Files.isDirectory(MAIN))
                .as("找不到 src/main/java，掃描結果會是空的，整個測試就變成空門")
                .isTrue();
        assertThat(referencedInMainCode())
                .as("一個型別都沒掃到，掃描邏輯壞了")
                .isNotEmpty();
        assertThat(OperationType.values().length).isGreaterThan(10);
    }
}
