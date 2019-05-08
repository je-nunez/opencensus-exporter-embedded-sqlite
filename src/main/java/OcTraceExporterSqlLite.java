
import io.opencensus.common.Scope;
import io.opencensus.common.Timestamp;

import io.opencensus.trace.SpanContext;
import io.opencensus.trace.Status;
import io.opencensus.trace.Tracer;
import io.opencensus.trace.Tracing;
import io.opencensus.trace.config.TraceConfig;
import io.opencensus.trace.config.TraceParams;
import io.opencensus.trace.export.SpanData;
import io.opencensus.trace.export.SpanExporter;
import io.opencensus.trace.samplers.Samplers;

import java.nio.charset.Charset;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

import java.util.Collection;
import java.util.Locale;
import java.util.Random;
import java.util.logging.Logger;


public class OcTraceExporterSqlLite extends SpanExporter.Handler {

  // based on the model from OcAgentTraceExporterSqlLite

  private static final Logger logger =
      Logger.getLogger(OcTraceExporterSqlLite.class.getName());

  // This is the structure of the table that is created in SQLite.
  // Notice that there are at least two ways to save an OpenCensus Timestamp
  // from the OpenCensus traces, which includes the nanoseconds:
  //
  // 1. As a single SQLite field with "REAL" type; or
  // 2. As two SQLite fields of "INTEGER" type.
  //
  // The first alternative (real value like <epoch>.<nanoseconds>) may lose
  // precision when saved as a SQLite REAL field. The second alternative above
  // does not lose precision, but it is uses slightly more space for a
  // SQLite record. The first alternative is commented below ("-- ..."), and
  // the second is the one used.

  private static String traceStoreSql =
      "CREATE TABLE IF NOT EXISTS trace_store (\n"
         + " name text NOT NULL,\n"
         + " trace_id text NOT NULL,\n"
         + " span_id text NOT NULL,\n"
         + " parent_span_id text,\n"
         + " -- start_time real NOT NULL,\n"
         + " start_time_epoch integer NOT NULL,\n"
         + " start_time_nano integer NOT NULL,\n"
         + " -- end_time real NOT NULL,\n"
         + " end_time_epoch integer NOT NULL,\n"
         + " end_time_nano integer NOT NULL,\n"
         + " annotations text\n"
         + ");";

  private static String insertSql =
      "INSERT INTO trace_store (name, \n"
         + " trace_id, \n"
         + " span_id, \n"
         + " parent_span_id, \n"
         + " start_time_epoch, \n"
         + " start_time_nano, \n"
         + " end_time_epoch, \n"
         + " end_time_nano, \n"
         + " annotations) VALUES(?,?,?,?,?,?,?,?,?)";

  private PreparedStatement prepInsertSqlStmt = null;

  private static double convertNanoseconds = Math.pow(10, 9);

  private static final Random random = new Random();

  private static final Tracer tracer = Tracing.getTracer();

  public OcTraceExporterSqlLite(PreparedStatement preparedInsertSql) {
    this.prepInsertSqlStmt = preparedInsertSql;
  }

  public static void main(String[] args) throws InterruptedException {

    // Always sample for demo purpose. DO NOT use in production.
    configureAlwaysSample();

    Connection traceSqlConnection =
        createSqlLiteTraceDbTable("my_sqlite_trace_store.db");

    try {
      PreparedStatement prepInsertSql =
          traceSqlConnection.prepareStatement(insertSql);

      registerAgentExporters(prepInsertSql);

      try (Scope scope = tracer.spanBuilder("root").startScopedSpan()) {
        int iteration = 1;
        while (true) {
          doWork(iteration, random.nextInt(10));
          iteration++;
          Thread.sleep(5000);
        }
      } catch (InterruptedException e) {
        logger.info("Thread interrupted, exiting in 5 seconds.");
        Thread.sleep(5000);    // Wait 5s so that last batch will be exported.
        traceSqlConnection.close();
      }
    } catch (SQLException e) {
      e.printStackTrace();
    }
  }

