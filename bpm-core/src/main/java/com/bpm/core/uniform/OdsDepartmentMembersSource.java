package com.bpm.core.uniform;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/** 僅讀 MASA 2.x ODS 投影；ParentOrgCode 精確相等，不包含子組織。 */
public final class OdsDepartmentMembersSource implements DepartmentMembersSource, AutoCloseable {
    private final HikariDataSource dataSource;
    private final JdbcTemplate jdbc;
    private final String sql;

    public OdsDepartmentMembersSource(HikariDataSource dataSource, String principalIdType) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
        this.jdbc.setQueryTimeout(3);
        // 表名／欄名僅來自固定白名單，caller 一律使用 JDBC 參數。
        String table;
        String id;
        switch (principalIdType) {
            case "staff-user-id" -> { table = "StaffEmployee"; id = "UserId"; }
            case "agent-code" -> { table = "AgentEmployee"; id = "AgCode"; }
            default -> throw new IllegalArgumentException("不支援的 ODS principal 命名空間");
        }
        // 同一 statement 決定 caller 部門與成員；多讀一筆以辨識超過上限。
        // LEFT JOIN 保留 caller，避免查無 caller／無部門被誤當作合法空清單。
        this.sql = "SELECT TOP (" + (MAX_MEMBERS + 1) + ") "
                + "caller." + id + " AS caller_id, caller.ParentOrgCode AS caller_department, "
                + "member." + id + " AS member_id, member.UserName AS member_name, "
                + "member.ParentOrgCode AS member_department "
                + "FROM dbo." + table + " caller LEFT JOIN dbo." + table + " member "
                + "ON member.ParentOrgCode = caller.ParentOrgCode AND "
                + exact("member.ParentOrgCode", "caller.ParentOrgCode")
                + " WHERE caller." + id + " = ? AND " + exact("caller." + id, "?")
                + " ORDER BY member." + id;
    }

    @Override
    public List<Member> findMembers(String caller) {
        List<Row> rows = jdbc.query(sql, (rs, row) -> new Row(rs.getString("caller_id"),
                rs.getString("caller_department"), rs.getString("member_id"),
                rs.getString("member_name"), rs.getString("member_department")), caller, caller, caller);
        if (rows.isEmpty() || rows.size() > MAX_MEMBERS) throw new IllegalStateException();
        String department = rows.getFirst().department();
        for (Row row : rows) {
            // SQL 的定序或尾端空白規則不得擴大授權；Java 再檢查原始字串。
            if (!caller.equals(row.callerId()) || department == null || department.isBlank()
                    || !department.equals(row.department())
                    || row.id() != null && !department.equals(row.memberDepartment())) {
                throw new IllegalStateException();
            }
        }
        if (rows.size() == 1 && rows.getFirst().id() == null) return List.of();
        return rows.stream().map(row -> new Member(row.id(), row.name())).toList();
    }

    /** BIN2 區分大小寫／字元；長度條件另排除 SQL Server 的尾端空白 padding。
     * 兩側先轉 NVARCHAR，避免 ODS VARCHAR 與 JDBC Unicode 參數的 byte 長度不同。
     */
    private static String exact(String left, String right) {
        String lhs = "CONVERT(nvarchar(max), " + left + ")";
        String rhs = "CONVERT(nvarchar(max), " + right + ")";
        return lhs + " COLLATE Latin1_General_100_BIN2 = " + rhs
                + " COLLATE Latin1_General_100_BIN2 AND DATALENGTH(" + lhs + ") = DATALENGTH(" + rhs + ")";
    }

    @Override
    public void close() { dataSource.close(); }

    private record Row(String callerId, String department, String id, String name, String memberDepartment) {}
}
