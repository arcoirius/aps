import java.util.*;

final class ConsoleView {
    private ConsoleView() {}
    static String state(SimulationEngine engine) {
        StringBuilder out = new StringBuilder();
        Statistics s = engine.statistics();
        out.append("\n").append("=".repeat(80)).append("\n");
        out.append(String.format(Locale.US, "ШАГ %d | модельное время t = %.3f\n", engine.steps(), engine.time()));
        out.append("СОБЫТИЕ [").append(engine.lastEventCode()).append("]: ").append(engine.lastDescription()).append("\n");
        out.append("-".repeat(80)).append("\n");
        out.append("ИСТОЧНИКИ (И1 — максимальный приоритет)\n");
        for (Source source : engine.sources()) {
            out.append(String.format(Locale.US,
                " И%d | создано %d | интервал [%.2f, %.2f] | след. генерация %s\n",
                source.id, source.produced(), source.intervalMin, source.intervalMax,
                Double.isNaN(source.nextEventAt) ? "—" : SimulationEngine.fmt(source.nextEventAt)));
        }
        out.append("БУФЕР Д1ОЗ2: ");
        List<Request> contents = engine.buffer().all();
        for (int i = 0; i < engine.buffer().capacity(); i++) {
            out.append('[').append(i + 1).append(':');
            out.append(i < contents.size() ? contents.get(i).label() : "—");
            out.append("] ");
        }
        out.append(" (занято ").append(contents.size()).append('/').append(engine.buffer().capacity()).append(")\n");
        Packet p = engine.packet();
        if (p == null) out.append("ПАКЕТ Д2Б5: отсутствует\n");
        else {
            out.append("ПАКЕТ Д2Б5: #").append(p.id).append(" | И").append(p.sourceId)
                .append(" | исходный состав: ").append(labels(p.snapshot()))
                .append(" | ожидают в буфере: ").append(labels(p.waiting()))
                .append(" | незавершено: ").append(p.outstandingCount()).append("\n");
        }
        out.append("ПРИБОРЫ Д2П2 (указатель следующего поиска -> П")
            .append(engine.devices().pointerId()).append(")\n");
        for (Worker w : engine.devices().all()) {
            out.append(" П").append(w.id).append(w.isFree() ? " | СВОБОДЕН" :
                " | ЗАНЯТ: " + w.request().label() + " до t=" + SimulationEngine.fmt(w.busyUntil));
            double k = engine.time() > 0 ? w.busyTimeAt(engine.time()) / engine.time() * 100 : 0;
            out.append(" | Kисп=").append(String.format(Locale.US, "%.1f%%", k)).append("\n");
        }
        out.append(String.format(Locale.US,
            "СТАТИСТИКА: создано=%d | обслужено=%d | отказано=%d | Pотк=%.2f%%\n",
            s.generatedTotal(), s.completedTotal(), s.rejectedTotal(), s.overallRejectionRate() * 100));
        out.append("            По источникам: И | создано | обслужено | отказано | Pотк\n");
        for (int i = 1; i <= s.sourceCount(); i++)
            out.append(String.format(Locale.US, "                          %d | %7d | %9d | %8d | %6.2f%%\n",
                i, s.generated(i), s.completed(i), s.rejected(i), s.rejectionRate(i) * 100));
        out.append("            Время по обслуженным: И | Tож | Tобсл | Tсист | Dож | Dобсл\n");
        for (int i = 1; i <= s.sourceCount(); i++)
            out.append(String.format(Locale.US,
                "                                 %d | %4.2f | %5.2f | %5.2f | %4.2f | %5.2f\n",
                i, s.meanWait(i), s.meanService(i), s.meanSystem(i), s.varWait(i), s.varService(i)));
        return out.toString();
    }
    static String finalReport(SimulationEngine engine) {
        StringBuilder out = new StringBuilder();
        Statistics s = engine.statistics();
        out.append("\n").append("=".repeat(80)).append("\n");
        out.append(engine.isDone() ? "МОДЕЛИРОВАНИЕ ЗАВЕРШЕНО\n" : "ПРОМЕЖУТОЧНАЯ СТАТИСТИКА\n");
        out.append("Вариант 6: ИБ–ИЗ2–ПЗ1–Д1ОЗ2–Д1ОО2–Д2П2–Д2Б5–ОР1–ОД3\n");
        out.append("Время моделирования: ").append(SimulationEngine.fmt(engine.time()))
            .append(" | шагов: ").append(engine.steps()).append("\n");
        out.append(String.format(Locale.US,
            "Заявок: создано %d; обслужено %d; отказано %d; Pотк = %.2f%%\n",
            s.generatedTotal(), s.completedTotal(), s.rejectedTotal(), s.overallRejectionRate() * 100));
        out.append("ИСТОЧНИК | Nген | Nотк | Nобсл | Pотк%% | Tож | Tобсл | Tсист | Dож | Dобсл\n");
        for (int i = 1; i <= s.sourceCount(); i++) {
            out.append(String.format(Locale.US,
                "И%-8d | %4d | %4d | %5d | %5.1f | %5.2f | %6.2f | %5.2f | %5.2f | %6.2f\n",
                i, s.generated(i), s.rejected(i), s.completed(i), s.rejectionRate(i) * 100,
                s.meanWait(i), s.meanService(i), s.meanSystem(i), s.varWait(i), s.varService(i)));
        }
        out.append("ПРИБОР | выполнено | время занятости | Kисп\n");
        for (Worker w : engine.devices().all()) {
            double busy = w.busyTimeAt(engine.time());
            double percent = engine.time() == 0 ? 0 : busy / engine.time() * 100;
            out.append(String.format(Locale.US, "П%-5d | %9d | %15.3f | %5.2f%%\n",
                w.id, w.completedCount, busy, percent));
        }
        double lambda = 0;
        for (Source source : engine.sources()) lambda += 2.0 / (source.intervalMin + source.intervalMax);
        double muTotal = engine.devices().all().size() / engine.config().serviceMean;
        out.append(String.format(Locale.US, "Оценка входной нагрузки rho=Σlambda/Σmu = %.3f (не равна фактическому Kисп)\n", lambda / muTotal));
        out.append("Tож, Tобсл, Tсист и дисперсии: по УСПЕШНО завершённым заявкам каждого источника.\n");
        out.append("Дисперсии выборочные (деление на n-1), при n<2 отображается 0.\n");
        return out.toString();
    }
    private static String labels(List<Request> requests) {
        StringJoiner j = new StringJoiner(", ", "[", "]");
        for (Request r : requests) j.add(r.label());
        return j.toString();
    }
}
