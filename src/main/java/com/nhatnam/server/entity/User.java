package com.nhatnam.server.entity;

import com.nhatnam.server.enumtype.Role;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.*;
import java.util.stream.Collectors;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "_user")
public class User implements UserDetails {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public long id;

  private String username;
  private String fullName;
  private String password;
  private String email;
  private String phoneNumber;
  private long timeCreate;
  private boolean isLockAccount;

  private boolean mfaEnabled;
  private String secret;

  /** Bộ phận / phòng ban (VD: Kinh doanh, Kế toán, …) */
  private String department;

  /** Chức vụ / vị trí công việc */
  private String position;

  @Enumerated(EnumType.STRING)
  private Role role;

  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(name = "_user_roles", joinColumns = @JoinColumn(name = "user_id"))
  @Enumerated(EnumType.STRING)
  @Column(name = "role")
  @Builder.Default
  private Set<Role> roles = new HashSet<>();

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "warehouse_id")
  private Warehouse warehouse;

  @ManyToMany(fetch = FetchType.EAGER)
  @JoinTable(
    name = "_user_warehouses",
    joinColumns = @JoinColumn(name = "user_id"),
    inverseJoinColumns = @JoinColumn(name = "warehouse_id")
  )
  @Builder.Default
  private Set<Warehouse> userWarehouses = new HashSet<>();

  public Set<Warehouse> getAllWarehouses() {
    if (userWarehouses != null && !userWarehouses.isEmpty()) return userWarehouses;
    if (warehouse != null) return new HashSet<>(Set.of(warehouse));
    return new HashSet<>();
  }

  @OneToMany(mappedBy = "user")
  private List<Token> tokens;

  public Set<Role> getAllRoles() {
    Set<Role> all = new HashSet<>(roles != null ? roles : Collections.emptySet());
    if (role != null) all.add(role);
    return all;
  }

  public boolean hasMultipleRoles() {
    return getAllRoles().size() > 1;
  }

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    return role != null ? role.getAuthorities() : Collections.emptyList();
  }

  @Override public String getPassword() { return password; }
  @Override public String getUsername() { return username; }
  @Override public boolean isAccountNonExpired() { return true; }
  @Override public boolean isAccountNonLocked() { return true; }
  @Override public boolean isCredentialsNonExpired() { return true; }
  @Override public boolean isEnabled() { return true; }
}
