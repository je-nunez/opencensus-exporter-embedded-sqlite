# Intro

An example of exporting the traces from the OpenCensus tracing framework to a
SQLite database.

OpenCensus is a stats collection and distributed tracing framework, and the
link [https://opencensus.io/faq/](https://opencensus.io/faq/) lists briefly
the objectives and supported programming languages and backends where it can
export to the collected stats and traces.

Among the many uses of Open Census's distributed tracing and stats have, it
is also possible with it to find visibility into complex systems to find,
e.g., the contribution in, or sub-components of, the term **W** in
[Little's Law](https://en.wikipedia.org/wiki/Little%27s_law), which is
relevant for capacity analysis. This term **W** is the average time that a
client request spends in the system. For example, the following link is a
practical analysis using Little's Law on the bottlenecks of a complex system:
[Software Infrastructure Bottlenecks in J2EE, by Deepak Goel](https://web.archive.org/web/20050206022643/http://www.onjava.com/pub/a/onjava/2005/01/19/j2ee-bottlenecks.html).
Open Census can give visibility on the time spent in the components of
complex systems.

Sometimes it is necessary to export the traces to an embedded and small, but
easily queryable, backend, due to a restriction in the running environment.
SQLite fits this role.

# Run

To run this project use Maven:

      mvn
      mvn exec:java@run
      ... this is an infinite example, so it needs to be stopped by Ctrl-C ...

It will create a SQLite database file named `my_sqlite_trace_store.db` in the
current directory, which contains a table named `trace_store` (these names can
be easily changed). This table is where the traces are exported. The structure
of this table is:

      CREATE TABLE trace_store (
             name text NOT NULL,
             trace_id text NOT NULL,
             span_id text NOT NULL,
             parent_span_id text,
             start_time_epoch integer NOT NULL,
             start_time_nano integer NOT NULL,
             end_time_epoch integer NOT NULL,
             end_time_nano integer NOT NULL,
             annotations text
      );

The size is relatively small: for example, for 1032 trace records written to
this table (with the current program, for other traces will gather other data),
the SQLite database file (`my_sqlite_trace_store.db`) uses 172 KB in total, for
an average of around 170 bytes per trace record in this SQLite database. (As
said, other programs may generate traces which gather more data, so this
average space usage -important in small run-time environments- may not apply to
those programs.)

