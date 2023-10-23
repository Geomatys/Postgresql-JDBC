/*
 * Copyright (c) 2023, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

/**
 * JDNC driver for PostgreSQL.
 */
module org.postgresql.jdbc {
    requires transitive java.sql;
    requires transitive java.naming;

    requires java.management;
    requires java.security.jgss;                    // TODO: make transitive if org.postgresql.gss is exported
    requires org.checkerframework.checker.qual;

    exports org.postgresql;
    exports org.postgresql.ds;
    exports org.postgresql.core;
    exports org.postgresql.util;
    // TODO: what else to export?

    provides java.sql.Driver with org.postgresql.Driver;
}
