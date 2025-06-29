/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.Driver;
import org.postgresql.PGProperty;
import org.postgresql.core.ServerVersion;
import org.postgresql.jdbc.PgStatement;
import org.postgresql.test.TestUtil;
import org.postgresql.test.util.StrangeProxyServer;
import org.postgresql.util.LazyCleaner;
import org.postgresql.util.PSQLState;
import org.postgresql.util.SharedTimer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class StatementTest {
  private Connection con;

  @BeforeEach
  void setUp() throws Exception {
    con = TestUtil.openDB();
    TestUtil.createTempTable(con, "test_statement", "i int");
    TestUtil.createTempTable(con, "escapetest",
        "ts timestamp, d date, t time, \")\" varchar(5), \"\"\"){a}'\" text ");
    TestUtil.createTempTable(con, "comparisontest", "str1 varchar(5), str2 varchar(15)");
    TestUtil.createTable(con, "test_lock", "name text");
    Statement stmt = con.createStatement();
    stmt.executeUpdate(TestUtil.insertSQL("comparisontest", "str1,str2", "'_abcd','_found'"));
    stmt.executeUpdate(TestUtil.insertSQL("comparisontest", "str1,str2", "'%abcd','%found'"));
    stmt.close();
  }

  @AfterEach
  void tearDown() throws Exception {
    TestUtil.dropTable(con, "test_statement");
    TestUtil.dropTable(con, "escapetest");
    TestUtil.dropTable(con, "comparisontest");
    TestUtil.dropTable(con, "test_lock");
    TestUtil.execute(con, "DROP FUNCTION IF EXISTS notify_loop()");
    TestUtil.execute(con, "DROP FUNCTION IF EXISTS notify_then_sleep()");
    con.close();
  }

  @Test
  void close() throws SQLException {
    Statement stmt = con.createStatement();
    stmt.close();

    try {
      stmt.getResultSet();
      fail("statements should not be re-used after close");
    } catch (SQLException ex) {
    }
  }

  @Test
  void resultSetClosed() throws SQLException {
    Statement stmt = con.createStatement();
    ResultSet rs = stmt.executeQuery("select 1");
    stmt.close();
    assertTrue(rs.isClosed());
  }

  /**
   * Closing a Statement twice is not an error.
   */
  @Test
  void doubleClose() throws SQLException {
    Statement stmt = con.createStatement();
    stmt.close();
    stmt.close();
  }

  @Test
  void multiExecute() throws SQLException {
    Statement stmt = con.createStatement();
    assertTrue(stmt.execute("SELECT 1 as a; UPDATE test_statement SET i=1; SELECT 2 as b, 3 as c"));

    ResultSet rs = stmt.getResultSet();
    assertTrue(rs.next());
    assertEquals(1, rs.getInt(1));
    rs.close();

    assertFalse(stmt.getMoreResults());
    assertEquals(0, stmt.getUpdateCount());

    assertTrue(stmt.getMoreResults());
    rs = stmt.getResultSet();
    assertTrue(rs.next());
    assertEquals(2, rs.getInt(1));
    rs.close();

    assertFalse(stmt.getMoreResults());
    assertEquals(-1, stmt.getUpdateCount());
    stmt.close();
  }

  @Test
  void emptyQuery() throws SQLException {
    Statement stmt = con.createStatement();
    stmt.execute("");
    assertNull(stmt.getResultSet());
    assertFalse(stmt.getMoreResults());
  }

  @Test
  void updateCount() throws SQLException {
    Statement stmt = con.createStatement();
    int count;

    count = stmt.executeUpdate("INSERT INTO test_statement VALUES (3)");
    assertEquals(1, count);
    count = stmt.executeUpdate("INSERT INTO test_statement VALUES (3)");
    assertEquals(1, count);

    count = stmt.executeUpdate("UPDATE test_statement SET i=4");
    assertEquals(2, count);

    count = stmt.executeUpdate("CREATE TEMP TABLE another_table (a int)");
    assertEquals(0, count);

    if (TestUtil.haveMinimumServerVersion(con, ServerVersion.v9_0)) {
      count = stmt.executeUpdate("CREATE TEMP TABLE yet_another_table AS SELECT x FROM generate_series(1,10) x");
      assertEquals(10, count);
    }
  }

  @Test
  void escapeProcessing() throws SQLException {
    Statement stmt = con.createStatement();
    int count;

    count = stmt.executeUpdate("insert into escapetest (ts) values ({ts '1900-01-01 00:00:00'})");
    assertEquals(1, count);

    count = stmt.executeUpdate("insert into escapetest (d) values ({d '1900-01-01'})");
    assertEquals(1, count);

    count = stmt.executeUpdate("insert into escapetest (t) values ({t '00:00:00'})");
    assertEquals(1, count);

    ResultSet rs = stmt.executeQuery("select {fn version()} as version");
    assertTrue(rs.next());

    // check nested and multiple escaped functions
    rs = stmt.executeQuery("select {fn version()} as version, {fn log({fn log(3.0)})} as log");
    assertTrue(rs.next());
    assertEquals(Math.log(Math.log(3)), rs.getDouble(2), 0.00001);

    stmt.executeUpdate("UPDATE escapetest SET \")\" = 'a', \"\"\"){a}'\" = 'b'");

    // check "difficult" values
    rs = stmt.executeQuery("select {fn concat(')',escapetest.\")\")} as concat"
        + ", {fn concat('{','}')} "
        + ", {fn concat('''','\"')} "
        + ", {fn concat(\"\"\"){a}'\", '''}''')} "
        + " FROM escapetest");
    assertTrue(rs.next());
    assertEquals(")a", rs.getString(1));
    assertEquals("{}", rs.getString(2));
    assertEquals("'\"", rs.getString(3));
    assertEquals("b'}'", rs.getString(4));

    count = stmt.executeUpdate("create temp table b (i int)");
    assertEquals(0, count);

    rs = stmt.executeQuery("select * from {oj test_statement a left outer join b on (a.i=b.i)} ");
    assertFalse(rs.next());
    // test escape character
    rs = stmt
        .executeQuery("select str2 from comparisontest where str1 like '|_abcd' {escape '|'} ");
    assertTrue(rs.next());
    assertEquals("_found", rs.getString(1));
    rs = stmt
        .executeQuery("select str2 from comparisontest where str1 like '|%abcd' {escape '|'} ");
    assertTrue(rs.next());
    assertEquals("%found", rs.getString(1));
  }

  @Test
  void preparedFunction() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement("SELECT {fn concat('a', ?)}");
    pstmt.setInt(1, 5);
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    assertEquals("a5", rs.getString(1));
  }

  @Test
  void dollarInComment() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement("SELECT /* $ */ {fn curdate()}");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    assertNotNull(rs.getString(1), "{fn curdate()} should be not null");
  }

  @Test
  void dollarInCommentTwoComments() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement("SELECT /* $ *//* $ */ {fn curdate()}");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    assertNotNull(rs.getString(1), "{fn curdate()} should be not null");
  }

  @Test
  void numericFunctions() throws SQLException {
    Statement stmt = con.createStatement();

    ResultSet rs = stmt.executeQuery("select {fn abs(-2.3)} as abs ");
    assertTrue(rs.next());
    assertEquals(2.3f, rs.getFloat(1), 0.00001);

    rs = stmt.executeQuery("select {fn acos(-0.6)} as acos ");
    assertTrue(rs.next());
    assertEquals(Math.acos(-0.6), rs.getDouble(1), 0.00001);

    rs = stmt.executeQuery("select {fn asin(-0.6)} as asin ");
    assertTrue(rs.next());
    assertEquals(Math.asin(-0.6), rs.getDouble(1), 0.00001);

    rs = stmt.executeQuery("select {fn atan(-0.6)} as atan ");
    assertTrue(rs.next());
    assertEquals(Math.atan(-0.6), rs.getDouble(1), 0.00001);

    rs = stmt.executeQuery("select {fn atan2(-2.3,7)} as atan2 ");
    assertTrue(rs.next());
    assertEquals(Math.atan2(-2.3, 7), rs.getDouble(1), 0.00001);

    rs = stmt.executeQuery("select {fn ceiling(-2.3)} as ceiling ");
    assertTrue(rs.next());
    assertEquals(-2, rs.getDouble(1), 0.00001);

    rs = stmt.executeQuery("select {fn cos(-2.3)} as cos, {fn cot(-2.3)} as cot ");
    assertTrue(rs.next());
    assertEquals(Math.cos(-2.3), rs.getDouble(1), 0.00001);
    assertEquals(1 / Math.tan(-2.3), rs.getDouble(2), 0.00001);

    rs = stmt.executeQuery("select {fn degrees({fn pi()})} as degrees ");
    assertTrue(rs.next());
    assertEquals(180, rs.getDouble(1), 0.00001);

    rs = stmt.executeQuery("select {fn exp(-2.3)}, {fn floor(-2.3)},"
        + " {fn log(2.3)},{fn log10(2.3)},{fn mod(3,2)}");
    assertTrue(rs.next());
    assertEquals(Math.exp(-2.3), rs.getDouble(1), 0.00001);
    assertEquals(-3, rs.getDouble(2), 0.00001);
    assertEquals(Math.log(2.3), rs.getDouble(3), 0.00001);
    assertEquals(Math.log(2.3) / Math.log(10), rs.getDouble(4), 0.00001);
    assertEquals(1, rs.getDouble(5), 0.00001);

    rs = stmt.executeQuery("select {fn pi()}, {fn power(7,-2.3)},"
        + " {fn radians(-180)},{fn round(3.1294,2)}");
    assertTrue(rs.next());
    assertEquals(Math.PI, rs.getDouble(1), 0.00001);
    assertEquals(Math.pow(7, -2.3), rs.getDouble(2), 0.00001);
    assertEquals(-Math.PI, rs.getDouble(3), 0.00001);
    assertEquals(3.13, rs.getDouble(4), 0.00001);

    rs = stmt.executeQuery("select {fn sign(-2.3)}, {fn sin(-2.3)},"
        + " {fn sqrt(2.3)},{fn tan(-2.3)},{fn truncate(3.1294,2)}");
    assertTrue(rs.next());
    assertEquals(-1, rs.getInt(1));
    assertEquals(Math.sin(-2.3), rs.getDouble(2), 0.00001);
    assertEquals(Math.sqrt(2.3), rs.getDouble(3), 0.00001);
    assertEquals(Math.tan(-2.3), rs.getDouble(4), 0.00001);
    assertEquals(3.12, rs.getDouble(5), 0.00001);
  }

  @Test
  void stringFunctions() throws SQLException {
    Statement stmt = con.createStatement();
    ResultSet rs = stmt.executeQuery(
        "select {fn ascii(' test')},{fn char(32)}"
        + ",{fn concat('ab','cd')}"
        + ",{fn lcase('aBcD')},{fn left('1234',2)},{fn length('123 ')}"
        + ",{fn locate('bc','abc')},{fn locate('bc','abc',3)}");
    assertTrue(rs.next());
    assertEquals(32, rs.getInt(1));
    assertEquals(" ", rs.getString(2));
    assertEquals("abcd", rs.getString(3));
    assertEquals("abcd", rs.getString(4));
    assertEquals("12", rs.getString(5));
    assertEquals(3, rs.getInt(6));
    assertEquals(2, rs.getInt(7));
    assertEquals(0, rs.getInt(8));

    rs = stmt.executeQuery(
        "SELECT {fn insert('abcdef',3,2,'xxxx')}"
        + ",{fn replace('abcdbc','bc','x')}");
    assertTrue(rs.next());
    assertEquals("abxxxxef", rs.getString(1));
    assertEquals("axdx", rs.getString(2));

    rs = stmt.executeQuery(
        "select {fn ltrim(' ab')},{fn repeat('ab',2)}"
        + ",{fn right('abcde',2)},{fn rtrim('ab ')}"
        + ",{fn space(3)},{fn substring('abcd',2,2)}"
        + ",{fn ucase('aBcD')}");
    assertTrue(rs.next());
    assertEquals("ab", rs.getString(1));
    assertEquals("abab", rs.getString(2));
    assertEquals("de", rs.getString(3));
    assertEquals("ab", rs.getString(4));
    assertEquals("   ", rs.getString(5));
    assertEquals("bc", rs.getString(6));
    assertEquals("ABCD", rs.getString(7));
  }

  @Test
  void dateFuncWithParam() throws SQLException {
    // Prior to 8.0 there is not an interval + timestamp operator,
    // so timestampadd does not work.
    //

    PreparedStatement ps = con.prepareStatement(
        "SELECT {fn timestampadd(SQL_TSI_QUARTER, ? ,{fn now()})}, {fn timestampadd(SQL_TSI_MONTH, ?, {fn now()})} ");
    ps.setInt(1, 4);
    ps.setInt(2, 12);
    ResultSet rs = ps.executeQuery();
    assertTrue(rs.next());
    assertEquals(rs.getTimestamp(1), rs.getTimestamp(2));
  }

  @Test
  void dateFunctions() throws SQLException {
    Statement stmt = con.createStatement();
    ResultSet rs = stmt.executeQuery("select {fn curdate()},{fn curtime()}"
        + ",{fn dayname({fn now()})}, {fn dayofmonth({fn now()})}"
        + ",{fn dayofweek({ts '2005-01-17 12:00:00'})},{fn dayofyear({fn now()})}"
        + ",{fn hour({fn now()})},{fn minute({fn now()})}"
        + ",{fn month({fn now()})}"
        + ",{fn monthname({fn now()})},{fn quarter({fn now()})}"
        + ",{fn second({fn now()})},{fn week({fn now()})}"
        + ",{fn year({fn now()})} ");
    assertTrue(rs.next());
    // ensure sunday =>1 and monday =>2
    assertEquals(2, rs.getInt(5));

    // Prior to 8.0 there is not an interval + timestamp operator,
    // so timestampadd does not work.
    //

    // second
    rs = stmt.executeQuery(
        "select {fn timestampdiff(SQL_TSI_SECOND,{fn now()},{fn timestampadd(SQL_TSI_SECOND,3,{fn now()})})} ");
    assertTrue(rs.next());
    assertEquals(3, rs.getInt(1));
    // MINUTE
    rs = stmt.executeQuery(
        "select {fn timestampdiff(SQL_TSI_MINUTE,{fn now()},{fn timestampadd(SQL_TSI_MINUTE,3,{fn now()})})} ");
    assertTrue(rs.next());
    assertEquals(3, rs.getInt(1));
    // HOUR
    rs = stmt.executeQuery(
        "select {fn timestampdiff(SQL_tsi_HOUR,{fn now()},{fn timestampadd(SQL_TSI_HOUR,3,{fn now()})})} ");
    assertTrue(rs.next());
    assertEquals(3, rs.getInt(1));
    // day
    rs = stmt.executeQuery(
        "select {fn timestampdiff(SQL_TSI_DAY,{fn now()},{fn timestampadd(SQL_TSI_DAY,-3,{fn now()})})} ");
    assertTrue(rs.next());
    int res = rs.getInt(1);
    if (res != -3 && res != -2) {
      // set TimeZone='America/New_York';
      // select CAST(-3 || ' day' as interval);
      // interval
      //----------
      // -3 days
      //
      // select CAST(-3 || ' day' as interval)+now();
      //           ?column?
      //-------------------------------
      // 2018-03-08 07:59:13.586895-05
      //
      // select CAST(-3 || ' day' as interval)+now()-now();
      //     ?column?
      //-------------------
      // -2 days -23:00:00
      fail("CAST(-3 || ' day' as interval)+now()-now() is expected to return -3 or -2. Actual value is " + res);
    }
    // WEEK => extract week from interval is not supported by backend
    // rs = stmt.executeQuery("select {fn timestampdiff(SQL_TSI_WEEK,{fn now()},{fn
    // timestampadd(SQL_TSI_WEEK,3,{fn now()})})} ");
    // assertTrue(rs.next());
    // assertEquals(3,rs.getInt(1));
    // MONTH => backend assume there are 0 month in an interval of 92 days...
    // rs = stmt.executeQuery("select {fn timestampdiff(SQL_TSI_MONTH,{fn now()},{fn
    // timestampadd(SQL_TSI_MONTH,3,{fn now()})})} ");
    // assertTrue(rs.next());
    // assertEquals(3,rs.getInt(1));
    // QUARTER => backend assume there are 1 quarter even in 270 days...
    // rs = stmt.executeQuery("select {fn timestampdiff(SQL_TSI_QUARTER,{fn now()},{fn
    // timestampadd(SQL_TSI_QUARTER,3,{fn now()})})} ");
    // assertTrue(rs.next());
    // assertEquals(3,rs.getInt(1));
    // YEAR
    // rs = stmt.executeQuery("select {fn timestampdiff(SQL_TSI_YEAR,{fn now()},{fn
    // timestampadd(SQL_TSI_YEAR,3,{fn now()})})} ");
    // assertTrue(rs.next());
    // assertEquals(3,rs.getInt(1));
  }

  @Test
  void systemFunctions() throws SQLException {
    Statement stmt = con.createStatement();
    ResultSet rs = stmt.executeQuery(
        "select {fn ifnull(null,'2')}"
        + ",{fn user()} ");
    assertTrue(rs.next());
    assertEquals("2", rs.getString(1));
    assertEquals(TestUtil.getUser(), rs.getString(2));

    rs = stmt.executeQuery("select {fn database()} ");
    assertTrue(rs.next());
    assertEquals(TestUtil.getDatabase(), rs.getString(1));
  }

  @Test
  void warningsAreCleared() throws SQLException {
    Statement stmt = con.createStatement();
    // Will generate a NOTICE: for primary key index creation
    stmt.execute("CREATE TEMP TABLE unused (a int primary key)");
    stmt.executeQuery("SELECT 1");
    // Executing another query should clear the warning from the first one.
    assertNull(stmt.getWarnings());
    stmt.close();
  }

  @Test
  void warningsAreAvailableAsap()
      throws Exception {
    try (Connection outerLockCon = TestUtil.openDB()) {
      outerLockCon.setAutoCommit(false);
      //Acquire an exclusive lock so we can block the notice generating statement
      outerLockCon.createStatement().execute("LOCK TABLE test_lock IN ACCESS EXCLUSIVE MODE;");
      con.createStatement()
              .execute("CREATE OR REPLACE FUNCTION notify_then_sleep() RETURNS VOID AS "
                  + "$BODY$ "
                  + "BEGIN "
                  + "RAISE NOTICE 'Test 1'; "
                  + "RAISE NOTICE 'Test 2'; "
                  + "LOCK TABLE test_lock IN ACCESS EXCLUSIVE MODE; "
                  + "END "
                  + "$BODY$ "
                  + "LANGUAGE plpgsql;");
      con.createStatement().execute("SET SESSION client_min_messages = 'NOTICE'");
      //If we never receive the two warnings the statement will just hang, so set a low timeout
      con.createStatement().execute("SET SESSION statement_timeout = 1000");
      final PreparedStatement preparedStatement = con.prepareStatement("SELECT notify_then_sleep()");
      final Callable<Void> warningReader = new Callable<Void>() {
        @Override
        public Void call() throws SQLException, InterruptedException {
          while (true) {
            SQLWarning warning = preparedStatement.getWarnings();
            if (warning != null) {
              assertEquals("Test 1", warning.getMessage(), "First warning received not first notice raised");
              SQLWarning next = warning.getNextWarning();
              if (next != null) {
                assertEquals("Test 2", next.getMessage(), "Second warning received not second notice raised");
                //Release the lock so that the notice generating statement can end.
                outerLockCon.commit();
                return null;
              }
            }
            //Break the loop on InterruptedException
            Thread.sleep(0);
          }
        }
      };
      ExecutorService executorService = Executors.newSingleThreadExecutor();
      try {
        Future<Void> future = executorService.submit(warningReader);
        //Statement should only finish executing once we have
        //received the two notices and released the outer lock.
        preparedStatement.execute();

        //If test takes longer than 2 seconds its a failure.
        future.get(2, TimeUnit.SECONDS);
      } finally {
        executorService.shutdownNow();
      }
    }
  }

  /**
   * Demonstrates a safe approach to concurrently reading the latest
   * warnings while periodically clearing them.
   *
   * <p>One drawback of this approach is that it requires the reader to make it to the end of the
   * warning chain before clearing it, so long as your warning processing step is not very slow,
   * this should happen more or less instantaneously even if you receive a lot of warnings.</p>
   */
  @Test
  void concurrentWarningReadAndClear()
      throws SQLException, InterruptedException, ExecutionException, TimeoutException {
    final int iterations = 1000;
    con.createStatement()
        .execute("CREATE OR REPLACE FUNCTION notify_loop() RETURNS VOID AS "
            + "$BODY$ "
            + "BEGIN "
            + "FOR i IN 1.. " + iterations + " LOOP "
            + "  RAISE NOTICE 'Warning %', i; "
            + "END LOOP; "
            + "END "
            + "$BODY$ "
            + "LANGUAGE plpgsql;");
    con.createStatement().execute("SET SESSION client_min_messages = 'NOTICE'");
    final PreparedStatement statement = con.prepareStatement("SELECT notify_loop()");
    final Callable<Void> warningReader = new Callable<Void>() {
      @Override
      public Void call() throws SQLException, InterruptedException {
        SQLWarning lastProcessed = null;
        int warnings = 0;
        //For production code replace this with some condition that
        //ends after the statement finishes execution
        while (warnings < iterations) {
          SQLWarning warn = statement.getWarnings();
          //if next linked warning has value use that, otherwise keep using latest head
          if (lastProcessed != null && lastProcessed.getNextWarning() != null) {
            warn = lastProcessed.getNextWarning();
          }
          if (warn != null) {
            warnings++;
            //System.out.println("Processing " + warn.getMessage());
            assertEquals("Warning " + warnings, warn.getMessage(), "Received warning out of expected order");
            lastProcessed = warn;
            //If the processed warning was the head of the chain clear
            if (warn == statement.getWarnings()) {
              //System.out.println("Clearing warnings");
              statement.clearWarnings();
            }
          } else {
            //Not required for this test, but a good idea adding some delay for production code
            //to avoid high cpu usage while the query is running and no warnings are coming in.
            //Alternatively use JDK9's Thread.onSpinWait()
            Thread.sleep(10);
          }
        }
        assertEquals("Warning " + iterations, lastProcessed.getMessage(), "Didn't receive expected last warning");
        return null;
      }
    };

    final ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      final Future warningReaderThread = executor.submit(warningReader);
      statement.execute();
      //If the reader doesn't return after 2 seconds, it failed.
      warningReaderThread.get(2, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }
  }

  /**
   * The parser tries to break multiple statements into individual queries as required by the V3
   * extended query protocol. It can be a little overzealous sometimes and this test ensures we keep
   * multiple rule actions together in one statement.
   */
  @Test
  void parsingSemiColons() throws SQLException {
    Statement stmt = con.createStatement();
    stmt.execute(
        "CREATE RULE r1 AS ON INSERT TO escapetest DO (DELETE FROM test_statement ; INSERT INTO test_statement VALUES (1); INSERT INTO test_statement VALUES (2); );");
    stmt.executeUpdate("INSERT INTO escapetest(ts) VALUES (NULL)");
    ResultSet rs = stmt.executeQuery("SELECT i from test_statement ORDER BY i");
    assertTrue(rs.next());
    assertEquals(1, rs.getInt(1));
    assertTrue(rs.next());
    assertEquals(2, rs.getInt(1));
    assertFalse(rs.next());
  }

  @Test
  void parsingDollarQuotes() throws SQLException {
    // dollar-quotes are supported in the backend since version 8.0
    Statement st = con.createStatement();
    ResultSet rs;

    rs = st.executeQuery("SELECT '$a$ ; $a$'");
    assertTrue(rs.next());
    assertEquals("$a$ ; $a$", rs.getObject(1));
    rs.close();

    rs = st.executeQuery("SELECT $$;$$");
    assertTrue(rs.next());
    assertEquals(";", rs.getObject(1));
    rs.close();

    rs = st.executeQuery("SELECT $OR$$a$'$b$a$$OR$ WHERE '$a$''$b$a$'=$OR$$a$'$b$a$$OR$OR ';'=''");
    assertTrue(rs.next());
    assertEquals("$a$'$b$a$", rs.getObject(1));
    assertFalse(rs.next());
    rs.close();

    rs = st.executeQuery("SELECT $B$;$b$B$");
    assertTrue(rs.next());
    assertEquals(";$b", rs.getObject(1));
    rs.close();

    rs = st.executeQuery("SELECT $c$c$;$c$");
    assertTrue(rs.next());
    assertEquals("c$;", rs.getObject(1));
    rs.close();

    rs = st.executeQuery("SELECT $A0$;$A0$ WHERE ''=$t$t$t$ OR ';$t$'=';$t$'");
    assertTrue(rs.next());
    assertEquals(";", rs.getObject(1));
    assertFalse(rs.next());
    rs.close();

    st.executeQuery("SELECT /* */$$;$$/**//*;*/").close();
    st.executeQuery("SELECT /* */--;\n$$a$$/**/--\n--;\n").close();

    st.close();
  }

  @Test
  void unbalancedParensParseError() throws SQLException {
    Statement stmt = con.createStatement();
    try {
      stmt.executeQuery("SELECT i FROM test_statement WHERE (1 > 0)) ORDER BY i");
      fail("Should have thrown a parse error.");
    } catch (SQLException sqle) {
    }
  }

  @Test
  void executeUpdateFailsOnSelect() throws SQLException {
    Statement stmt = con.createStatement();
    try {
      stmt.executeUpdate("SELECT 1");
      fail("Should have thrown an error.");
    } catch (SQLException sqle) {
    }
  }

  @Test
  void executeUpdateFailsOnMultiStatementSelect() throws SQLException {
    Statement stmt = con.createStatement();
    try {
      stmt.executeUpdate("/* */; SELECT 1");
      fail("Should have thrown an error.");
    } catch (SQLException sqle) {
    }
  }

  @Test
  void setQueryTimeout() throws SQLException {
    Statement stmt = con.createStatement();
    long start = 0;
    boolean cancelReceived = false;
    try {
      stmt.setQueryTimeout(1);
      start = System.nanoTime();
      stmt.execute("select pg_sleep(10)");
    } catch (SQLException sqle) {
      // state for cancel
      if ("57014".equals(sqle.getSQLState())) {
        cancelReceived = true;
      }
    }
    long duration = System.nanoTime() - start;
    if (!cancelReceived || duration > TimeUnit.SECONDS.toNanos(5)) {
      fail("Query should have been cancelled since the timeout was set to 1 sec."
          + " Cancel state: " + cancelReceived + ", duration: " + duration);
    }
  }

  @Test
  void longQueryTimeout() throws SQLException {
    Statement stmt = con.createStatement();
    stmt.setQueryTimeout(Integer.MAX_VALUE);
    assertEquals(Integer.MAX_VALUE,
        stmt.getQueryTimeout(),
        "setQueryTimeout(Integer.MAX_VALUE)");
    stmt.setQueryTimeout(Integer.MAX_VALUE - 1);
    assertEquals(Integer.MAX_VALUE - 1,
        stmt.getQueryTimeout(),
        "setQueryTimeout(Integer.MAX_VALUE-1)");
  }

  /**
   * Test executes two queries one after another. The first one has timeout of 1ms, and the second
   * one does not. The timeout of the first query should not impact the second one.
   */
  @Test
  void shortQueryTimeout() throws SQLException {

    long deadLine = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    Statement stmt = con.createStatement();
    ((PgStatement) stmt).setQueryTimeoutMs(1);
    Statement stmt2 = con.createStatement();
    while (System.nanoTime() < deadLine) {
      try {
        // This usually won't time out but scheduler jitter, server load
        // etc can cause a timeout.
        stmt.executeQuery("select 1;");
      } catch (SQLException e) {
        // Expect "57014 query_canceled" (en-msg is "canceling statement due to statement timeout")
        // but anything else is fatal. We can't differentiate other causes of statement cancel like
        // "canceling statement due to user request" without error message matching though, and we
        // don't want to do that.
        assertEquals(
            PSQLState.QUERY_CANCELED.getState(),
            e.getSQLState(),
            "Query is expected to be cancelled via st.close(), got " + e.getMessage());
      }
      // Must never time out.
      stmt2.executeQuery("select 1;");
    }
  }

  @Test
  void setQueryTimeoutWithSleep() throws SQLException, InterruptedException {
    // check that the timeout starts ticking at execute, not at the
    // setQueryTimeout call.
    Statement stmt = con.createStatement();
    try {
      stmt.setQueryTimeout(1);
      Thread.sleep(3000);
      stmt.execute("select pg_sleep(5)");
      fail("statement should have been canceled by query timeout");
    } catch (SQLException sqle) {
      // state for cancel
      if (sqle.getSQLState().compareTo("57014") != 0) {
        throw sqle;
      }
    }
  }

  @Test
  void setQueryTimeoutOnPrepared() throws SQLException, InterruptedException {
    // check that a timeout set on a prepared statement works on every
    // execution.
    PreparedStatement pstmt = con.prepareStatement("select pg_sleep(5)");
    pstmt.setQueryTimeout(1);
    for (int i = 1; i <= 3; i++) {
      try {
        ResultSet rs = pstmt.executeQuery();
        fail("statement should have been canceled by query timeout (execution #" + i + ")");
      } catch (SQLException sqle) {
        // state for cancel
        if (sqle.getSQLState().compareTo("57014") != 0) {
          throw sqle;
        }
      }
    }
  }

  @Test
  void setQueryTimeoutWithoutExecute() throws SQLException, InterruptedException {
    // check that a timeout set on one statement doesn't affect another
    Statement stmt1 = con.createStatement();
    stmt1.setQueryTimeout(1);

    Statement stmt2 = con.createStatement();
    ResultSet rs = stmt2.executeQuery("SELECT pg_sleep(2)");
  }

  @Test
  void resultSetTwice() throws SQLException {
    Statement stmt = con.createStatement();

    ResultSet rs = stmt.executeQuery("select {fn abs(-2.3)} as abs ");
    assertNotNull(rs);

    ResultSet rsOther = stmt.getResultSet();
    assertNotNull(rsOther);
  }

  @Test
  void multipleCancels() throws Exception {
    SharedTimer sharedTimer = Driver.getSharedTimer();

    try (Connection connA = TestUtil.openDB();
         Connection connB = TestUtil.openDB();
         Statement stmtA = connA.createStatement();
         Statement stmtB = connB.createStatement();
    ) {
      assertEquals(0, sharedTimer.getRefCount());
      stmtA.setQueryTimeout(1);
      stmtB.setQueryTimeout(1);
      try (ResultSet rsA = stmtA.executeQuery("SELECT pg_sleep(2)")) {
        fail("statement should have been canceled by query timeout since the sleep should take 2 sec and the timeout was 1 sec");
      } catch (SQLException e) {
        assertEquals(
            PSQLState.QUERY_CANCELED.getState(), e.getSQLState(),
            "Query is expected to be cancelled since the sleep should take 2 sec and the timeout was 1 sec");
      }
      assertEquals(1, sharedTimer.getRefCount());
      try (ResultSet rsB = stmtB.executeQuery("SELECT pg_sleep(2)");) {
        fail("statement should have been canceled by query timeout since the sleep should take 2 sec and the timeout was 1 sec");
      } catch (SQLException e) {
        assertEquals(
            PSQLState.QUERY_CANCELED.getState(), e.getSQLState(),
            "Query is expected to be cancelled since the sleep should take 2 sec and the timeout was 1 sec");
      }
    }
    assertEquals(0, sharedTimer.getRefCount());
  }

  @Test
  @Timeout(30)
  void cancelQueryWithBrokenNetwork() throws SQLException, IOException, InterruptedException {
    // check that stmt.cancel() doesn't hang forever if the network is broken

    ExecutorService executor = Executors.newCachedThreadPool();

    try (StrangeProxyServer proxyServer = new StrangeProxyServer(TestUtil.getServer(), TestUtil.getPort())) {
      Properties props = new Properties();
      TestUtil.setTestUrlProperty(props, PGProperty.PG_HOST, "localhost");
      TestUtil.setTestUrlProperty(props, PGProperty.PG_PORT, String.valueOf(proxyServer.getServerPort()));
      PGProperty.CANCEL_SIGNAL_TIMEOUT.set(props, 1);

      try (Connection conn = TestUtil.openDB(props); Statement stmt = conn.createStatement()) {
        executor.submit(() -> stmt.execute("select pg_sleep(60)"));

        Thread.sleep(1000);
        proxyServer.stopForwardingAllClients();

        stmt.cancel();
        // Note: network is still inaccessible, so the statement execution is still in progress.
        // So we abort the connection to allow implicit conn.close()
        conn.abort(executor);
      }
    }

    executor.shutdownNow();
  }

  /*
  We are going to use this test to test version 3.2 since the only change in 3.2 is the width of the
  cancel key. We need a test that does a cancel. We call this below once without changing the
  protocol version and once with protocol version 3.2
   */
  private void closePrivateInProgressStatement() throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try (Connection outerLockCon = TestUtil.openDB()) {
      outerLockCon.setAutoCommit(false);
      //Acquire an exclusive lock so we can block the notice generating statement
      outerLockCon.createStatement().execute("LOCK TABLE test_lock IN ACCESS EXCLUSIVE MODE;");

      con.createStatement().execute("SET SESSION client_min_messages = 'NOTICE'");
      con.createStatement()
          .execute("CREATE OR REPLACE FUNCTION notify_then_sleep() RETURNS VOID AS "
              + "$BODY$ "
              + "BEGIN "
              + "RAISE NOTICE 'start';"
              + "LOCK TABLE test_lock IN ACCESS EXCLUSIVE MODE;"
              + "END "
              + "$BODY$ "
              + "LANGUAGE plpgsql;");
      int cancels = 0;
      for (int i = 0; i < 100; i++) {
        final Statement st = con.createStatement();
        executor.submit(new Callable<Void>() {
          @Override
          public Void call() throws Exception {
            long start = System.nanoTime();
            while (st.getWarnings() == null) {
              long dt = System.nanoTime() - start;
              if (dt > TimeUnit.SECONDS.toNanos(10)) {
                throw new IllegalStateException("Expected to receive a notice within 10 seconds");
              }
            }
            st.close();
            return null;
          }
        });
        st.setQueryTimeout(120);
        try {
          st.execute("select notify_then_sleep()");
        } catch (SQLException e) {
          assertEquals(
              PSQLState.QUERY_CANCELED.getState(),
              e.getSQLState(),
              "Query is expected to be cancelled via st.close(), got " + e.getMessage()
          );
          cancels++;
          break;
        } finally {
          TestUtil.closeQuietly(st);
        }
      }
      assertNotEquals(0, cancels, "At least one QUERY_CANCELED state is expected");
    } finally {
      executor.shutdown();
    }
  }

  @Test
  @Timeout(10)
  void closeInProgressStatement() throws Exception {
    closePrivateInProgressStatement();
  }

  @Test
  @Timeout(10)
  void closeInProgressStatementProtocol32() throws Exception {
    assumeTrue(TestUtil.haveMinimumServerVersion(con, ServerVersion.v11));
    Properties props = new Properties();
    con.close();
    PGProperty.PROTOCOL_VERSION.set(props, "3.2");
    con = TestUtil.openDB(props);
    closePrivateInProgressStatement();
  }

  @Test
  @Timeout(10)
  void concurrentIsValid() throws Throwable {
    ExecutorService executor = Executors.newCachedThreadPool();
    try {
      List<Future<?>> results = new ArrayList<>();
      Random rnd = new Random();
      for (int i = 0; i < 10; i++) {
        Future<?> future = executor.submit(() -> {
          try {
            for (int j = 0; j < 50; j++) {
              con.isValid(2);
              try (PreparedStatement ps =
                       con.prepareStatement("select * from generate_series(1,?) as x(id)")) {
                int limit = rnd.nextInt(10);
                ps.setInt(1, limit);
                try (ResultSet r = ps.executeQuery()) {
                  int cnt = 0;
                  String callName = "generate_series(1, " + limit + ") in thread "
                      + Thread.currentThread().getName();
                  while (r.next()) {
                    cnt++;
                    assertEquals(cnt, r.getInt(1), callName + ", row " + cnt);
                  }
                  assertEquals(limit, cnt, callName + " number of rows");
                }
              }
            }
          } catch (SQLException e) {
            throw new RuntimeException(e);
          }
        });
        results.add(future);
      }
      for (Future<?> result : results) {
        // Propagate exception if any
        result.get();
      }
    } catch (ExecutionException e) {
      throw e.getCause();
    } finally {
      executor.shutdown();
      executor.awaitTermination(10, TimeUnit.SECONDS);
    }
  }

  @Test
  @Timeout(20)
  void fastCloses() throws SQLException {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    con.createStatement().execute("SET SESSION client_min_messages = 'NOTICE'");
    con.createStatement()
        .execute("CREATE OR REPLACE FUNCTION notify_then_sleep() RETURNS VOID AS "
            + "$BODY$ "
            + "BEGIN "
            + "RAISE NOTICE 'start';"
            + "EXECUTE pg_sleep(1);" // Note: timeout value does not matter here, we just test if test crashes or locks somehow
            + "END "
            + "$BODY$ "
            + "LANGUAGE plpgsql;");
    Map<String, Integer> cnt = new HashMap<>();
    final Random rnd = new Random();
    for (int i = 0; i < 1000; i++) {
      final Statement st = con.createStatement();
      executor.submit(new Callable<Void>() {
        @Override
        public Void call() throws Exception {
          int s = rnd.nextInt(10);
          if (s > 8) {
            try {
              Thread.sleep(s - 9);
            } catch (InterruptedException ex) {
              // don't execute the close here as this thread was cancelled below in shutdownNow
              return null;
            }
          }
          st.close();
          return null;
        }
      });
      ResultSet rs = null;
      String sqlState = "0";
      try {
        rs = st.executeQuery("select 1");
        // Acceptable
      } catch (SQLException e) {
        sqlState = e.getSQLState();
        if (!PSQLState.OBJECT_NOT_IN_STATE.getState().equals(sqlState)
            && !PSQLState.QUERY_CANCELED.getState().equals(sqlState)) {
          assertEquals(
              PSQLState.QUERY_CANCELED.getState(),
              e.getSQLState(),
              "Query is expected to be cancelled via st.close(), got " + e.getMessage()
          );
        }
      } finally {
        TestUtil.closeQuietly(rs);
        TestUtil.closeQuietly(st);
      }
      Integer val = cnt.get(sqlState);
      val = (val == null ? 0 : val) + 1;
      cnt.put(sqlState, val);
    }
    System.out.println("[testFastCloses] total counts for each sql state: " + cnt);
    executor.shutdown();
  }

  /**
   * Tests that calling {@code java.sql.Statement#close()} from a concurrent thread does not result
   * in {@link java.util.ConcurrentModificationException}.
   */
  @Test
  void sideStatementFinalizers() throws SQLException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);

    final AtomicInteger leaks = new AtomicInteger();
    final AtomicReference<Throwable> cleanupFailure = new AtomicReference<>();
    // Create several cleaners, so they can clean leaks concurrently
    List<LazyCleaner> cleaners = new ArrayList<>();
    for (int i = 0; i < 16; i++) {
      cleaners.add(new LazyCleaner(Duration.ofSeconds(2), "pgjdbc-test-cleaner-" + i));
    }

    for (int q = 0; System.nanoTime() < deadline || leaks.get() < 10000; q++) {
      for (int i = 0; i < 100; i++) {
        PreparedStatement ps = con.prepareStatement("select " + (i + q));
        ps.close();
      }
      final int nextId = q;
      int cleanerId = ThreadLocalRandom.current().nextInt(cleaners.size());
      PreparedStatement ps = con.prepareStatement("select /*leak*/ " + nextId);
      cleaners.get(cleanerId).register(new Object(), leak -> {
        try {
          ps.close();
        } catch (Throwable t) {
          cleanupFailure.compareAndSet(null, t);
        }
        leaks.incrementAndGet();
      });
    }
    if (cleanupFailure.get() != null) {
      throw new IllegalStateException("Detected failure in cleanup thread", cleanupFailure.get());
    }
  }

  /**
   * Test that $JAVASCRIPT$ protects curly braces from JDBC {fn now()} kind of syntax.
   * @throws SQLException if something goes wrong
   */
  @Test
  void javaScriptFunction() throws SQLException {
    String str = "  var _modules = {};\n"
        + "  var _current_stack = [];\n"
        + "\n"
        + "  // modules start\n"
        + "  _modules[\"/root/aidbox/fhirbase/src/core\"] = {\n"
        + "  init:  function(){\n"
        + "    var exports = {};\n"
        + "    _current_stack.push({file: \"core\", dir: \"/root/aidbox/fhirbase/src\"})\n"
        + "    var module = {exports: exports};";

    PreparedStatement ps = null;
    try {
      ps = con.prepareStatement("select $JAVASCRIPT$" + str + "$JAVASCRIPT$");
      ResultSet rs = ps.executeQuery();
      rs.next();
      assertEquals(str, rs.getString(1), "JavaScript code has been protected with $JAVASCRIPT$");
    } finally {
      TestUtil.closeQuietly(ps);
    }
  }

  @Test
  void unterminatedDollarQuotes() throws SQLException {
    ensureSyntaxException("dollar quotes", "CREATE OR REPLACE FUNCTION update_on_change() RETURNS TRIGGER AS $$\n"
        + "BEGIN");
  }

  @Test
  void unterminatedNamedDollarQuotes() throws SQLException {
    ensureSyntaxException("dollar quotes", "CREATE OR REPLACE FUNCTION update_on_change() RETURNS TRIGGER AS $ABC$\n"
        + "BEGIN");
  }

  @Test
  void unterminatedComment() throws SQLException {
    ensureSyntaxException("block comment", "CREATE OR REPLACE FUNCTION update_on_change() RETURNS TRIGGER AS /* $$\n"
        + "BEGIN $$");
  }

  @Test
  void unterminatedLiteral() throws SQLException {
    ensureSyntaxException("string literal", "CREATE OR REPLACE FUNCTION update_on_change() 'RETURNS TRIGGER AS $$\n"
        + "BEGIN $$");
  }

  @Test
  void unterminatedIdentifier() throws SQLException {
    ensureSyntaxException("string literal", "CREATE OR REPLACE FUNCTION \"update_on_change() RETURNS TRIGGER AS $$\n"
        + "BEGIN $$");
  }

  private void ensureSyntaxException(String errorType, String sql) throws SQLException {
    PreparedStatement ps = null;
    try {
      ps = con.prepareStatement(sql);
      ps.executeUpdate();
      fail("Query with unterminated " + errorType + " should fail");
    } catch (SQLException e) {
      assertEquals(PSQLState.SYNTAX_ERROR.getState(), e.getSQLState(), "Query should fail with unterminated " + errorType);
    } finally {
      TestUtil.closeQuietly(ps);
    }
  }
}
