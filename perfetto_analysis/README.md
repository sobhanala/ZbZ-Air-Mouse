# Perfetto Analysis

This folder contains the files used for Android Perfetto tracing and analysis.

## Files

- `config.pbtx`: Perfetto trace configuration for the Air Mouse app.
- `trace_file.perfetto-trace`: Initial quick trace captured directly with adb.
- `config_trace_file.perfetto-trace`: Trace captured using `config.pbtx`.
- `analyze_perfetto_trace.py`: Basic Python analysis script using Perfetto TraceProcessor.
- `record_airmouse_trace.sh`: Captures a new Air Mouse trace with `config.pbtx`.

## Capture a Trace With Config

Start the laptop receiver first, then connect the Android app and keep it in the
foreground with tracking enabled (`Stop Tracking` should be visible). During the
30-second trace, perform the scenario that should be analyzed: steady phone,
slow movement, sudden movement, click, and scroll.

Run from the project root:

```bash
perfetto_analysis/record_airmouse_trace.sh
```

The helper enables Android app tracing for `io.github.zbz.airmouse` before
recording so `android.os.Trace` sections appear in Perfetto.

Equivalent manual commands:

```bash
cat perfetto_analysis/config.pbtx | \
  ~/Library/Android/sdk/platform-tools/adb shell perfetto \
  -c - \
  --txt \
  -o /data/misc/perfetto-traces/config_trace_file.perfetto-trace

~/Library/Android/sdk/platform-tools/adb pull \
  /data/misc/perfetto-traces/config_trace_file.perfetto-trace \
  perfetto_analysis/config_trace_file.perfetto-trace
```

The stdin form is used because this device rejected reading
`/data/local/tmp/config.pbtx` directly with permission denied.

## Analyze Numerically

The current temporary Python environment is:

```bash
/private/tmp/zbz-perfetto-venv/bin/python
```

Run:

```bash
/private/tmp/zbz-perfetto-venv/bin/python \
  perfetto_analysis/analyze_perfetto_trace.py \
  perfetto_analysis/airmouse_trace.perfetto-trace
```

Current measured result from `config_trace_file.perfetto-trace`:

```text
duration_s: 14.599380076
context_switch_count: 23135
switches_per_second: 1584.6563264718172
```

This previous trace is only a setup check. Use a new trace captured while the
Air Mouse app is running for the final report.

## Perfetto UI

Docker is not currently available on this machine (`docker: command not found`),
so the Docker UI was not started.

If Docker is installed later, run:

```bash
docker pull gresearch/perfetto-ui:latest
docker run -d -p 9001:8080 --name perfetto-ui gresearch/perfetto-ui:latest
```

Then open:

```text
http://127.0.0.1:9001/
```

If internet access is available, the simpler option is:

```text
https://ui.perfetto.dev/
```

In either UI, choose `Open trace file` and open:

```text
perfetto_analysis/config_trace_file.perfetto-trace
```
