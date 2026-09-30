package com.bpm.core.external;

import com.bpm.core.security.CallerId;
import com.bpm.core.model.ExternalSystem;
import com.bpm.core.model.ProcessVariableSpec;
import com.bpm.core.repository.ProcessVariableSpecRepository;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
public class ProcessVariableSpecController {

    private final ProcessVariableSpecRepository repo;
    private final ExternalSystemPolicy policy;
    private final com.bpm.core.audit.ConfigChangeAuditor auditor;

    public ProcessVariableSpecController(ProcessVariableSpecRepository repo,
                                          ExternalSystemPolicy policy,
                                         com.bpm.core.audit.ConfigChangeAuditor auditor) {
        this.repo = repo;
        this.policy = policy;
        this.auditor = auditor;
    }

    /**
     * 整批取代某個流程的變數規格。
     *
     * <h2>⚠️ 缺陷：重複儲存必定 500（#86）</h2>
     *
     * <p>改動前這裡是 {@code deleteByProcessDefinitionKey(key)}（衍生刪除）接著
     * {@code saveAll}。衍生刪除是「SELECT 出 entity → {@code em.remove()}」，
     * 而 {@code em.remove()} 只<b>排程</b>刪除；Hibernate 的 flush 順序固定是
     * <b>INSERT 在 DELETE 之前</b>，於是同一個 flush 裡每一筆 INSERT 都撞上
     * 尚未刪掉的同名舊列 →
     * {@code Violation of UNIQUE KEY constraint 'uk_bpm_process_variable_spec_key_name'} → 500。
     *
     * <p><b>實測（修前）：</b>同一個 key 連續呼叫兩次（內容相同），
     * 第一次 200、第二次 500。而 {@code ProcessVariableSpecAdmin.vue} 的
     * 「儲存」按鈕正是走這條路徑 —— 管理頁第二次按儲存必定壞。
     *
     * <p><b>⚠️ 觸發條件比「key 已有資料」更窄。</b>必須是<b>新批次與既有規格
     * 有同名變數</b>才會撞：INSERT 之所以失敗，是因為它寫進去的那個
     * {@code (key, variableName)} 還被舊列佔著。實測（缺陷期間）：
     * <ul>
     *   <li>新舊有重疊（改內容、或原樣重存）→ <b>500</b></li>
     *   <li>新舊完全不重疊（整批換成別的名字）→ 200，因為沒有任何一筆 INSERT
     *       會撞到同名的舊列</li>
     *   <li>送空陣列 → 200，因為沒有 INSERT</li>
     * </ul>
     * 這解釋了為什麼這個缺陷可以躲過「手動試一次看看」：剛建好規格時第一次存是好的，
     * 而管理頁的正常使用流程（改設定 → 儲存）必然與既有變數重疊。
     * 負向控制組也確認了這點 —— 缺陷期間紅的 4 條測試全部是「有重疊」形狀。
     *
     * <h2>修法：刪除改成原生 SQL（{@code deleteAllByProcessDefinitionKey}）</h2>
     *
     * <p>原生 SQL 在呼叫當下就送到資料庫，完全不進 Hibernate 的動作佇列，
     * 所以「INSERT 排在 DELETE 前面」這個排序再也碰不到它。
     * 該方法為什麼不能用衍生刪除寫在 repository 的註解裡。
     *
     * <p><b>不</b>選「衍生刪除後補 {@code flush()}」：那樣也能修好，
     * 但規則會散在「刪除」與「記得 flush」兩處 —— 這正是這個缺陷的成因
     * （create 與 update 對同一個參數有兩套規則那次也是同一個成因）。
     * 讓刪除只有一種形狀比較重要。
     *
     * <h2>同一批內重複變數名：輸入驗證（#87）</h2>
     *
     * <p>送 {@code [{"variableName":"x",…},{"variableName":"x",…}]} 原本仍然是 500，
     * 那是<b>另一個</b>根因：呼叫端送了互相衝突的資料，與刪除／寫入的順序無關，
     * 所以本方法的修法（原生 SQL 刪除）碰不到它。
     * 修法是輸入驗證 —— 見 {@link #requireUsableVariableNames}。
     *
     * <h2>空白與 null 的名稱：同樣是輸入驗證（#87-2／#87-3，使用者已裁決）</h2>
     *
     * <p>使用者 2026-09-30 裁決「空白與 null 都回 400」。裁決前這裡分別是
     * {@code ""} → 200（寫得進資料庫）與 {@code null} → 500（{@code NOT NULL} 約束），
     * 兩者都是缺陷而其中一個危害更大 —— 理由與修法都寫在
     * {@link #requireUsableVariableNames}，本方法不再為此分岔。
     */
    @PostMapping("/api/admin/process-definitions/{key}/variable-spec")
    @Transactional("primaryTransactionManager")
    public List<ProcessVariableSpec> batchSave(@PathVariable String key,
                                                @RequestBody List<ProcessVariableSpec> specs,
                                                @CallerId String operatorId) {
        // ⚠️ 必須在「刪掉舊規格」之前擋。放後面會變成「資料已經清空才回 400」——
        // 那正是本方法最不能出現的組合（見 requireUsableVariableNames 的說明）。
        requireUsableVariableNames(specs);
        // 先記下舊的變數名再刪 —— 這個端點是「整批取代」，
        // 所以移除了哪些變數跟新增了哪些一樣重要：
        // 刪掉一個 required 變數等於放寬外部系統的輸入驗證。
        var removed = repo.findByProcessDefinitionKeyOrderByVariableName(key).stream()
                .map(ProcessVariableSpec::getVariableName).sorted().toList();
        repo.deleteAllByProcessDefinitionKey(key);
        // 這個端點不在 security-audit 的 P0-4 清單內，但問題完全相同：
        // 以 entity 當 @RequestBody，夾帶 id 就會讓 saveAll 走 merge。
        // ⚠️ 這個 setId(null) 也讓「只刪真正消失的那幾列」這種修法不可行 ——
        // 保留舊 id 等於替夾帶 id 開一個繞道（理由見 repository 的註解）。
        specs.forEach(s -> {
            s.setId(null);
            s.setProcessDefinitionKey(key);
        });
        List<ProcessVariableSpec> saved = repo.saveAll(specs);

        // 變數規格限制外部系統能傳什麼進流程 —— 那是輸入驗證的定義，
        // 改動它需要軌跡（security-audit P2-4）。這個端點原本零稽核。
        auditor.record(operatorId, "process-variable-spec", "replace", key, java.util.Map.of(
                "processDefinitionKey", key,
                "before", String.join(",", removed),
                "after", saved.stream().map(ProcessVariableSpec::getVariableName).sorted()
                        .collect(java.util.stream.Collectors.joining(",")),
                "requiredAfter", saved.stream().filter(x -> Boolean.TRUE.equals(x.getRequired()))
                        .map(ProcessVariableSpec::getVariableName).sorted()
                        .collect(java.util.stream.Collectors.joining(","))));
        return saved;
    }

