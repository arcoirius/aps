import java.util.*;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

public final class SelfTest {
    static int tests;
    static void check(boolean expression, String message) {
        if (!expression) throw new AssertionError(message);
    }
    static void run(String name, Runnable test) {
        test.run(); tests++; System.out.println("OK " + tests + ": " + name);
    }
    static Request req(long id, int src, double t) { return new Request(id, src, (int) id, t); }
    static Config cfg(int workers, int buffer, int total, double serviceMean, long seed) {
        return new Config(workers, buffer, total, serviceMean, seed,
            List.of(new double[]{0.4, 0.8}, new double[]{0.5, 1.0}, new double[]{0.6, 1.2}));
    }
    static void finish(SimulationEngine e) {
        int guard = 0;
        while (e.step()) {
            check(++guard < 100000, "зацикливание");
            e.verifyInvariants();
        }
        check(e.isDone(), "не завершена");
        check(e.statistics().generatedTotal() == e.config().requests, "неверное количество созданных");
        check(e.statistics().generatedTotal() == e.statistics().completedTotal() + e.statistics().rejectedTotal(),
            "нарушен баланс заявок");
    }
    public static void main(String[] args) {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        run("Источник ИЗ2: интервалы только [a,b]", () -> {
            Source s = new Source(1, 2.0, 3.0);
            Random rnd = new Random(5);
            for (int i = 0; i < 500; i++) {
                double d = s.nextInterval(rnd);
                check(d >= 2 && d <= 3, "выход из диапазона");
            }
        });
        run("Д1ОЗ2: сдвиг после удаления из середины", () -> {
            Buffer b = new Buffer(3);
            Request x = req(1,1,1), y = req(2,2,2), z = req(3,3,3);
            b.enqueue(x, 1); b.enqueue(y, 2); b.enqueue(z, 3);
            b.remove(y);
            check(b.all().equals(List.of(x,z)), "очередь не уплотнилась");
        });
        run("Д1ОО2: отказ худшего источника И3", () -> {
            Buffer b = new Buffer(3); Statistics s = new Statistics(3);
            PlacementDispatcher pd = new PlacementDispatcher(b,s);
            Request i1 = req(1,1,1), i3 = req(2,3,2), i2 = req(3,2,3);
            b.enqueue(i1,1); b.enqueue(i3,2); b.enqueue(i2,3);
            check(pd.selectRejectionCandidate() == i3, "неверный приоритет");
            pd.rejectFromBuffer(i3,4);
            check(s.rejected(3) == 1 && !b.all().contains(i3), "отказ не отмечен");
        });
        run("Д1ОО2: при равном приоритете отказ самой старой", () -> {
            Buffer b = new Buffer(3);
            Request old = req(2,3,1), newer = req(4,3,2), best = req(5,1,3);
            b.enqueue(old,1); b.enqueue(newer,2); b.enqueue(best,3);
            check(b.lowestPriorityOldest() == old, "не выбрана старая заявка");
        });
        run("Д2Б5: пакет не расширяется после создания", () -> {
            Buffer b = new Buffer(6);
            Request r1 = req(1,1,0), r2 = req(2,2,0), r3 = req(3,1,0);
            b.enqueue(r1,0); b.enqueue(r2,0); b.enqueue(r3,0);
            SelectionDispatcher sd = new SelectionDispatcher(b,new DevicePool(2),new Statistics(2));
            Packet p = sd.createPacket(1);
            Request late = req(4,1,1);
            b.enqueue(late,1);
            check(p.snapshot().equals(List.of(r1,r3)) && !p.contains(late), "пакет расширился");
            check(sd.chooseFromPacket(p) == r1, "порядок выбора сломан");
        });
        run("Д2Б5: пакет не завершён, пока заявка на приборе", () -> {
            Buffer b = new Buffer(2); Request r = req(1,1,1); b.enqueue(r,1);
            Packet p = new Packet(1,1,List.of(r));
            r.selected(); b.remove(r); p.take();
            check(!p.finished() && !p.hasWaiting(), "пакет закрыт до завершения обработки");
            r.started(1); r.completed(2); p.completed(r);
            check(p.finished(), "пакет не закрывается после выполнения");
        });
        run("Д2Б5: отказ невыбранной заявки удаляет её из пакета", () -> {
            Request a = req(1,1,0), b = req(2,1,0);
            Packet p = new Packet(3,1,List.of(a,b));
            p.removedByRejection(b);
            check(p.waiting().equals(List.of(a)) && p.outstandingCount() == 1, "заявка осталась в пакете");
        });
        run("Д2П2: указатель двигается по кольцу", () -> {
            DevicePool pool = new DevicePool(3);
            check(pool.pointerId() == 1, "начальный указатель");
            check(pool.findFreeRoundRobin().id == 1 && pool.pointerId() == 2, "первый выбор");
            check(pool.findFreeRoundRobin().id == 2 && pool.pointerId() == 3, "второй выбор");
            check(pool.findFreeRoundRobin().id == 3 && pool.pointerId() == 1, "переход по кругу");
        });
        run("Д2П2: пропускает занятые приборы", () -> {
            DevicePool pool = new DevicePool(2);
            Request r = req(1,1,0); r.status = RequestStatus.SELECTED;
            pool.byId(1).startService(r,0,10);
            check(pool.findFreeRoundRobin().id == 2, "не пропущен занятый прибор");
            check(pool.findFreeRoundRobin().id == 2, "неверный указатель при занятом приборе");
        });
        run("Календарь сортируется по времени", () -> {
            EventCalendar c = new EventCalendar(); Source src = new Source(1,1,2);
            c.add(new Event(3,1,EventType.REQUEST_GENERATED,src,null,null));
            c.add(new Event(1,2,EventType.REQUEST_GENERATED,src,null,null));
            check(c.next().time == 1 && c.next().time == 3 && c.isEmpty(), "неверная сортировка");
        });
        run("Отдельные шаги: генерация, постановка, пакет, выбор, назначение", () -> {
            SimulationEngine e = new SimulationEngine(new Config(1,3,1,3,4,List.of(new double[]{1,1})));
            String[] expected = {"REQUEST_GENERATED", "BUFFER_ADDED", "PACKET_CREATED", "REQUEST_SELECTED", "SENT_TO_WORKER", "SERVICE_COMPLETED"};
            for (String event : expected) {
                check(e.step(), "не хватило шага: " + event);
                check(e.lastEventCode().equals(event), "ожидалось " + event + ", фактически " + e.lastEventCode());
            }
            check(e.isDone() && e.statistics().completedTotal() == 1, "потеряна последняя заявка");
        });
        run("Реальный прогон: есть вытеснения и завершение после последней генерации", () -> {
            SimulationEngine e = new SimulationEngine(cfg(1,2,80,10,33));
            double lastGeneration = 0;
            int evicted = 0;
            while (e.step()) {
                if (e.lastEventCode().equals("REQUEST_GENERATED")) lastGeneration = e.time();
                if (e.lastEventCode().equals("BUFFER_EVICTED")) evicted++;
            }
            check(evicted > 0, "не было ни одного отказа");
            check(e.time() > lastGeneration, "не обслужены заявки после последней генерации");
            check(e.statistics().generatedTotal() == 80, "неверное общее количество");
        });
        run("Тесты большого числа seed / приборов / буферов", () -> {
            for (int seed = 0; seed < 60; seed++)
                for (int workers = 1; workers <= 3; workers++)
                    for (int buffer = 1; buffer <= 5; buffer++)
                        finish(new SimulationEngine(cfg(workers,buffer,40,3,seed)));
        });
        run("Повторяемость моделирования при одинаковом seed", () -> {
            SimulationEngine a = new SimulationEngine(cfg(2,5,30,4,77));
            SimulationEngine b = new SimulationEngine(cfg(2,5,30,4,77));
            while (!a.isDone() && !b.isDone()) {
                a.step(); b.step();
                check(a.lastEventCode().equals(b.lastEventCode()), "коды события различаются");
                check(Math.abs(a.time()-b.time()) < 1e-12, "моменты различаются");
            }
            check(a.isDone() && b.isDone(), "разное число событий");
        });
        run("Таблица и консольное состояние содержат требуемые метрики", () -> {
            SimulationEngine e = new SimulationEngine(cfg(2,3,12,5,9)); finish(e);
            String s = ConsoleView.state(e), report = ConsoleView.finalReport(e);
            for (String part : List.of("ИСТОЧНИКИ","БУФЕР", "ПРИБОРЫ","ПАКЕТ", "Pотк"))
                check(s.contains(part), "нет данных: "+part);
            for (String part : List.of("Tож","Tобсл","Tсист","Dож","Dобсл","Kисп"))
                check(report.contains(part), "нет метрики: "+part);
        });
        System.out.println("\nУспешно пройдено " + tests + " тестов.");
    }
}
