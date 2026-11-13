import java.util.*;

final class SimulationEngine {
    enum ActionType { EVICT, BUFFER_ADD, FORM_PACKET, SELECT_REQUEST, SEND_TO_WORKER }
    private static final class Action {
        final ActionType type;
        final Request request;
        Action(ActionType type, Request request) { this.type = type; this.request = request; }
    }
    private final Config config;
    private final Random random;
    private final List<Source> sources;
    private final Buffer buffer;
    private final DevicePool devices;
    private final PlacementDispatcher placement;
    private final SelectionDispatcher selection;
    private final Statistics statistics;
    private final EventCalendar calendar = new EventCalendar();
    private final Deque<Action> immediate = new ArrayDeque<>();
    private long nextRequestId = 1, nextEventOrder = 1, nextPacketId = 1;
    private int reservedGenerations;
    private long stepCount;
    private double now;
    private Packet activePacket;
    private String lastDescription = "Система инициализирована";
    private String lastEventCode = "INIT";
    private boolean formQueued, selectQueued;

    SimulationEngine(Config config) {
        this.config = config; random = new Random(config.seed);
        sources = new ArrayList<>();
        for (int i = 0; i < config.intervals.size(); i++) {
            double[] bounds = config.intervals.get(i);
            sources.add(new Source(i + 1, bounds[0], bounds[1]));
        }
        buffer = new Buffer(config.bufferCapacity);
        devices = new DevicePool(config.workers);
        statistics = new Statistics(sources.size());
        placement = new PlacementDispatcher(buffer, statistics);
        selection = new SelectionDispatcher(buffer, devices, statistics);
        for (Source s : sources) scheduleGeneration(s, 0);
    }
    Config config() { return config; }
    double time() { return now; }
    long steps() { return stepCount; }
    String lastDescription() { return lastDescription; }
    String lastEventCode() { return lastEventCode; }
    List<Source> sources() { return List.copyOf(sources); }
    Buffer buffer() { return buffer; }
    DevicePool devices() { return devices; }
    Statistics statistics() { return statistics; }
    Packet packet() { return activePacket; }
    List<Event> future() { return calendar.upcoming(); }
    List<String> immediateActions() {
        List<String> result = new ArrayList<>();
        for (Action action : immediate) result.add(action.type + (action.request == null ? "" : ":" + action.request.label()));
        return result;
    }
    boolean isDone() { return immediate.isEmpty() && calendar.isEmpty(); }

