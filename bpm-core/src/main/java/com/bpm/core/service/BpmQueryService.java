package com.bpm.core.service;

import org.springframework.stereotype.Service;

import java.util.List;

@Service("bpmQueryService")
public class BpmQueryService {

    private final OrgService orgService;
    private final BpmPermissionService permService;

    public BpmQueryService(OrgService orgService, BpmPermissionService permService) {
        this.orgService = orgService;
        this.permService = permService;
    }

    /**
     * Find the direct manager of userId who also has the given permission.
     * Walks up the manager chain until a match is found.
     */
    public String getManagerWithPermission(String userId, String permCode) {
        List<String> chain = orgService.getManagerChain(userId, 5);
        if (chain == null) return null;
        return chain.stream()
                .filter(mgr -> permService.hasPermission(mgr, permCode))
                .findFirst().orElse(null);
    }

    /**
     * Find users in the same department as userId who have the given permission and are available.
     */
    public List<String> getDeptUsersWithPermission(String userId, String permCode) {
        String deptId = orgService.getDeptId(userId);
        if (deptId == null) return List.of();
        List<String> users = permService.getUsersByPermissionAndDept(permCode, deptId);
        if (users == null) return List.of();
        return users.stream()
                .filter(orgService::isUserAvailable)
                .toList();
    }
}
