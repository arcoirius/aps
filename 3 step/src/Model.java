import java.util.*;

enum RequestStatus { CREATED, BUFFERED, SELECTED, IN_SERVICE, COMPLETED, REJECTED }
enum EventType { REQUEST_GENERATED, SERVICE_COMPLETED }

final class Request {
    final long id;
    final int sourceId;
    final int sequence;
    final int priority;
    final double createdAt;
    double bufferedAt = Double.NaN;
    double serviceStartedAt = Double.NaN;
    double serviceFinishedAt = Double.NaN;
    RequestStatus status = RequestStatus.CREATED;

    Request(long id, int sourceId, int sequence, double createdAt) {
        this.id = id; this.sourceId = sourceId; this.sequence = sequence;
        this.priority = sourceId; this.createdAt = createdAt;
    }
    String label() { return "R" + sourceId + "." + sequence; }
    void buffered(double t) { require(RequestStatus.CREATED); bufferedAt = t; status = RequestStatus.BUFFERED; }
    void selected() { require(RequestStatus.BUFFERED); status = RequestStatus.SELECTED; }
    void started(double t) { require(RequestStatus.SELECTED); serviceStartedAt = t; status = RequestStatus.IN_SERVICE; }
    void completed(double t) { require(RequestStatus.IN_SERVICE); serviceFinishedAt = t; status = RequestStatus.COMPLETED; }
    void rejected(double t) { require(RequestStatus.BUFFERED); serviceFinishedAt = t; status = RequestStatus.REJECTED; }
    private void require(RequestStatus expected) {
        if (status != expected) throw new IllegalStateException(label() + " ожидает " + expected + ", сейчас " + status);
    }
}

final class Source {
    final int id;
    final double intervalMin, intervalMax;
    private int produced;
    double nextEventAt = Double.NaN;
    Source(int id, double min, double max) {
        if (min <= 0 || max < min) throw new IllegalArgumentException("Ошибка интервала источника " + id);
        this.id = id; this.intervalMin = min; this.intervalMax = max;
    }
    double nextInterval(Random random) { return intervalMin + random.nextDouble() * (intervalMax - intervalMin); }
    Request generate(long globalId, double t) { return new Request(globalId, id, ++produced, t); }
    int produced() { return produced; }
}

final class Buffer {
    private final int capacity;
    private final List<Request> queue = new ArrayList<>();
    Buffer(int capacity) { if (capacity < 1) throw new IllegalArgumentException("buffer < 1"); this.capacity = capacity; }
    int capacity() { return capacity; }
    int size() { return queue.size(); }
    boolean isEmpty() { return queue.isEmpty(); }
    boolean isFull() { return queue.size() == capacity; }
    List<Request> all() { return List.copyOf(queue); }
    void enqueue(Request r, double t) {
        if (isFull()) throw new IllegalStateException("буфер переполнен");
        r.buffered(t); queue.add(r);
    }
    void remove(Request r) {
        if (!queue.remove(r)) throw new IllegalStateException("нет в буфере: " + r.label());
    }
    // Д1ОО2: худший приоритет, при равенстве — наиболее ранняя заявка.
    Request lowestPriorityOldest() {
        return queue.stream().max(Comparator.comparingInt((Request r) -> r.priority)
            .thenComparingDouble(r -> -r.createdAt).thenComparingLong(r -> -r.id)).orElseThrow();
    }
    int bestSource() { return queue.stream().mapToInt(r -> r.sourceId).min().orElseThrow(); }
    List<Request> bySource(int sourceId) {
        List<Request> result = new ArrayList<>();
        for (Request r : queue) if (r.sourceId == sourceId) result.add(r);
        return result;
    }
}

final class Packet {
    final int sourceId;
    final long id;
    private final List<Request> snapshot;
    private final Deque<Request> waiting;
    private final Set<Request> outstanding;
    Packet(long id, int sourceId, List<Request> requests) {
        if (requests.isEmpty()) throw new IllegalArgumentException("пустой пакет");
        this.id = id; this.sourceId = sourceId;
        snapshot = List.copyOf(requests);
        waiting = new ArrayDeque<>(requests);
        outstanding = new LinkedHashSet<>(requests);
    }
    List<Request> snapshot() { return snapshot; }
    List<Request> waiting() { return List.copyOf(waiting); }
    int outstandingCount() { return outstanding.size(); }
    boolean hasWaiting() { return !waiting.isEmpty(); }
    Request take() { return waiting.removeFirst(); }
    boolean contains(Request r) { return outstanding.contains(r); }
    void removedByRejection(Request r) { if (outstanding.remove(r)) waiting.remove(r); }
    void completed(Request r) { if (!outstanding.remove(r)) throw new IllegalStateException("заявка не из пакета"); }
    boolean finished() { return outstanding.isEmpty(); }
}

