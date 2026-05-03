# Performance and Benchmarking Ideas

## Benchmark Suite
- Microbenchmarks for lexer/parser/interpreter hot paths.
- Macrobenchmarks with realistic programs.

## Baseline Tracking
- Persist benchmark history per release.
- Detect regressions early in CI.

## Memory Profiling
- Track allocation hotspots and GC pressure.
- Validate lazy structures stay O(1)-friendly.

## Startup Optimization
- Measure and reduce cold-start latency.

## Playground Performance
- Profile browser runtime overhead and optimize bridges.

## Performance Docs
- Publish benchmark methodology and comparable results.
