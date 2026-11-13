import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class App {
    private App() {}
    private static void help() {
        System.out.println("Вариант 6: один Enter — один шаг; a — до конца; q — выход.");
        System.out.println("javac -encoding UTF-8 *.java && java App --workers 2 --buffer 4 --requests 18 --service-mean 4 --seed 42");
        System.out.println("Параметры: --sources 0.7:1.2,0.9:1.5,1:1.7 --output ../trace.txt --batch");
    }
    private static List<double[]> parseIntervals(String arg) {
        List<double[]> out = new ArrayList<>();
        for (String item : arg.split(",")) {
            String[] pair = item.trim().split(":");
            if (pair.length != 2) throw new IllegalArgumentException("интервалы: min:max,min:max");
            out.add(new double[]{Double.parseDouble(pair[0]), Double.parseDouble(pair[1])});
        }
        return out;
    }
    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        int workers = 2, buffer = 4, requests = 18;
        double serviceMean = 4.0;
        long seed = 42;
        String outputPath = null;
        boolean batch = false;
        List<double[]> intervals = Config.defaults().intervals;
        try {
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (arg.equals("--help")) { help(); return; }
                if (arg.equals("--batch")) { batch = true; continue; }
                if (i + 1 >= args.length) throw new IllegalArgumentException("Не указано значение после " + arg);
                String v = args[++i];
                switch (arg) {
                    case "--workers" -> workers = Integer.parseInt(v);
                    case "--buffer" -> buffer = Integer.parseInt(v);
                    case "--requests" -> requests = Integer.parseInt(v);
                    case "--service-mean" -> serviceMean = Double.parseDouble(v);
                    case "--seed" -> seed = Long.parseLong(v);
                    case "--sources" -> intervals = parseIntervals(v);
                    case "--output" -> outputPath = v;
                    default -> throw new IllegalArgumentException("Неизвестный параметр " + arg);
                }
            }
            Config config = new Config(workers, buffer, requests, serviceMean, seed, intervals);
            SimulationEngine sim = new SimulationEngine(config);
            Path path = outputPath == null ? null : Path.of(outputPath);
            if (path != null && path.toAbsolutePath().getParent() != null)
                Files.createDirectories(path.toAbsolutePath().getParent());
            try (PrintWriter log = path == null ? null : new PrintWriter(
                     Files.newBufferedWriter(path, StandardCharsets.UTF_8))) {
                System.out.println("СМО: вариант 6. Enter — шаг, a — до конца, q — выход.");
                if (path != null) System.out.println("Запись шагов в " + path.toAbsolutePath());
                emit(ConsoleView.state(sim), log);
                Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8);
                while (!sim.isDone()) {
                    if (!batch) {
                        System.out.print("\nКоманда [Enter/a/q]: ");
                        if (!scanner.hasNextLine()) break;
                        String command = scanner.nextLine().trim().toLowerCase(Locale.ROOT);
                        if (command.equals("q")) break;
                        if (command.equals("a")) batch = true;
                        else if (!command.isEmpty()) { System.out.println("Неизвестная команда: " + command); continue; }
                    }
                    sim.step();
                    emit(ConsoleView.state(sim), log);
                }
                emit(ConsoleView.finalReport(sim), log);
            }
        } catch (IllegalArgumentException ex) {
            System.err.println("Ошибка параметров: " + ex.getMessage()); help();
            System.exit(2);
        }
    }
    private static void emit(String text, PrintWriter log) {
        System.out.print(text);
        if (log != null) { log.print(text); log.flush(); }
    }
}