    /**
     * 同一批內的 {@code variableName} 必須「<b>每一列都有名字</b>」且「<b>彼此不重複</b>」
     * （#87、#87-2／#87-3）。
     *
     * <h2>規則一：重複的名稱（#87）</h2>
     *
     * <p><b>⚠️ 缺陷：送重複的名字必定 500，而且與 #86 是不同的根因</b>
     *
     * <p>{@code ProcessVariableSpec} 上有
     * {@code @UniqueConstraint(processDefinitionKey, variableName)}，
     * 所以送 {@code [{"variableName":"x",…},{"variableName":"x",…}]}
     * 必然在 flush 時撞約束 → {@code DataIntegrityViolationException} → 500。
     *
     * <p><b>這與 #86 無關，#86 的修法也解決不了它。</b>#86 是「刪除與寫入在同一次
     * flush 的順序問題」（{@code em.remove()} 只排程不落地，而 Hibernate 把
     * INSERT 排在 DELETE 之前）；本項是<b>呼叫端送了互相衝突的資料</b> ——
     * 資料庫層面兩筆本來就不可能同時存在，換任何刪除方式都不會改變這件事。
     * 實測（#86 修好之後）：新 key 送兩筆同名 → 仍然 500。
     *
     * <h2>危害比「500」本身大：狀態碼會教呼叫端做錯事</h2>
     *
     * <p>500 的慣例語意是「伺服器的問題，稍後重試」。但重試<b>永遠不會成功</b>
     * —— payload 沒有變，結果不會變。呼叫端（以及看 log 的人）會因此把時間
     * 花在重試與查伺服器狀態上，而真正的修法是改 payload。
     * 這與 #84 修掉的 {@code id 不存在 → 500} 是同一個理由：
     * <b>400 才會告訴呼叫端「這是請求本身的問題，改 payload」</b>。
     *
     * <h2>規則二：空白與 null 的名稱（#87-2／#87-3，使用者已裁決）</h2>
     *
     * <p>裁決前的實測：{@code ""} 與 {@code "  "} → <b>200</b>（真的寫進資料庫），
     * {@code null} → <b>500</b>（{@code NOT NULL} 約束）。裁決：<b>都回 400</b>。
     *
     * <p><b>為什麼空白名要擋</b>：{@code variableName} 就是外部系統要塞進流程的
     * key（spec §8.5）。一個叫 {@code ""} 的變數<b>永遠比對不到任何東西</b> ——
     * 設定它的人看不到任何異常（畫面上儲存成功、稽核上有一筆 replace），
     * 但外部系統的輸入驗證等同少了一項：它宣告「這個 key 是被接受的」，
     * 實際上接受的是一個沒人送得出來的值。而 {@code required=true} 的空白名
     * 危害更大：{@code ExternalApiController.validateVariables} 會拿它去比對，
     * 結果是<b>每一次</b>外部發起都回 400，而管理頁上看不出任何設定有問題。
     *
     * <p><b>為什麼 null 從 500 改成 400</b>：理由與上一段相同，但多一條 ——
     * 500 的語意是「稍後重試」，而 {@code null} 重試永遠不會成功（payload 沒變，
     * 約束照樣擋）。而且對呼叫端而言 {@code null} 與 {@code ""} 是同一類錯誤
     * （沒有給名字），回不同的狀態碼等於要求呼叫端處理兩種形狀。
     *
     * <h2>⚠️「什麼叫空白」必須複用 {@link #dbComparisonKey}，不能寫 {@code isBlank()}</h2>
     *
     * <p>這一條是本方法最容易寫錯的地方。判準是
     * <b>{@code dbComparisonKey(name).isEmpty()} —— 「資料庫分不出這個名字
     * 與空字串的差別」，而這正是要擋的東西</b>：
     *
     * <table border="1">
     *   <caption>為什麼 isBlank() 不對（實測值沿用 #87 對 MSSQL 的實查）</caption>
     *   <tr><th>輸入</th><th>{@code dbComparisonKey}</th><th>本方法</th><th>{@code isBlank()}</th></tr>
     *   <tr><td>{@code ""}</td><td>空</td><td><b>擋</b></td><td>擋（相同）</td></tr>
     *   <tr><td>{@code "  "}（只有半形空白）</td><td>空</td><td><b>擋</b></td><td>擋（相同）</td></tr>
     *   <tr><td>{@code "　"}（全形空白）</td><td>空（折成半形後被忽略）</td><td><b>擋</b></td><td>擋（相同）</td></tr>
     *   <tr><td>{@code null}</td><td>—</td><td><b>擋</b></td><td>擋（相同）</td></tr>
     *   <tr><td>{@code "\t"}</td><td>{@code "\t"}</td><td>放行</td><td><b>擋（誤擋）</b></td></tr>
     *   <tr><td>{@code "\u00A0"}（NBSP）</td><td>NBSP</td><td>放行</td><td>放行</td></tr>
     *   <tr><td>{@code "\n"}</td><td>{@code "\n"}</td><td>放行</td><td><b>擋（誤擋）</b></td></tr>
     * </table>
     *
     * <p>分歧全部在「誤擋合法資料」那一側，而那正是本專案前端檢查必須避免的
     * 方向（#87 建立的原則：<b>前端不得擋掉後端會接受的資料</b>）。
     * {@code String.isBlank()} 走 {@code Character.isWhitespace}，會把 TAB、
     * 換行算成空白，但 #87 實測（{@code sys.columns} 確認欄位 collation 後
     * 對 MSSQL 實查）<b>它們在資料庫裡不是空白</b> —— ANSI padding 只忽略
     * 尾端 U+0020，{@code amount} 與 {@code amount\t} 是<b>兩個不同的變數</b>。
     * 用 {@code isBlank()} 等於禁止使用那些合法的名稱。
     *
     * <p>反過來說，{@code "  "} 與 {@code "　"} <b>正是要擋的形狀</b>：
     * 它們在資料庫層面與 {@code ""} 是同一個字串，所以「兩個空白名互相衝突」
     * 本來就會撞唯一約束；而單獨一個空白名之所以今天能存進去，就是因為
     * 沒有第二個東西去撞它。用同一個比較鍵判定，規則才會與資料庫一致。
     *
     * <h2>⚠️ 一次把所有問題都回報，不是只報第一個</h2>
     *
     * <p>空白名與重複名經常同時出現（{@code addRow()} 產生的就是空字串，
     * 按兩次「新增行」＝兩列空白＋一組重複）。只報一類的話，使用者修完再按
     * 一次儲存才看到下一類 —— 那正是「規則要一次說清楚」的相反。
     * 兩類問題合成同一個 400 回應（呼叫端仍然只需要處理一個狀態碼）。
     *
     * <h2>⚠️ 檢查必須在 {@code deleteAllByProcessDefinitionKey} 之前</h2>
     *
     * <p>這個端點是「整批取代」，所以順序決定了「被拒的請求有沒有副作用」。
     * 放在刪除之後，回 400 的那一刻舊規格<b>已經被刪掉</b>；
     * 而整個方法跑在 {@code @Transactional} 裡，例外會讓交易回捲，
     * 資料其實會回來 —— 但那是「靠回捲救回來」，不是「根本沒動過」。
     * 兩者的差別在非資料庫副作用（稽核、呼叫端的重試邏輯），
     * 而且回捲能不能救回來取決於呼叫鏈上有沒有別人把例外吃掉。
     * 驗證放在最前面，拒絕的時候資料庫根本沒被碰過。
     *
     * <h2>被擋下時不寫稽核</h2>
     *
     * <p>理由與 {@link #update} 的 404 完全相同：一筆 {@code CONFIG_CHANGE/replace}
     * 代表「這個變更發生了」，而實際上什麼都沒發生。
     * 稽核窗戶會如實記錄（稽核系統採 fail-closed，「多記」而不是「漏記」，
     * 見 {@code AuditEventPublisher} 類別註解），但那個 500 時代留下的
     * 「假變更紀錄」窗口，在這個檢查之後就不會再發生。
     *
     * <h2>為什麼是 400 而不是 409</h2>
     *
     * <p>與 {@code NotifyAdminController} 的既定政策一致（{@code requireExistingTemplate}）：
     * 呼叫端送來的參照／內容本身不合法，是<b>請求有誤</b>。
     * 409 是「東西存在，但現在的狀態不允許這個動作」——
     * 那是刪除「仍被引用的模板」那一類的狀態衝突，語意不同。
     *
     * <h2>為什麼不用 Bean Validation（{@code @NotBlank} + {@code @Valid}）</h2>
     *
     * <p>那是最短的路，三個理由：
     * <ul>
     *   <li><b>它會排在 #85 的 404 守衛之前。</b>驗證發生在 controller 方法
     *       <i>之前</i>，所以「key 對不上而且名字是空白」會回 400 而不是 404。
     *       這正是 handoff 第 7.1 節記下來的那個坑：守衛必須排在 action 形狀
     *       檢查之後，否則狀態碼會被綁死在錯誤的地方。</li>
     *   <li><b>它用的就是 {@code isBlank()} 那套空白定義</b>，會誤擋
     *       {@code "\t"} 這種資料庫分得開的名稱（見上面的表）。</li>
     *   <li><b>它需要新的例外處理</b>（{@code MethodArgumentNotValidException}），
     *       回應形狀會與本 repo 其它 400 不一致，而全域
     *       {@code @RestControllerAdvice} 是 #69 已決定不做的事。</li>
     * </ul>
     *
     * <h2>為什麼不在前端擋就好</h2>
     *
     * <p>前端擋是 UX 層的保護，管理頁確實是主要入口；但這個端點是
     * {@code /api/admin/**}，curl 一行就繞得過去，而且其他呼叫端
     * （腳本、匯入工具）都會撞上。<b>後端驗證才是邊界</b>，
     * 前端的檢查只是讓錯誤在送出前就以人看得懂的形式出現。
     */
    private static void requireUsableVariableNames(List<ProcessVariableSpec> specs) {
        // 一次掃描收集兩類問題，最後合成一個 400。
        // 空白名稱的位置用「第 N 列」（1-based）：payload 的順序就是畫面上
        // 由上而下的順序（ProcessVariableSpecAdmin.vue 直接送 specs），
        // 所以使用者拿這個號碼對照畫面是準的。
        List<String> nameless = new ArrayList<>();
        // LinkedHashMap：重複的名字要照「送出順序」報，而不是照雜湊順序 ——
        // 呼叫端是照畫面上由上而下的順序在對照錯誤訊息。
        // 值是同一個比較鍵底下的**所有寫法**（照送出順序、不去重），
        // 而不只是第一個：最常見的失敗形狀是 Amount 與 amount，
        // 只報其中一個的話，使用者看到訊息仍然不知道自己打錯了哪一個。
        Map<String, List<String>> byComparisonKey = new LinkedHashMap<>();
        for (int i = 0; i < specs.size(); i++) {
            String raw = specs.get(i).getVariableName();
            String namelessWhy = namelessReason(raw);
            if (namelessWhy != null) {
                nameless.add("第 " + (i + 1) + " 列" + namelessWhy);
                continue;
            }
            byComparisonKey.computeIfAbsent(dbComparisonKey(raw), k -> new ArrayList<>()).add(raw);
        }
        // 出現兩次以上才算重複（同一個寫法送三次也是重複）。
        // 顯示時才把完全相同的寫法收斂成一個 —— 報三次「a、a、a」沒有任何新資訊。
        List<String> duplicated = byComparisonKey.values().stream()
                .filter(spellings -> spellings.size() >= 2)
                .map(ProcessVariableSpecController::describeCollisions)
                .toList();
        if (nameless.isEmpty() && duplicated.isEmpty()) return;
        StringBuilder message = new StringBuilder("變數名稱有問題：");
        if (!nameless.isEmpty()) {
            message.append(String.join("、", nameless)).append(" 的變數名稱是空白。");
        }
        if (!duplicated.isEmpty()) {
            message.append("同一個流程底下變數名稱不得重複: ")
                    .append(String.join("、", duplicated)).append("。");
        }
        message.append("變數名稱是外部系統要塞進流程的 key，空白或重複的名稱永遠比對不到任何值；")
                .append("請每一列都填上不同的名稱再送出。資料庫以不分大小寫、"
                        + "不分全形半形、忽略尾端空白的方式比對名稱。");
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message.toString());
    }

    /**
     * 單一名稱「沒有名字」的問題；沒有問題回 {@code null}（判定規則只寫在這裡，
     * {@link #requireUsableVariableNames} 與單數版
     * {@link #requireUsableVariableName} 共用）。
     *
     * <p>回傳 {@code null} 表示「可以」，否則回一句可以直接併進訊息的說明。
     * 用回傳值而不是丟例外，是為了讓整批的驗證能把「空白」與「重複」
     * 一次收集起來回報。
     *
     * <p>⚠️ 為什麼是 {@code dbComparisonKey(raw).isEmpty()} 而不是
     * {@code raw == null || raw.isBlank()}：理由與取捨完整寫在
     * {@link #requireUsableVariableNames} 的表格。簡單版：{@code isBlank()}
     * 會把 TAB／換行算成空白，但實測那些在資料庫裡<b>不是</b>空白
     * （ANSI padding 只忽略尾端 U+0020），擋下它們等於禁止使用合法的名稱；
     * 而 {@code "  "} 與全形空白在資料庫裡就是空字串，本來就是要擋的形狀。
     */
    private static String namelessReason(String raw) {
        if (raw == null) return "（完全沒有給）";
        if (dbComparisonKey(raw).isEmpty()) return "（只有空白字元）";
        return null;
    }

    /**
     * 單一名稱的版本，供 {@link #update} 使用（同一條規則、同一個判準）。
     *
     * <p>改名成空白名一樣沒有意義：{@code variableName} 是外部系統的 key，
     * 空白名永遠比對不到值（理由見 {@link #requireUsableVariableNames}）。
     * 放行它的後果與允許建立空白名完全相同，只是路徑換成 PUT。
     */
    private static void requireUsableVariableName(String raw) {
        String why = namelessReason(raw);
        if (why == null) return;
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "變數名稱不得為空白" + why + "。變數名稱是外部系統要塞進流程的 key，"
                        + "空白名稱永遠比對不到任何值，請給這一個變數一個名稱再送出。");
    }

    /**
     * 把互相衝突的寫法寫成一句人看得懂的話，例如 {@code Amount／amount}。
     *
     * <p>空白名稱已經在 {@link #namelessReason} 被擋掉了，所以這裡的
     * {@code isBlank()} 分支只剩「資料庫當成一般字元的空白」走得到
     * （TAB、換行、NBSP…）。那些確實是空白字元，印出「（空白）」比印出一個
     * 看不見的字元有用。
     */
    private static String describeCollisions(List<String> spellings) {
        return spellings.stream().distinct()
                .map(raw -> raw.isBlank() ? "（空白）" : raw)   // 空白印出來會是看不見的東西
                .collect(java.util.stream.Collectors.joining("／"));
    }

    /**
     * 把變數名轉成「資料庫認得出來是同一個名字」的比較鍵（#87）。
     *
     * <h2>為什麼不能用 {@code Set<String>} 直接比對字串</h2>
     *
     * <p>因為本專案三個資料庫的文字欄位定序是 {@code SQL_Latin1_General_CP1_CI_AS}，
     * 也就是 <b>CI_AS</b>：不分大小寫、<b>分</b>重音。實測（對 MSSQL 實查，
     * 不是讀文件）以下四種「肉眼不同的名字」在資料庫層面<b>互相衝突</b>，
     * 也就是修補前照樣 500：
     *
     * <table border="1">
     *   <caption>實測結果（sqlcmd 對照，欄位 collation 已查 sys.columns 確認）</caption>
     *   <tr><th>比對</th><th>結果</th><th>規則</th></tr>
     *   <tr><td>{@code amount} / {@code AMOUNT}</td><td>衝突</td><td>CI（不分大小寫）</td></tr>
     *   <tr><td>{@code amount} / {@code amount }</td><td>衝突</td><td>ANSI padding（忽略尾端空白）</td></tr>
     *   <tr><td>{@code amount} / {@code amount&nbsp;&nbsp;}</td><td>衝突</td><td>同上</td></tr>
     *   <tr><td>{@code Ａ} / {@code A}</td><td>衝突</td><td>全形半形同一個字</td></tr>
     *   <tr><td>{@code 　}（全形空白）/ {@code }</td><td>衝突</td><td>同上</td></tr>
     *   <tr><td>{@code amount} / {@code amount\t}</td><td>不衝突</td><td>TAB 不是空白</td></tr>
     *   <tr><td>{@code amount} / {@code amount }</td><td>不衝突</td><td>NBSP 不是空白</td></tr>
     *   <tr><td>{@code café} / {@code cafe}</td><td>不衝突</td><td>AS（<b>分</b>重音）</td></tr>
     *   <tr><td>{@code ①} / {@code 1}</td><td>不衝突</td><td>相容但不等價</td></tr>
     * </table>
     *
     * <p>所以「大小寫不同」這個形狀不是雞蛋肋，<b>它就是這個缺陷最容易發生的形狀</b>
     * （使用者在表格裡輸入 {@code Amount} 與 {@code amount}），
     * 而 {@code Set<String>} 恰好擋不住它 —— 修完之後同一個請求還是 500，
     * 等於「這個 500 修掉了嗎」變成一個無法回答的問題。
     *
     * <h2>為什麼不是「乾脆不管比對規則，交給資料庫」</h2>
     *
     * <p>那條路是「讓唯一約束去擋」，也就是現在的行為：回 500。
     * 另一條是「每次都問資料庫這批名字有沒有重複」（例如
     * {@code SELECT variable_name FROM (VALUES (?,?)) … GROUP BY …}）。
     * 那確實是精確的，代價是這個 admin 端點每次多一組動態原生 SQL；
     * 而這個端點是整批取代，批次大小本來就是「一個流程有幾個變數」，
     * 通常個位數。為了個位數的批次在每次點儲存時多打一個資料庫往返，
     * 不划算。<b>更重要的是</b>：這裡寧可多擋一點點，也不要漏擋 ——
     * 漏擋的後果是 500（缺陷還在），多擋的後果是 400 附帶一個明確的名字
     * （使用者改個名字就好了）。
     *
     * <h2>⚠️ 這個鍵是<b>近似值</b>，已知的分歧記在下面</h2>
     *
     * <p>Windows 定序的權重表沒有對應的 Java API，所以無法完全重現。
     * 用 26 組名稱對照 MSSQL 實測的結果：<b>24 組一致</b>，
     * 剩下 2 組分歧，而且<b>方向全部是「我這裡說衝突、資料庫說不衝突」</b>
     * （會擋下合法資料，不會漏放會 500 的資料）：
     * <ul>
     *   <li>{@code ı}（U+0131）→ Java 大寫化成 {@code I}，而資料庫認為它與
     *       {@code i} / {@code I} <b>不同</b>（SQL_Latin1_General 的土耳其語排序規則）。</li>
     *   <li>{@code ǅ}（U+01C5）與 {@code ǆ}（U+01C6）→ Java 分別大寫成
     *       {@code Ǆ} / {@code Ǉ}（不同），這裡反而把它們判成相同；資料庫視為不同。</li>
     * </ul>
     * 兩個都是幾乎不會出現在流程變數名裡的字元，而誤擋的後果是
     * <b>400 附帶一個明確的名字</b>（使用者換個名字就好了），
     * 沒有任何一種會讓資料被寫壞。反方向的漏擋才是危險的那一邊，
     * 而實測 26 組裡<b>沒有任何一組</b>落在那個方向。
     * 真要徹底消滅分歧只能讓資料庫自己判斷，那是效能與複雜度的取捨，
     * 寫在上面的段落。
     *
     * <h2>刻意<b>不做</b>的事</h2>
     *
     * <ul>
     *   <li><b>不折疊重音</b>：定序是 AS（分重音），{@code café} 與 {@code cafe}
     *       在資料庫裡是<b>兩個不同的變數</b>，折疊等於禁止使用合法的名稱。</li>
     *   <li><b>不用整串 {@code NFKC}</b>：它會把 {@code ①} 折成 {@code 1}、
     *       {@code ﬁ} 折成 {@code fi}、{@code ㎡} 折成 {@code m2}。
     *       實測 {@code ①} 與 {@code 1} 在資料庫裡<b>不衝突</b>，
     *       所以整串 NFKC 會誤擋合法資料。只對「寬度不敏感」確實成立
     *       的三段範圍做逐字折疊。</li>
     *   <li><b>不 trim 前導或內部空白</b>：實測 {@code " a"} 與 {@code "a"}
     *       <b>不衝突</b>，只有<b>尾端</b> 的 U+0020 會被忽略。
     *       {@code trim()} 會誤擋 {@code " a"}。</li>
     * </ul>
     *
     * <h2>這個鍵同時是「什麼叫空白名稱」的判準（#87-2／#87-3）</h2>
     *
     * <p>⚠️ 因為 ② 把全形空白折成半形、③ 忽略尾端 U+0020，所以
     * {@code ""}、{@code "  "}、{@code "　"} 的比較鍵<b>都是空字串</b> ——
     * 資料庫分不出它們與空字串的差別，於是 {@code requireUsableVariableNames}
     * 直接用 {@code dbComparisonKey(name).isEmpty()} 判定「沒有名字」。
     * 這個寫法讓規則自動繼承本方法所有實測過的細節，也讓它<b>不會</b>誤擋
     * {@code "\t"}、{@code "\n"}、NBSP 這種資料庫當成一般字元的空白
     * （理由與取捨見該方法的表格）。
     */
    private static String dbComparisonKey(String name) {
        // ① 標準化（canonical）成 NFC：實測 é（NFC）與 é（NFD）
        //    在資料庫裡是同一個字元，Java 字串則是兩個不一樣的字串。
        //    NFC 是「無損的標準化」，不會把兩個不同的字合併，所以不可能誤擋。
        String canonical = Normalizer.normalize(name, Normalizer.Form.NFC);
        StringBuilder sb = new StringBuilder(canonical.length());
        for (int i = 0; i < canonical.length(); i++) {
            char c = canonical.charAt(i);
            if (isWidthInsensitive(c)) {
                // ② 寬度折疊只針對實測確認會衝突的三段：
                //    U+3000（全形空白）、U+FF01–U+FF5E（全形 ASCII）、
                //    U+FF61–U+FF9F（半形片假名）。逐字 NFKC 剛好等於
                //    這個定序的寬度不敏感行為，也不會順帶折掉 ① 這種相容字。
                String folded = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFKC);
                sb.append(folded);
            } else {
                sb.append(c);
            }
        }
        // ③ 忽略尾端 U+0020（ANSI padding）。只認 U+0020：
        //    實測 TAB、LF、NBSP 都不等於空白，String.trim() 會誤擋它們。
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == ' ') end--;
        // ④ 不分大小寫。Locale.ROOT 避免土耳其語的 dotted/dotless I 規則
        //    （JDK 預設 locale 若是 tr，"i".toUpperCase() 會變成 "İ"）。
        return sb.substring(0, end).toUpperCase(Locale.ROOT);
    }

    /** 寬度不敏感（width-insensitive）確實成立的字元範圍。 */
    private static boolean isWidthInsensitive(char c) {
        return c == '　'                    // 全形空白
                || (c >= '！' && c <= '～')   // 全形 ASCII
                || (c >= '｡' && c <= 'ﾟ');   // 半形片假名 / 半形標點
    }

    /**
     * 修改單一變數規格。
     *
     * <h2>⚠️ 路徑的 {@code {key}} 必須與資料列一致（#85）</h2>
     *
     * <p>改動前 {@code findById(id)} 之後完全沒有比對
     * {@code existing.getProcessDefinitionKey()}，而路徑上的 {@code {key}}
     * 只被寫進稽核紀錄。實測（修前）：拿 purchase-approval 的那一筆 id
     * 打在 {@code .../leave-approval/variable-spec/{id}} → 回 <b>200</b>、
     * purchase-approval 的規格真的被改掉（{@code required} 由 true 變 false），
     * 而稽核紀錄寫的是 {@code processDefinitionKey = "leave-approval"}。
     *
     * <p>危害不是「改到別人的資料」—— 端點限 ADMIN，攻擊面很小。
     * 危害是<b>稽核紀錄會說謊</b>，而稽核是這個專案的核心賣點：
     * 變數規格是外部系統的<b>輸入驗證定義</b>（{@code required} 決定
     * {@code ExternalApiController.validateVariables} 會不會擋），把
     * {@code required} 從 true 改成 false 就是放寬外部系統的輸入驗證，
     * 而稽核上留下的是「有人在流程 A 上改了 amount」——
     * 追查的人會去查流程 A 的設定，而不是去查真正被改的那筆。
     *
     * <h2>為什麼是 404 而不是 403</h2>
     *
     * <p>{@code ProcessAccessGuard} 的既定政策：403 會確認「這個物件存在」，
     * 對可枚舉的 key／id 等於把枚舉管道留著。流程定義 key 是公開的、
     * 規格 id 會出現在稽核與錯誤訊息裡，所以回 404 ——
     * 呼叫端得到「這個資源不存在」而不是「這筆存在但換個流程就不給你」，
     * 那兩個回應合起來就是一個可以拿來枚舉資料存在性的 oracle。
     *
     * <h2>為什麼不靜默忽略 key 不一致（改成「以 id 為準，照樣更新」）</h2>
     *
     * <p>那樣資料庫是對的，但稽核紀錄仍然會寫錯的 key —— 除非把稽核也改成
     * 記 {@code existing} 的 key。而那正是 #66／#71／#72 定下的政策要排除的：
     * <b>明確拒絕冒用，不靜默忽略</b>。理由有兩條：
     *
     * <ol>
     *   <li>呼叫端送來的 key 與它想改的東西對不上，這是<b>呼叫端的 bug</b>
     *       （多半是前端快取了舊的 key）。靜默忽略等於回 200 讓它以為改對了，
     *       而實際改到別的地方 —— 下一次除錯會從錯誤的方向開始。
     *       明確 404 讓它立刻知道 key 對不上。</li>
     *   <li>「稽核寫實際值」必須建立在「資料確實只改該改的那筆」之上。
     *       先拒絕、稽核才可能永遠誠實。</li>
     * </ol>
     *
     * <h2>稽核寫的是實際被改的那筆</h2>
     *
     * <p>檢查通過之後 {@code key} 與 {@code existing.getProcessDefinitionKey()}
     * 必然相等，所以這裡寫哪一個都不會說謊。仍然寫
     * {@code existing.getProcessDefinitionKey()}：稽核的價值來自於
     * <b>記錄來自資料本身、而不是來自呼叫端宣稱的東西</b>。寫
     * {@code existing} 的話，萬一未來有人把檢查改成別的形狀（例如放寬成
     * 「不一致就用 existing 的 key」），稽核紀錄不會跟著一起壞掉 ——
     * 它壞掉的方式會變成「少了一筆紀錄」，那比說謊容易察覺。
     *
     * <p><b>被擋下來時不寫稽核。</b>寫一筆 {@code CONFIG_CHANGE/update}
     * 代表「這個變更發生了」，而實際上什麼都沒發生 —— 那正是這個缺陷本身
     * 在做的事。要留下「有人試圖用錯的 key」是另一種稽核型別，
     * 屬於稽核政策決定，不在 #85 的範圍內（真的需要時應比照
     * {@code ProcessAccessGuard.denyNonParticipant} 的
     * {@code DATA_ACCESS + denied}，走 {@code publishDetached}）。
     *
     * <h2>順帶修掉 id 不存在回 500</h2>
     *
     * <p>原本是 {@code orElseThrow()}（{@code NoSuchElementException} → 500）。
     * 這個端點屬於 {@code /api/admin/**}，屬於「回 404 才誠實」
     * （見 {@code NotifyAdminController.deleteTemplate}）那一類：
     * 呼叫端拿到 500 只會重試，而重試永遠不會成功。
     * 而且不修的話，這個方法會出現「id 不存在 → 500、id 存在但 key 不符 → 404」
     * 這種沒有人能解釋的組合。
     *
     * <h2>⚠️ 改名撞到同一個 key 底下的其他變數 → 原本 500（#87 的相鄰缺陷）</h2>
     *
     * <p>與 {@link #batchSave} 的重複名是<b>同一個資料庫約束</b>：
     * {@code (processDefinitionKey, variableName)} 唯一。實測（修補前）：
     * 同一個流程底下已有 {@code amount} 與 {@code reason}，
     * 把 {@code amount} 改名成 {@code reason} → <b>500</b>、資料不變。
     *
     * <p>之所以一起修：#87 的核心是「呼叫端送出互相衝突的資料時要回 400 而不是 500」，
     * 而這條路徑是同一個控制器裡的同一個約束。留下來的話，
     * 「這個端點的 500 修掉了嗎」同樣是一個無法回答的問題。
     * 比較鍵 {@link #dbComparisonKey} 只有一份，兩處共用。
     *
     * <h2>⚠️ 改名成空白／null 名 → 原本 200 或 500（#87-2／#87-3）</h2>
     *
     * <p>空白名在這條路徑上的危害與 {@link #batchSave} 完全相同（空白名永遠
     * 比對不到外部系統送來的值），所以必須一起擋 —— 只擋 POST 會留下一個
     * 「PUT 可以把變數改名成空白」的繞道，而那個結果與允許建立空白名沒有差別。
     *
     * <p><b>位置：排在 #85 的 404 守衛之後。</b>「這筆資料存不存在、這個
     * {@code key} 是不是它的 key」是<b>守衛</b>，「payload 長什麼樣」才是
     * <b>action 形狀檢查</b>；順序反過來會讓「key 對不上而且名字是空白」回 400
     * 而不是 404，那正是 handoff 第 7.1 節記下來要避免的狀態碼錯位
     * （守衛插在流程中段時，狀態碼差異會被既有測試綁死）。
     * 規則本身與整批路徑共用同一份（{@link #namelessReason}），
     * 所以兩邊不可能對「什麼叫空白」有不同答案。
     */
    @PutMapping("/api/admin/process-definitions/{key}/variable-spec/{id}")
    @Transactional("primaryTransactionManager")
    public ProcessVariableSpec update(@PathVariable String key, @PathVariable String id,
                                       @RequestBody ProcessVariableSpec spec,
                                       @CallerId String operatorId) {
        ProcessVariableSpec existing = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!key.equals(existing.getProcessDefinitionKey())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        // 空白／null 的判定（#87-2／#87-3）必須排在撞名檢查之前：
        // 「第 1 列是空白」是比「這一列撞了別人」更基本、更該先講的問題。
        requireUsableVariableName(spec.getVariableName());
        requireNameFreeAmongSiblings(existing, spec.getVariableName());
        String before = "%s:%s required=%s".formatted(
                existing.getVariableName(), existing.getVariableType(), existing.getRequired());
        existing.setVariableName(spec.getVariableName());
        existing.setVariableType(spec.getVariableType());
        existing.setRequired(spec.getRequired());
        existing.setDescription(spec.getDescription());
        existing.setExample(spec.getExample());
        ProcessVariableSpec saved = repo.save(existing);

        auditor.record(operatorId, "process-variable-spec", "update", id, java.util.Map.of(
                "processDefinitionKey", existing.getProcessDefinitionKey(),
                "before", before,
                "after", "%s:%s required=%s".formatted(
                        saved.getVariableName(), saved.getVariableType(), saved.getRequired())));
        return saved;
    }

    /**
     * 改名後不得與同一個流程底下的其他變數撞名（#87 的相鄰缺陷）。
     *
     * <p>與 {@link #requireUsableVariableNames} 共用同一個比較鍵，
     * 理由是<b>規則只能有一份</b>（見 {@code NotifyAdminController} 的類別註解）。
     *
     * <p><b>排除自己</b>：呼叫端送回與現況相同的名字（只改型別或說明）
     * 是這個頁面最常見的操作之一，拿它跟自己比會擋掉正常功能。
     *
     * <p><b>{@code newName} 不會是 null 或空白</b>：呼叫端在
     * {@link #requireUsableVariableName} 就擋掉了，所以這裡不需要
     * （也不應該）再有一份 null 判斷 —— 同一條規則有兩套形狀正是本專案
     * 反覆出現的缺陷成因。
     *
     * <p><b>被擋下時不寫稽核</b>，理由與 {@link #update} 的 404 相同：
     * 一筆 {@code CONFIG_CHANGE/update} 代表「這個變更發生了」。
     */
    private void requireNameFreeAmongSiblings(ProcessVariableSpec existing, String newName) {
        String wanted = dbComparisonKey(newName);
        for (ProcessVariableSpec sibling : repo.findByProcessDefinitionKeyOrderByVariableName(
                existing.getProcessDefinitionKey())) {
            if (sibling.getId().equals(existing.getId())) continue;   // 不是自己
            // 既有資料不可能是 null（欄位是 NOT NULL），這裡只是避免 NPE：
            // 比較的是**資料庫裡的舊值**，規則與「送進來的 payload」無關。
            if (sibling.getVariableName() == null) continue;
            if (!wanted.equals(dbComparisonKey(sibling.getVariableName()))) continue;
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "變數名稱已存在: " + newName + "（同一個流程底下已有 " + sibling.getVariableName()
                            + "）。請改成別的名稱。");
        }
    }

    /**
     * External API（由 ExternalApiAuthFilter 驗證 API Key）。
     *
     * <p>⚠️ 改動前這裡只驗 API Key、不比對 allowedProcessKeys —— 任何持
     * {@code query_status} 權限的外部系統都能枚舉<b>全部</b>流程定義的變數規格
     * （變數名、型別、是否必填、說明、範例）。spec §9.1.3 步驟 6 明確要求
     * 必須檢查 allowedProcessKeys 是否包含目標流程。
     */
    @GetMapping("/api/external/process-definitions/{key}/variable-spec")
    public List<ProcessVariableSpec> getSpec(@PathVariable String key,
                                              @RequestAttribute("externalSystem") ExternalSystem sys) {
        if (!policy.isProcessKeyAllowed(sys, key)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "此系統未被授權存取流程: " + key);
        }
        return repo.findByProcessDefinitionKeyOrderByVariableName(key);
    }
}