  private static Connection createSqlLiteTraceDbTable(String dbFilename) {
    String url = "jdbc:sqlite:" + dbFilename;

    Connection conn = null;
    try {
      conn = DriverManager.getConnection(url);
      if (conn == null) {
        // TODO: handle error when SQLite database can't be created
      }

      Statement createSqlStmt = conn.createStatement();
      createSqlStmt.execute(traceStoreSql);

    } catch (SQLException e) {
      System.err.println(e.getMessage());
    }

    return conn;
  }

  private static void configureAlwaysSample() {
    TraceConfig traceConfig = Tracing.getTraceConfig();
    TraceParams activeTraceParams = traceConfig.getActiveTraceParams();
    traceConfig.updateActiveTraceParams(
        activeTraceParams.toBuilder()
            .setSampler(Samplers.alwaysSample()).build()
    );
  }

  private static void registerAgentExporters(PreparedStatement prepInsertSql) {
    Tracing.getExportComponent()
        .getSpanExporter()
        .registerHandler(OcTraceExporterSqlLite.class.getName(),
                         new OcTraceExporterSqlLite(prepInsertSql));
  }

  @Override
  public void export(Collection<SpanData> spanDataList) {
    for (SpanData sd : spanDataList) {
      // Note: SpanData has more members than the ones reported below, so it is
      //       very powerful and invites to many possibilities (e.g., use
      //       JsonPath to query this list of SpanData [after conversion to
      //       Json]) -besides the more scalable trace exporters allowed by
      //       the Open Census agent.
      SpanContext sc = sd.getContext();
      Timestamp startTimeStamp = sd.getStartTimestamp();
      Timestamp endTimeStamp = sd.getEndTimestamp();

      try {
        prepInsertSqlStmt.setString(1, sd.getName());
        prepInsertSqlStmt.setString(2, sc.getTraceId().toLowerBase16());
        prepInsertSqlStmt.setString(3, sc.getSpanId().toLowerBase16());
        prepInsertSqlStmt.setString(4, sd.getParentSpanId().toLowerBase16());
        prepInsertSqlStmt.setLong(5, startTimeStamp.getSeconds());
        prepInsertSqlStmt.setLong(6, startTimeStamp.getNanos());
        prepInsertSqlStmt.setLong(7, endTimeStamp.getSeconds());
        prepInsertSqlStmt.setLong(8, endTimeStamp.getNanos());
        prepInsertSqlStmt.setString(9, sd.getAnnotations().toString());
        prepInsertSqlStmt.executeUpdate();
      } catch (SQLException e) {
        System.err.println(e.getMessage());
      }
    }
  }

  private static void doWork(int iteration, int jobs) {
    String childSpanName = "iteration-" + iteration;
    try (Scope scope = tracer.spanBuilder(childSpanName).startScopedSpan()) {
      for (int i = 0; i < jobs; i++) {
        String grandChildSpanName = childSpanName + "-job-" + i;
        try (Scope childScope =
                tracer.spanBuilder(grandChildSpanName).startScopedSpan()) {
          String line = generateRandom(random.nextInt(128));
          processLine(line);
        } catch (Exception e) {
          tracer.getCurrentSpan()
              .setStatus(Status.INTERNAL.withDescription(e.toString()));
        }
      }
    }
  }

  private static String generateRandom(int size) {
    byte[] array = new byte[size];
    random.nextBytes(array);
    return new String(array, Charset.forName("UTF-8"));
  }

  private static String processLine(String line) {
    try {
      Thread.sleep(10L);
      return line.toUpperCase(Locale.US);
    } catch (Exception e) {
      return "";
    }
  }

  private static String getStringOrDefaultFromArgs(String[] args,
                                                   int index,
                                                   String defaultString) {
    String s = defaultString;
    if (index < args.length) {
      s = args[index];
    }
    return s;
  }
}

