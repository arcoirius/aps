import java.util.*;

final class Statistics {
    private final int[] generated, rejected, started, completed;
    private final double[] sumWait, sumWait2, sumService, sumService2, sumSystem;
    Statistics(int sources) {
        int n = sources + 1;
        generated = new int[n]; rejected = new int[n]; started = new int[n]; completed = new int[n];
        sumWait = new double[n]; sumWait2 = new double[n]; sumService = new double[n];
        sumService2 = new double[n]; sumSystem = new double[n];
    }
    void recordGenerated(Request r) { generated[r.sourceId]++; }
    void recordRejected(Request r) { rejected[r.sourceId]++; }
    void recordServiceStart(Request r) { started[r.sourceId]++; }
    void recordCompleted(Request r) {
        int s = r.sourceId; completed[s]++;
        double wait = r.serviceStartedAt - r.createdAt;
        double service = r.serviceFinishedAt - r.serviceStartedAt;
        double system = r.serviceFinishedAt - r.createdAt;
        sumWait[s] += wait; sumWait2[s] += wait * wait;
        sumService[s] += service; sumService2[s] += service * service;
        sumSystem[s] += system;
    }
    int sourceCount() { return generated.length - 1; }
    int generated(int s) { return generated[s]; }
    int rejected(int s) { return rejected[s]; }
    int completed(int s) { return completed[s]; }
    int started(int s) { return started[s]; }
    int generatedTotal() { return sum(generated); }
    int rejectedTotal() { return sum(rejected); }
    int completedTotal() { return sum(completed); }
    private int sum(int[] values) { int result = 0; for (int i = 1; i < values.length; i++) result += values[i]; return result; }
    double rejectionRate(int s) { return generated[s] == 0 ? 0 : (double) rejected[s] / generated[s]; }
    double overallRejectionRate() { return generatedTotal() == 0 ? 0 : (double) rejectedTotal() / generatedTotal(); }
    double meanWait(int s) { return mean(sumWait[s], completed[s]); }
    double meanService(int s) { return mean(sumService[s], completed[s]); }
    double meanSystem(int s) { return mean(sumSystem[s], completed[s]); }
    double varWait(int s) { return variance(sumWait[s], sumWait2[s], completed[s]); }
    double varService(int s) { return variance(sumService[s], sumService2[s], completed[s]); }
    private double mean(double v, int n) { return n == 0 ? 0 : v / n; }
    private double variance(double sum, double sumSquare, int n) {
        return n < 2 ? 0 : Math.max(0, (sumSquare - sum * sum / n) / (n - 1));
    }
}
