#!/usr/bin/env python3
import argparse

from perfetto.trace_processor import TraceProcessor, TraceProcessorConfig


def query_one(tp, sql):
    rows = list(tp.query(sql))
    return rows[0] if rows else {}


def query_all(tp, sql):
    return list(tp.query(sql))


def print_rows(title, rows):
    print(title)
    if rows:
        for row in rows:
            print(row)
    else:
        print("No rows")
    print()


def main():
    parser = argparse.ArgumentParser(description="Basic Perfetto trace analysis")
    parser.add_argument("trace", help="Path to .perfetto-trace file")
    args = parser.parse_args()

    config = TraceProcessorConfig(load_timeout=30)
    tp = TraceProcessor(trace=args.trace, config=config)
    try:
        bounds = query_one(
            tp,
            "select start_ts, end_ts, (end_ts - start_ts) / 1e9 as duration_s "
            "from trace_bounds",
        )
        switches = query_one(
            tp,
            "select count(*) as context_switch_count, "
            "(select (end_ts - start_ts) / 1e9 from trace_bounds) as duration_s, "
            "count(*) / (select (end_ts - start_ts) / 1e9 from trace_bounds) "
            "as switches_per_second from sched",
        )
        app_slices = query_all(
            tp,
            "select s.name, count(*) as count, "
            "round(avg(s.dur) / 1e6, 3) as avg_ms, "
            "round(max(s.dur) / 1e6, 3) as max_ms "
            "from slice s "
            "where s.name glob 'airmouse.*' "
            "group by s.name "
            "order by s.name",
        )
        app_threads = query_all(
            tp,
            "select s.name as section, coalesce(t.name, '[unnamed]') as thread, "
            "count(*) as count, round(avg(s.dur) / 1e6, 3) as avg_ms "
            "from slice s "
            "join thread_track tt on s.track_id = tt.id "
            "join thread t on tt.utid = t.utid "
            "where s.name glob 'airmouse.*' "
            "group by section, thread "
            "order by section, count desc",
        )
        sensor_periods = query_all(
            tp,
            "with sensor_events as ("
            "  select name, ts, lead(ts) over (partition by name order by ts) as next_ts "
            "  from slice where name in ("
            "    'airmouse.sensor.accel', "
            "    'airmouse.sensor.gyro', "
            "    'airmouse.sensor.mag'"
            "  )"
            ") "
            "select name, count(*) as intervals, "
            "round(avg(next_ts - ts) / 1e6, 3) as avg_period_ms, "
            "round(min(next_ts - ts) / 1e6, 3) as min_period_ms, "
            "round(max(next_ts - ts) / 1e6, 3) as max_period_ms "
            "from sensor_events "
            "where next_ts is not null "
            "group by name "
            "order by name",
        )

        print_rows("Trace bounds:", [bounds])
        print_rows("Context switches:", [switches])
        print_rows("Air Mouse trace sections:", app_slices)
        print_rows("Air Mouse sections by thread:", app_threads)
        print_rows("Sensor callback periods:", sensor_periods)
    finally:
        tp.close()


if __name__ == "__main__":
    main()