    private void scheduleGeneration(Source s, double fromTime) {
        if (reservedGenerations >= config.requests) { s.nextEventAt = Double.NaN; return; }
        double when = fromTime + s.nextInterval(random);
        calendar.add(new Event(when, nextEventOrder++, EventType.REQUEST_GENERATED, s, null, null));
        s.nextEventAt = when;
        reservedGenerations++;
    }
    private void scheduleCompletion(Worker w, Request r) {
        calendar.add(new Event(w.busyUntil, nextEventOrder++, EventType.SERVICE_COMPLETED, null, w, r));
    }
    // Триггер: поступление в пустую обслуживаемую очередь либо освобождение прибора.
    // Диспетчер выбора никогда не вызывается беспричинно каждый тик.
    private void triggerSelection() {
        if (!devices.hasFreeWorker()) return;
        if (activePacket == null) {
            if (!buffer.isEmpty() && !formQueued) {
                immediate.add(new Action(ActionType.FORM_PACKET, null)); formQueued = true;
            }
        } else if (activePacket.hasWaiting() && !selectQueued) {
            immediate.add(new Action(ActionType.SELECT_REQUEST, null)); selectQueued = true;
        }
    }
    private void packetMaybeClosed() {
        if (activePacket != null && activePacket.finished()) {
            activePacket = null;
            triggerSelection();
        }
    }
    boolean step() {
        if (isDone()) return false;
        if (!immediate.isEmpty()) {
            Action a = immediate.removeFirst();
            executeAction(a);
        } else {
            Event e = calendar.next();
            if (e.time + 1e-10 < now) throw new IllegalStateException("время пошло назад");
            now = e.time;
            executeEvent(e);
        }
        stepCount++;
        verifyInvariants();
        return true;
    }
    private void executeEvent(Event e) {
        if (e.type == EventType.REQUEST_GENERATED) {
            Source s = e.source;
            Request r = s.generate(nextRequestId++, now);
            statistics.recordGenerated(r);
            s.nextEventAt = Double.NaN;
            scheduleGeneration(s, now);
            if (placement.isFull()) {
                Request victim = placement.selectRejectionCandidate();
                immediate.add(new Action(ActionType.EVICT, victim));
                immediate.add(new Action(ActionType.BUFFER_ADD, r));
            } else {
                immediate.add(new Action(ActionType.BUFFER_ADD, r));
            }
            lastEventCode = "REQUEST_GENERATED";
            lastDescription = "Источник И" + s.id + " сгенерировал " + r.label() + ". Триггер диспетчера постановки";
        } else {
            Worker w = e.worker;
            Request finished = w.complete(now);
            if (finished != e.request) throw new IllegalStateException("ошибка события завершения");
            statistics.recordCompleted(finished);
            String additional = "";
            if (activePacket != null && activePacket.contains(finished)) {
                activePacket.completed(finished);
                if (activePacket.finished()) additional = "; пакет #" + activePacket.id + " полностью завершён";
            }
            packetMaybeClosed();
            triggerSelection();
            lastEventCode = "SERVICE_COMPLETED";
            lastDescription = "Прибор П" + w.id + " закончил " + finished.label()
                + " (обслуживание " + fmt(now - finished.serviceStartedAt) + ")" + additional
                + ". Триггер диспетчера выбора";
        }
    }
    private void executeAction(Action a) {
        switch (a.type) {
            case EVICT -> {
                placement.rejectFromBuffer(a.request, now);
                if (activePacket != null && activePacket.contains(a.request)) {
                    activePacket.removedByRejection(a.request);
                    if (activePacket.finished()) activePacket = null;
                }
                lastEventCode = "BUFFER_EVICTED";
                lastDescription = "Д1ОО2: выбита " + a.request.label() + " (И" + a.request.sourceId
                    + ", самый низкий приоритет в БП; при равенстве самая старая). Отказ +1";
            }
            case BUFFER_ADD -> {
                int slot = placement.enqueue(a.request, now);
                lastEventCode = "BUFFER_ADDED";
                lastDescription = "Д1ОЗ2: " + a.request.label() + " помещена в конец буфера, место " + slot;
                triggerSelection();
            }
            case FORM_PACKET -> {
                formQueued = false;
                if (activePacket != null || buffer.isEmpty() || !devices.hasFreeWorker())
                    throw new IllegalStateException("ошибка формирования пакета");
                activePacket = selection.createPacket(nextPacketId++);
                lastEventCode = "PACKET_CREATED";
                lastDescription = "Д2Б5: сформирован НЕРАСШИРЯЕМЫЙ пакет #" + activePacket.id + " от И"
                    + activePacket.sourceId + " с заявками " + labels(activePacket.snapshot());
                triggerSelection();
            }
            case SELECT_REQUEST -> {
                selectQueued = false;
                if (activePacket == null || !activePacket.hasWaiting() || !devices.hasFreeWorker())
                    throw new IllegalStateException("нельзя выбрать заявку");
                Request r = selection.chooseFromPacket(activePacket);
                lastEventCode = "REQUEST_SELECTED";
                lastDescription = "Д2Б5: " + r.label() + " выбрана из фиксированного пакета #"
                    + activePacket.id + ", удалена из буфера";
                immediate.addFirst(new Action(ActionType.SEND_TO_WORKER, r));
            }
            case SEND_TO_WORKER -> {
                Worker w = selection.selectWorker();
                if (w == null) throw new IllegalStateException("нет свободного прибора");
                double duration = selection.startService(w, a.request, now, config.serviceMean, random);
                scheduleCompletion(w, a.request);
                lastEventCode = "SENT_TO_WORKER";
                lastDescription = "Д2П2: " + a.request.label() + " передана П" + w.id
                    + ", обслуживание ПЗ1 = " + fmt(duration) + "; указатель теперь П" + devices.pointerId();
                triggerSelection();
            }
        }
    }
    private static String labels(List<Request> r) {
        StringJoiner j = new StringJoiner(", ", "[", "]");
        for (Request x : r) j.add(x.label());
        return j.toString();
    }
    static String fmt(double v) { return String.format(Locale.US, "%.3f", v); }

    void verifyInvariants() {
        Set<Request> buffered = new HashSet<>(buffer.all());
        if (buffer.size() > buffer.capacity() || buffered.size() != buffer.size())
            throw new IllegalStateException("неверное состояние буфера");
        int inService = 0;
        for (Worker w : devices.all()) {
            if (!w.isFree()) {
                inService++;
                if (buffered.contains(w.request()) || w.request().status != RequestStatus.IN_SERVICE)
                    throw new IllegalStateException("одна заявка в двух местах");
                if (activePacket == null || !activePacket.contains(w.request()))
                    throw new IllegalStateException("прибор обслуживает не текущий пакет");
            }
        }
        for (Request r : buffered) if (r.status != RequestStatus.BUFFERED)
            throw new IllegalStateException("неверный статус в буфере");
        if (activePacket != null) {
            for (Request r : activePacket.waiting()) {
                if (!buffered.contains(r)) throw new IllegalStateException("ожидающая заявка пакета не в буфере");
            }
        }
        if (isDone()) {
            if (!buffer.isEmpty() || inService != 0 || activePacket != null || statistics.generatedTotal() != config.requests
                || statistics.generatedTotal() != statistics.completedTotal() + statistics.rejectedTotal())
                throw new IllegalStateException("потеря заявок при завершении симуляции");
        }
    }
}
