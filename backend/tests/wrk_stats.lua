-- wrk 延迟统计脚本：输出 P50/P95/P99/QPS/错误数
done = function(summary, latency, requests)
    local duration_s = summary.duration / 1000000
    local qps = summary.requests / duration_s
    io.write("---------------------------------------------\n")
    io.write(string.format("请求总数: %d\n", summary.requests))
    io.write(string.format("持续时间: %.2fs\n", duration_s))
    io.write(string.format("QPS: %.2f\n", qps))
    io.write(string.format("HTTP错误数: %d\n", summary.errors.status))
    io.write(string.format("平均延迟: %.2fms\n", latency.mean / 1000))
    io.write(string.format("最小延迟: %.2fms\n", latency.min / 1000))
    io.write(string.format("最大延迟: %.2fms\n", latency.max / 1000))
    io.write(string.format("P50: %.2fms\n", latency:percentile(50) / 1000))
    io.write(string.format("P95: %.2fms\n", latency:percentile(95) / 1000))
    io.write(string.format("P99: %.2fms\n", latency:percentile(99) / 1000))
    io.write("---------------------------------------------\n")
end
