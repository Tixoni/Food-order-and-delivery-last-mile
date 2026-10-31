package ru.mirea.delivery.http;

import java.util.Map;
import java.util.TreeMap;

/** Показатели запросов для /metrics: число и суммарное время по методу, маршруту и коду. */
public final class Metrics {
    private record Key(String method, String route, int status) implements Comparable<Key> {
        @Override
        public int compareTo(Key other) {
            int byMethod = method.compareTo(other.method);
            if (byMethod != 0) {
                return byMethod;
            }
            int byRoute = route.compareTo(other.route);
            return byRoute != 0 ? byRoute : Integer.compare(status, other.status);
        }
    }

    private static final class Counter {
        long count;
        double seconds;
    }

    private final String service;
    private final Map<Key, Counter> counters = new TreeMap<>();

    public Metrics(String service) {
        this.service = service;
    }

    public synchronized void record(String method, String route, int status, double seconds) {
        Counter counter = counters.computeIfAbsent(new Key(method, route, status), key -> new Counter());
        counter.count++;
        counter.seconds += seconds;
    }

    /** Формат Prometheus. */
    public synchronized String render() {
        StringBuilder text = new StringBuilder();
        text.append("# HELP http_requests_total Число обработанных запросов.\n");
        text.append("# TYPE http_requests_total counter\n");
        counters.forEach((key, counter) -> text.append("http_requests_total{").append(labels(key)).append("} ")
                .append(counter.count).append('\n'));
        text.append("# HELP http_request_duration_seconds Время обработки запросов.\n");
        text.append("# TYPE http_request_duration_seconds summary\n");
        counters.forEach((key, counter) -> {
            text.append("http_request_duration_seconds_sum{").append(labels(key)).append("} ")
                    .append(String.format(java.util.Locale.ROOT, "%.6f", counter.seconds)).append('\n');
            text.append("http_request_duration_seconds_count{").append(labels(key)).append("} ")
                    .append(counter.count).append('\n');
        });
        return text.toString();
    }

    private String labels(Key key) {
        return "service=\"" + service + "\",method=\"" + key.method() + "\",route=\"" + key.route()
                + "\",status=\"" + key.status() + "\"";
    }
}