final class Worker {
    final int id;
    private Request current;
    double busySince = Double.NaN;
    double busyUntil = Double.NaN;
    double completedBusyTime = 0;
    int completedCount = 0;
    Worker(int id) { this.id = id; }
    boolean isFree() { return current == null; }
    Request request() { return current; }
    void startService(Request r, double t, double duration) {
        if (!isFree()) throw new IllegalStateException("прибор занят");
        r.started(t); current = r; busySince = t; busyUntil = t + duration;
    }
    Request complete(double t) {
        if (current == null || Math.abs(t - busyUntil) > 1e-7) throw new IllegalStateException("неверное завершение прибора");
        Request r = current;
        r.completed(t); completedBusyTime += t - busySince; completedCount++;
        current = null; busySince = Double.NaN; busyUntil = Double.NaN;
        return r;
    }
    double busyTimeAt(double t) { return completedBusyTime + (current == null ? 0 : Math.max(0, t - busySince)); }
}

final class DevicePool {
    private final List<Worker> workers = new ArrayList<>();
    private int pointer;
    DevicePool(int count) {
        if (count < 1) throw new IllegalArgumentException("workers < 1");
        for (int i = 1; i <= count; i++) workers.add(new Worker(i));
    }
    List<Worker> all() { return List.copyOf(workers); }
    boolean hasFreeWorker() { return workers.stream().anyMatch(Worker::isFree); }
    int pointerId() { return pointer + 1; }
    Worker findFreeRoundRobin() {
        for (int offset = 0; offset < workers.size(); offset++) {
            int index = (pointer + offset) % workers.size();
            Worker worker = workers.get(index);
            if (worker.isFree()) { pointer = (index + 1) % workers.size(); return worker; }
        }
        return null;
    }
    Worker byId(int id) { return workers.get(id - 1); }
}

final class Event {
    final double time;
    final long order;
    final EventType type;
    final Source source;
    final Worker worker;
    final Request request;
    Event(double time, long order, EventType type, Source source, Worker worker, Request request) {
        this.time = time; this.order = order; this.type = type;
        this.source = source; this.worker = worker; this.request = request;
    }
    String label() {
        return type == EventType.REQUEST_GENERATED ? "ГЕНЕРАЦИЯ И" + source.id :
            "ЗАВЕРШЕНИЕ П" + worker.id + " " + request.label();
    }
}

final class EventCalendar {
    private final PriorityQueue<Event> future = new PriorityQueue<>(Comparator
        .comparingDouble((Event e) -> e.time).thenComparingLong(e -> e.order));
    void add(Event e) { future.add(e); }
    Event next() { return future.remove(); }
    boolean isEmpty() { return future.isEmpty(); }
    List<Event> upcoming() {
        List<Event> sorted = new ArrayList<>(future);
        sorted.sort(Comparator.comparingDouble((Event e) -> e.time).thenComparingLong(e -> e.order));
        return sorted;
    }
}

final class Config {
    final int workers, bufferCapacity, requests;
    final double serviceMean;
    final long seed;
    final List<double[]> intervals;
    Config(int workers, int bufferCapacity, int requests, double serviceMean, long seed, List<double[]> intervals) {
        if (workers < 1 || bufferCapacity < 1 || requests < 1 || serviceMean <= 0 || intervals.isEmpty())
            throw new IllegalArgumentException("Некорректные параметры СМО");
        for (double[] x : intervals) if (x.length != 2 || x[0] <= 0 || x[1] < x[0])
            throw new IllegalArgumentException("Некорректный интервал источника");
        this.workers = workers; this.bufferCapacity = bufferCapacity;
        this.requests = requests; this.serviceMean = serviceMean; this.seed = seed;
        this.intervals = intervals;
    }
    static Config defaults() { return new Config(2, 4, 18, 4.0, 42,
        List.of(new double[]{0.7, 1.2}, new double[]{0.9, 1.5}, new double[]{1.0, 1.7})); }
}
