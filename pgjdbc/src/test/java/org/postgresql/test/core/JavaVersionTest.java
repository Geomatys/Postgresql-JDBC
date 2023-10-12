/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.core;

import org.postgresql.core.JavaVersion;

import org.junit.Test;

public class JavaVersionTest {
  @Test
  public void testGetRuntimeVersion() {
    String currentVersion = System.getProperty("java.version");
    String msg = "java.version = " + currentVersion + ", JavaVersion.getRuntimeVersion() = "
        + JavaVersion.getRuntimeVersion();
    System.out.println(msg);
  }
}
