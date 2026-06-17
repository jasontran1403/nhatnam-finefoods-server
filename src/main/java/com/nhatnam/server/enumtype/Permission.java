package com.nhatnam.server.enumtype;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum Permission {

    SUPERADMIN_READ("admin:read"),
    SUPERADMIN_UPDATE("admin:update"),
    SUPERADMIN_CREATE("admin:create"),
    SUPERADMIN_DELETE("admin:delete"),

    ADMIN_READ("admin:read"),
    ADMIN_UPDATE("admin:update"),
    ADMIN_CREATE("admin:create"),
    ADMIN_DELETE("admin:delete"),

    ACCOUNTANT_READ("accountant:read"),
    ACCOUNTANT_UPDATE("accountant:update"),
    ACCOUNTANT_CREATE("accountant:create"),
    ACCOUNTANT_DELETE("accountant:delete"),

    SELLER_READ("seller:read"),
    SELLER_UPDATE("seller:update"),
    SELLER_CREATE("seller:create"),
    SELLER_DELETE("seller:delete"),

    WAREHOUSE_READ("warehouse:read"),
    WAREHOUSE_UPDATE("warehouse:update"),
    WAREHOUSE_CREATE("warehouse:create"),
    WAREHOUSE_DELETE("warehouse:delete"),

    SHIPPER_READ("shipper:read"),
    SHIPPER_UPDATE("shipper:update"),
    SHIPPER_CREATE("shipper:create"),
    SHIPPER_DELETE("shipper:delete"),

    POS_READ("pos:read"),
    POS_UPDATE("pos:update"),
    POS_CREATE("pos:create"),
    POS_DELETE("pos:delete"),

    USER_READ("user:read"),
    USER_UPDATE("user:update"),
    USER_CREATE("user:create"),
    USER_DELETE("user:delete"),

    OPERATOR_READ("operator:read"),
    OPERATOR_UPDATE("operator:update"),
    OPERATOR_CREATE("operator:create"),
    OPERATOR_DELETE("operator:delete"),

    OWNER_READ("owner:read"),
    OWNER_UPDATE("owner:update"),
    OWNER_CREATE("owner:create"),
    OWNER_DELETE("owner:delete"),

    SUPER_SELLER_READ("super_seller:read"),
    SUPER_SELLER_UPDATE("super_seller:update"),
    SUPER_SELLER_CREATE("super_seller:create"),
    SUPER_SELLER_DELETE("super_seller:delete"),

    SUPER_ACCOUNTANT_READ("super_accountant:read"),
    SUPER_ACCOUNTANT_UPDATE("super_accountant:update"),
    SUPER_ACCOUNTANT_CREATE("super_accountant:create"),
    SUPER_ACCOUNTANT_DELETE("super_accountant:delete"),

    SUPER_WAREHOUSE_READ("super_warehouse:read"),
    SUPER_WAREHOUSE_UPDATE("super_warehouse:update"),
    SUPER_WAREHOUSE_CREATE("super_warehouse:create"),
    SUPER_WAREHOUSE_DELETE("super_warehouse:delete"),

    FACTORY_WORKER_READ("factory_worker:read"),
    FACTORY_WORKER_UPDATE("factory_worker:update"),
    FACTORY_WORKER_CREATE("factory_worker:create"),
    FACTORY_WORKER_DELETE("factory_worker:delete"),

    // ── HR (Nhân viên nhân sự) ────────────────────────────────────────
    HR_READ("hr:read"),
    HR_UPDATE("hr:update"),
    HR_CREATE("hr:create"),
    HR_DELETE("hr:delete"),

    ;

    @Getter
    private final String permission;
}
