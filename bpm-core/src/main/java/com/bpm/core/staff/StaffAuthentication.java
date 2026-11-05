package com.bpm.core.staff;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import java.time.Instant;
import java.util.List;

public class StaffAuthentication extends JwtAuthenticationToken {
    private final StaffIdentitySnapshot staff;
    private final List<String> permissions;
    private final Instant checkedAt;
    public StaffAuthentication(Jwt jwt, StaffIdentitySnapshot staff, List<String> permissions) {
        super(jwt, permissions.stream().flatMap(code -> java.util.stream.Stream.of(
                code, StaffPermissions.ALIASES.getOrDefault(code, code))).distinct()
                .map(SimpleGrantedAuthority::new).toList(), staff.userId());
        this.staff = staff;
        this.permissions = List.copyOf(permissions);
        this.checkedAt = Instant.now();
    }
    public StaffIdentitySnapshot staff() { return staff; }
    public List<String> permissions() { return permissions; }
    public Instant checkedAt() { return checkedAt; }
    public static StaffAuthentication current() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return auth instanceof StaffAuthentication staff ? staff : null;
    }
}
