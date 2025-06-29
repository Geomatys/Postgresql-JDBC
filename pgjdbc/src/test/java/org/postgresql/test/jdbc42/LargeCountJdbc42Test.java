/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc42;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Properties;

/**
 * Test methods with small counts that return long and failure scenarios. This have two really big
 * and slow test, they are ignored for CI but can be tested locally to check that it works.
 */
@ParameterizedClass
@MethodSource("data")
public class LargeCountJdbc42Test extends BaseTest4 {

  private final boolean insertRewrite;

  public LargeCountJdbc42Test(BinaryMode binaryMode, boolean insertRewrite) {
    this.insertRewrite = insertRewrite;
    setBinaryMode(binaryMode);
  }

  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<>();
    for (BinaryMode binaryMode : BinaryMode.values()) {
      for (boolean insertRewrite : new boolean[]{false, true}) {
        ids.add(new Object[]{binaryMode, insertRewrite});
      }
    }
    return ids;
  }

  @Override
  protected void updateProperties(Properties props) {
    super.updateProperties(props);
    PGProperty.REWRITE_BATCHED_INSERTS.set(props, insertRewrite);
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    TestUtil.createUnloggedTable(con, "largetable", "a boolean");
  }

  @Override
  public void tearDown() throws SQLException {
    TestUtil.dropTable(con, "largetable");
    TestUtil.closeDB(con);
  }

  // ********************* EXECUTE LARGE UPDATES *********************
  //    FINEST: simple execute, handler=org.postgresql.jdbc.PgStatement$StatementResultHandler@38cccef, maxRows=0, fetchSize=0, flags=21
  //    FINEST: FE=> Parse(stmt=null,query="insert into largetable select true from generate_series($1, $2)",oids={20,20})
  //    FINEST: FE=> Bind(stmt=null,portal=null,$1=<1>,$2=<2147483757>)
  //    FINEST: FE=> Describe(portal=null)
  //    FINEST: FE=> Execute(portal=null,limit=1)
  //    FINEST: FE=> Sync
  //    FINEST: <=BE ParseComplete [null]
  //    FINEST: <=BE BindComplete [unnamed]
  //    FINEST: <=BE NoData
  //    FINEST: <=BE CommandStatus(INSERT 0 2147483757)
  //    FINEST: <=BE ReadyForQuery(I)
  //    FINEST: simple execute, handler=org.postgresql.jdbc.PgStatement$StatementResultHandler@5679c6c6, maxRows=0, fetchSize=0, flags=21
  //    FINEST: FE=> Parse(stmt=null,query="delete from largetable",oids={})
  //    FINEST: FE=> Bind(stmt=null,portal=null)
  //    FINEST: FE=> Describe(portal=null)
  //    FINEST: FE=> Execute(portal=null,limit=1)
  //    FINEST: FE=> Sync
  //    FINEST: <=BE ParseComplete [null]
  //    FINEST: <=BE BindComplete [unnamed]
  //    FINEST: <=BE NoData
  //    FINEST: <=BE CommandStatus(DELETE 2147483757)

  /*
   * Test PreparedStatement.executeLargeUpdate() and Statement.executeLargeUpdate(String sql)
   */
  @Disabled("This is the big and SLOW test")
  @Test
  public void testExecuteLargeUpdateBIG() throws Exception {
    long expected = Integer.MAX_VALUE + 110L;
    con.setAutoCommit(false);
    // Test PreparedStatement.executeLargeUpdate()
    try (PreparedStatement stmt = con.prepareStatement("insert into largetable "
        + "select true from generate_series(?, ?)")) {
      stmt.setLong(1, 1);
      stmt.setLong(2, 2_147_483_757L); // Integer.MAX_VALUE + 110L
      long count = stmt.executeLargeUpdate();
      assertEquals(expected, count, "PreparedStatement 110 rows more than Integer.MAX_VALUE");
    }
    // Test Statement.executeLargeUpdate(String sql)
    try (Statement stmt = con.createStatement()) {
      long count = stmt.executeLargeUpdate("delete from largetable");
      assertEquals(expected, count, "Statement 110 rows more than Integer.MAX_VALUE");
    }
    con.setAutoCommit(true);
  }

  /*
   * Test Statement.executeLargeUpdate(String sql)
   */
  @Test
  public void testExecuteLargeUpdateStatementSMALL() throws Exception {
    try (Statement stmt = con.createStatement()) {
      long count = stmt.executeLargeUpdate("insert into largetable "
          + "select true from generate_series(1, 1010)");
      long expected = 1010L;
      assertEquals(expected, count, "Small long return 1010L");
    }
  }

  /*
   * Test PreparedStatement.executeLargeUpdate();
   */
  @Test
  public void testExecuteLargeUpdatePreparedStatementSMALL() throws Exception {
    try (PreparedStatement stmt = con.prepareStatement("insert into largetable "
        + "select true from generate_series(?, ?)")) {
      stmt.setLong(1, 1);
      stmt.setLong(2, 1010L);
      long count = stmt.executeLargeUpdate();
      long expected = 1010L;
      assertEquals(expected, count, "Small long return 1010L");
    }
  }

  /*
   * Test Statement.getLargeUpdateCount();
   */
  @Test
  public void testGetLargeUpdateCountStatementSMALL() throws Exception {
    try (Statement stmt = con.createStatement()) {
      boolean isResult = stmt.execute("insert into largetable "
          + "select true from generate_series(1, 1010)");
      assertFalse(isResult, "False if it is an update count or there are no results");
      long count = stmt.getLargeUpdateCount();
      long expected = 1010L;
      assertEquals(expected, count, "Small long return 1010L");
    }
  }

  /*
   * Test PreparedStatement.getLargeUpdateCount();
   */
  @Test
  public void testGetLargeUpdateCountPreparedStatementSMALL() throws Exception {
    try (PreparedStatement stmt = con.prepareStatement("insert into largetable "
        + "select true from generate_series(?, ?)")) {
      stmt.setInt(1, 1);
      stmt.setInt(2, 1010);
      boolean isResult = stmt.execute();
      assertFalse(isResult, "False if it is an update count or there are no results");
      long count = stmt.getLargeUpdateCount();
      long expected = 1010L;
      assertEquals(expected, count, "Small long return 1010L");
    }
  }

  /*
   * Test fail SELECT Statement.executeLargeUpdate(String sql)
   */
  @Test
  public void testExecuteLargeUpdateStatementSELECT() throws Exception {
    try (Statement stmt = con.createStatement()) {
      long count = stmt.executeLargeUpdate("select true from generate_series(1, 5)");
      fail("A result was returned when none was expected. Returned: " + count);
    } catch (SQLException e) {
      assertEquals(PSQLState.TOO_MANY_RESULTS.getState(), e.getSQLState());
    }
  }

  /*
   * Test fail SELECT PreparedStatement.executeLargeUpdate();
   */
  @Test
  public void testExecuteLargeUpdatePreparedStatementSELECT() throws Exception {
    try (PreparedStatement stmt = con.prepareStatement("select true from generate_series(?, ?)")) {
      stmt.setLong(1, 1);
      stmt.setLong(2, 5L);
      long count = stmt.executeLargeUpdate();
      fail("A result was returned when none was expected. Returned: " + count);
    } catch (SQLException e) {
      assertEquals(PSQLState.TOO_MANY_RESULTS.getState(), e.getSQLState());
    }
  }

  /*
   * Test Statement.getLargeUpdateCount();
   */
  @Test
  public void testGetLargeUpdateCountStatementSELECT() throws Exception {
    try (Statement stmt = con.createStatement()) {
      boolean isResult = stmt.execute("select true from generate_series(1, 5)");
      assertTrue(isResult, "True since this is a SELECT");
      long count = stmt.getLargeUpdateCount();
      long expected = -1L;
      assertEquals(expected, count, "-1 if the current result is a ResultSet object");
    }
  }

  /*
   * Test PreparedStatement.getLargeUpdateCount();
   */
  @Test
  public void testGetLargeUpdateCountPreparedStatementSELECT() throws Exception {
    try (PreparedStatement stmt = con.prepareStatement("select true from generate_series(?, ?)")) {
      stmt.setLong(1, 1);
      stmt.setLong(2, 5L);
      boolean isResult = stmt.execute();
      assertTrue(isResult, "True since this is a SELECT");
      long count = stmt.getLargeUpdateCount();
      long expected = -1L;
      assertEquals(expected, count, "-1 if the current result is a ResultSet object");
    }
  }

  // ********************* BATCH LARGE UPDATES *********************
  //    FINEST: batch execute 3 queries, handler=org.postgresql.jdbc.BatchResultHandler@3d04a311, maxRows=0, fetchSize=0, flags=21
  //    FINEST: FE=> Parse(stmt=null,query="insert into largetable select true from generate_series($1, $2)",oids={23,23})
  //    FINEST: FE=> Bind(stmt=null,portal=null,$1=<1>,$2=<200>)
  //    FINEST: FE=> Describe(portal=null)
  //    FINEST: FE=> Execute(portal=null,limit=1)
  //    FINEST: FE=> Parse(stmt=null,query="insert into largetable select true from generate_series($1, $2)",oids={23,20})
  //    FINEST: FE=> Bind(stmt=null,portal=null,$1=<1>,$2=<3000000000>)
  //    FINEST: FE=> Describe(portal=null)
  //    FINEST: FE=> Execute(portal=null,limit=1)
  //    FINEST: FE=> Parse(stmt=null,query="insert into largetable select true from generate_series($1, $2)",oids={23,23})
  //    FINEST: FE=> Bind(stmt=null,portal=null,$1=<1>,$2=<50>)
  //    FINEST: FE=> Describe(portal=null)
  //    FINEST: FE=> Execute(portal=null,limit=1)
  //    FINEST: FE=> Sync
  //    FINEST: <=BE ParseComplete [null]
  //    FINEST: <=BE BindComplete [unnamed]
  //    FINEST: <=BE NoData
  //    FINEST: <=BE CommandStatus(INSERT 0 200)
  //    FINEST: <=BE ParseComplete [null]
  //    FINEST: <=BE BindComplete [unnamed]
  //    FINEST: <=BE NoData
  //    FINEST: <=BE CommandStatus(INSERT 0 3000000000)
  //    FINEST: <=BE ParseComplete [null]
  //    FINEST: <=BE BindComplete [unnamed]
  //    FINEST: <=BE NoData
  //    FINEST: <=BE CommandStatus(INSERT 0 50)

  /*
   * Test simple PreparedStatement.executeLargeBatch();
   */
  @Disabled("This is the big and SLOW test")
  @Test
  public void testExecuteLargeBatchStatementBIG() throws Exception {
    con.setAutoCommit(false);
    try (PreparedStatement stmt = con.prepareStatement("insert into largetable "
        + "select true from generate_series(?, ?)")) {
      stmt.setInt(1, 1);
      stmt.setInt(2, 200);
      stmt.addBatch(); // statement one
      stmt.setInt(1, 1);
      stmt.setLong(2, 3_000_000_000L);
      stmt.addBatch(); // statement two
      stmt.setInt(1, 1);
      stmt.setInt(2, 50);
      stmt.addBatch(); // statement three
      long[] actual = stmt.executeLargeBatch();
      assertArrayEquals(
          new long[]{200L, 3_000_000_000L, 50L},
          actual,
          "Large rows inserted via 3 batch");
    }
    con.setAutoCommit(true);
  }

  /*
   * Test simple Statement.executeLargeBatch();
   */
  @Test
  public void testExecuteLargeBatchStatementSMALL() throws Exception {
    try (Statement stmt = con.createStatement()) {
      stmt.addBatch("insert into largetable(a) select true"); // statement one
      stmt.addBatch("insert into largetable select false"); // statement two
      stmt.addBatch("insert into largetable(a) values(true)"); // statement three
      stmt.addBatch("insert into largetable values(false)"); // statement four
      long[] actual = stmt.executeLargeBatch();
      assertArrayEquals(new long[]{1L, 1L, 1L, 1L}, actual, "Rows inserted via 4 batch");
    }
  }

  /*
   * Test simple PreparedStatement.executeLargeBatch();
   */
  @Test
  public void testExecuteLargePreparedStatementStatementSMALL() throws Exception {
    try (PreparedStatement stmt = con.prepareStatement("insert into largetable "
        + "select true from generate_series(?, ?)")) {
      stmt.setInt(1, 1);
      stmt.setInt(2, 200);
      stmt.addBatch(); // statement one
      stmt.setInt(1, 1);
      stmt.setInt(2, 100);
      stmt.addBatch(); // statement two
      stmt.setInt(1, 1);
      stmt.setInt(2, 50);
      stmt.addBatch(); // statement three
      stmt.addBatch(); // statement four, same parms as three
      long[] actual = stmt.executeLargeBatch();
      assertArrayEquals(new long[]{200L, 100L, 50L, 50L}, actual, "Rows inserted via 4 batch");
    }
  }

  /*
   * Test loop PreparedStatement.executeLargeBatch();
   */
  @Test
  public void testExecuteLargePreparedStatementStatementLoopSMALL() throws Exception {
    long[] loop = {200, 100, 50, 300, 20, 60, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024, 2048, 4096};
    try (PreparedStatement stmt = con.prepareStatement("insert into largetable "
        + "select true from generate_series(?, ?)")) {
      for (long i : loop) {
        stmt.setInt(1, 1);
        stmt.setLong(2, i);
        stmt.addBatch();
      }
      long[] actual = stmt.executeLargeBatch();
      assertArrayEquals(loop, actual, "Rows inserted via batch");
    }
  }

  /*
   * Test loop PreparedStatement.executeLargeBatch();
   */
  @Test
  public void testExecuteLargeBatchValuesInsertSMALL() throws Exception {
    boolean[] loop = {true, false, true, false, false, false, true, true, true, true, false, true};
    try (PreparedStatement stmt = con.prepareStatement("insert into largetable values(?)")) {
      for (boolean i : loop) {
        stmt.setBoolean(1, i);
        stmt.addBatch();
      }
      long[] actual = stmt.executeLargeBatch();
      assertEquals(loop.length, actual.length, "Rows inserted via batch");
      for (long i : actual) {
        if (insertRewrite) {
          assertEquals(Statement.SUCCESS_NO_INFO, i);
        } else {
          assertEquals(1, i);
        }
      }
    }
  }

  /*
   * Test null PreparedStatement.executeLargeBatch();
   */
  @Test
  public void testNullExecuteLargeBatchStatement() throws Exception {
    try (Statement stmt = con.createStatement()) {
      long[] actual = stmt.executeLargeBatch();
      assertArrayEquals(new long[0], actual, "addBatch() not called batchStatements is null");
    }
  }

  /*
   * Test empty PreparedStatement.executeLargeBatch();
   */
  @Test
  public void testEmptyExecuteLargeBatchStatement() throws Exception {
    try (Statement stmt = con.createStatement()) {
      stmt.addBatch("");
      stmt.clearBatch();
      long[] actual = stmt.executeLargeBatch();
      assertArrayEquals(new long[0], actual, "clearBatch() called, batchStatements.isEmpty()");
    }
  }

  /*
   * Test null PreparedStatement.executeLargeBatch();
   */
  @Test
  public void testNullExecuteLargeBatchPreparedStatement() throws Exception {
    try (PreparedStatement stmt = con.prepareStatement("")) {
      long[] actual = stmt.executeLargeBatch();
      assertArrayEquals(new long[0], actual, "addBatch() not called batchStatements is null");
    }
  }

  /*
   * Test empty PreparedStatement.executeLargeBatch();
   */
  @Test
  public void testEmptyExecuteLargeBatchPreparedStatement() throws Exception {
    try (PreparedStatement stmt = con.prepareStatement("")) {
      stmt.addBatch();
      stmt.clearBatch();
      long[] actual = stmt.executeLargeBatch();
      assertArrayEquals(new long[0], actual, "clearBatch() called, batchStatements.isEmpty()");
    }
  }

}
