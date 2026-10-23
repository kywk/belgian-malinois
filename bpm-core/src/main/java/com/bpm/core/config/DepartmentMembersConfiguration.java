package com.bpm.core.config;

import com.bpm.core.client.OrgRestClient;
import com.bpm.core.uniform.DepartmentMembersSource;
import com.bpm.core.uniform.OdsDepartmentMembersSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.util.ArrayList;
import java.util.List;

/** Options 專用來源，不影響主管／代理查詢，也不註冊或取用應用 DataSource。 */
@Configuration(proxyBeanMethods = false)
public class DepartmentMembersConfiguration {
    private static final String PREFIX = "bpm.uniform.options.";

    @Bean
    DepartmentMembersSource departmentMembersSource(Environment environment, OrgRestClient org) {
        String source = environment.getProperty(PREFIX + "source", "disabled");
        return switch (source) {
            case "disabled" -> caller -> { throw new IllegalStateException(); };
            case "mock" -> {
                if (environment.acceptsProfiles(Profiles.of("prod"))
                        || !environment.getProperty("bpm.external.mock-enabled", Boolean.class, false)) {
                    throw new IllegalStateException("Options mock 只允許啟用組織 fixture 的開發環境");
                }
                yield caller -> mockMembers(org, caller);
            }
            case "ods" -> ods(environment);
            default -> throw new IllegalStateException("Options 來源設定不合法");
        };
    }

    private static DepartmentMembersSource ods(Environment environment) {
        String principalIdType = required(environment, "ods.principal-id-type");
        if (!List.of("staff-user-id", "agent-code").contains(principalIdType)) {
            throw new IllegalStateException("Options ODS principal 命名空間設定不合法");
        }
        HikariConfig pool = new HikariConfig();
        pool.setJdbcUrl(required(environment, "ods.url"));
        pool.setUsername(required(environment, "ods.username"));
        // 專用密碼為原始 secret，不套用 MASA DataSourceFactory 的 Base64 猜測解碼。
        pool.setPassword(required(environment, "ods.password"));
        pool.setDriverClassName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
        pool.setPoolName("bpm-options-ods-pool");
        pool.setMaximumPoolSize(3);
        pool.setMinimumIdle(0);
        pool.setReadOnly(true);
        pool.setConnectionTimeout(3000);
        pool.setValidationTimeout(2000);
        // 延後第一次連線至查詢，來源不可用由 Registry 統一回 503。
        pool.setInitializationFailTimeout(-1);
        pool.addDataSourceProperty("loginTimeout", "3");
        pool.addDataSourceProperty("socketTimeout", "5000");
        return new OdsDepartmentMembersSource(new HikariDataSource(pool), principalIdType);
    }

    private static String required(Environment environment, String key) {
        String value = environment.getProperty(PREFIX + key);
        if (value == null || value.isBlank()) {
            // 錯誤只包含組態名稱，不帶值、帳密或連線字串。
            throw new IllegalStateException("Options ODS 缺少必要組態：" + PREFIX + key);
        }
        return value;
    }

    private static List<DepartmentMembersSource.Member> mockMembers(OrgRestClient org, String caller) {
        String department = org.getDepartment(caller);
        if (department == null || department.isBlank()) throw new IllegalStateException();
        List<String> members = org.getDeptMembers(department);
        if (members == null || members.size() > DepartmentMembersSource.MAX_MEMBERS) throw new IllegalStateException();
        var result = new ArrayList<DepartmentMembersSource.Member>();
        for (String id : members) {
            if (id == null || id.isBlank()) throw new IllegalStateException();
            var user = org.getUser(id);
            if (user == null || !id.equals(user.get("userId")) || !(user.get("name") instanceof String name)) {
                throw new IllegalStateException();
            }
            result.add(new DepartmentMembersSource.Member(id, name));
        }
        return result;
    }
}
