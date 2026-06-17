package com.nhatnam.server.enumtype;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.nhatnam.server.enumtype.Permission.*;

@RequiredArgsConstructor
public enum Role {

  UN_AUTH(Collections.emptySet()),
  SUPERADMIN(Set.of(SUPERADMIN_READ, SUPERADMIN_UPDATE, SUPERADMIN_DELETE, SUPERADMIN_CREATE)),
  OWNER(Set.of(
          OWNER_READ, OWNER_UPDATE, OWNER_DELETE, OWNER_CREATE,
          ADMIN_READ, ADMIN_UPDATE, ADMIN_DELETE, ADMIN_CREATE,
          HR_READ, HR_UPDATE, HR_CREATE, HR_DELETE
  )),
  ADMIN(Set.of(
          ADMIN_READ, ADMIN_UPDATE, ADMIN_DELETE, ADMIN_CREATE,
          ACCOUNTANT_READ, ACCOUNTANT_UPDATE, ACCOUNTANT_DELETE, ACCOUNTANT_CREATE
  )),
  ACCOUNTANT(Set.of(ACCOUNTANT_READ, ACCOUNTANT_UPDATE, ACCOUNTANT_DELETE, ACCOUNTANT_CREATE)),
  SUPER_ACCOUNTANT(Set.of(
          SUPER_ACCOUNTANT_READ, SUPER_ACCOUNTANT_UPDATE, SUPER_ACCOUNTANT_DELETE, SUPER_ACCOUNTANT_CREATE,
          ACCOUNTANT_READ, ACCOUNTANT_UPDATE, ACCOUNTANT_DELETE, ACCOUNTANT_CREATE
  )),
  WAREHOUSE(Set.of(WAREHOUSE_READ, WAREHOUSE_UPDATE, WAREHOUSE_DELETE, WAREHOUSE_CREATE)),
  SUPER_WAREHOUSE(Set.of(
          SUPER_WAREHOUSE_READ, SUPER_WAREHOUSE_UPDATE, SUPER_WAREHOUSE_DELETE, SUPER_WAREHOUSE_CREATE,
          WAREHOUSE_READ, WAREHOUSE_UPDATE, WAREHOUSE_DELETE, WAREHOUSE_CREATE
  )),
  USER(Set.of(USER_READ, USER_UPDATE, USER_DELETE, USER_CREATE)),
  SHIPPER(Set.of(SHIPPER_READ, SHIPPER_UPDATE, SHIPPER_DELETE, SHIPPER_CREATE)),
  POS(Set.of(POS_READ, POS_UPDATE, POS_DELETE, POS_CREATE)),
  SELLER(Set.of(SELLER_READ, SELLER_UPDATE, SELLER_DELETE, SELLER_CREATE)),
  SUPER_SELLER(Set.of(
          SUPER_SELLER_READ, SUPER_SELLER_UPDATE, SUPER_SELLER_DELETE, SUPER_SELLER_CREATE,
          SELLER_READ, SELLER_UPDATE, SELLER_DELETE, SELLER_CREATE
  )),
  OPERATOR(Set.of(OPERATOR_READ, OPERATOR_UPDATE, OPERATOR_DELETE, OPERATOR_CREATE)),
  FACTORY_WORKER(Set.of(
          FACTORY_WORKER_READ, FACTORY_WORKER_UPDATE,
          FACTORY_WORKER_CREATE, FACTORY_WORKER_DELETE
  )),

  // ── Nhân viên nhân sự ────────────────────────────────────────────────
  HR(Set.of(HR_READ, HR_UPDATE, HR_CREATE, HR_DELETE));

  @Getter
  private final Set<Permission> permissions;

  public List<SimpleGrantedAuthority> getAuthorities() {
    var authorities = getPermissions()
            .stream()
            .map(permission -> new SimpleGrantedAuthority(permission.getPermission()))
            .collect(Collectors.toList());
    authorities.add(new SimpleGrantedAuthority("ROLE_" + this.name()));
    return authorities;
  }
}
