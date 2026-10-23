package com.bpm.core.uniform;

import java.util.List;

/** 本次登入者的部門成員；實作不得回傳其他組織或原始人事資料。 */
@FunctionalInterface
public interface DepartmentMembersSource {
    int MAX_MEMBERS = 500;

    List<Member> findMembers(String caller);

    record Member(String id, String name) {}
}
