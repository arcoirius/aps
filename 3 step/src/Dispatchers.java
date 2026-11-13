import java.util.*;

final class PlacementDispatcher {
    private final Buffer buffer;
    private final Statistics statistics;
    PlacementDispatcher(Buffer buffer, Statistics statistics) {
        this.buffer = buffer; this.statistics = statistics;
    }
    boolean isFull() { return buffer.isFull(); }
    Request selectRejectionCandidate() { return buffer.lowestPriorityOldest(); }
    void rejectFromBuffer(Request victim, double now) {
        buffer.remove(victim);
        victim.rejected(now);
        statistics.recordRejected(victim);
    }
    int enqueue(Request request, double now) {
        buffer.enqueue(request, now);
        return buffer.size();
    }
}

final class SelectionDispatcher {
    private final Buffer buffer;
    private final DevicePool devices;
    private final Statistics statistics;
    SelectionDispatcher(Buffer buffer, DevicePool devices, Statistics statistics) {
        this.buffer = buffer; this.devices = devices; this.statistics = statistics;
    }
    Packet createPacket(long packetNumber) {
        int source = buffer.bestSource();
        return new Packet(packetNumber, source, buffer.bySource(source));
    }
    Request chooseFromPacket(Packet packet) {
        Request request = packet.take();
        buffer.remove(request);
        request.selected();
        return request;
    }
    Worker selectWorker() { return devices.findFreeRoundRobin(); }
    double startService(Worker worker, Request request, double time, double mean, Random random) {
        double duration = -mean * Math.log(1.0 - random.nextDouble()); // ПЗ1, Exp(mean)
        duration = Math.max(1e-9, duration);
        worker.startService(request, time, duration);
        statistics.recordServiceStart(request);
        return duration;
    }
}
