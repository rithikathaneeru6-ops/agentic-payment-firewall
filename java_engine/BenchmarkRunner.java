package com.razorpay.apf.benchmark;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Entry point: generates the 2,000-transaction synthetic benchmark, runs the
 * baseline risk scorer, writes benchmark_dataset.csv + benchmark_metrics.json,
 * and prints a human-readable report with amounts in Indian Rupee (lakh/crore)
 * formatting.
 *
 * Run:
 *   mvn package && java -jar target/apf-benchmark.jar
 * or, without Maven:
 *   javac -d out $(find src -name "*.java") && java -cp out com.razorpay.apf.benchmark.BenchmarkRunner
 */
public class BenchmarkRunner {

    public static void main(String[] args) throws IOException {
        long seed = 42L;
        DatasetGenerator generator = new DatasetGenerator(seed);
        List<Transaction> dataset = generator.buildDataset();

        RiskScorer scorer = new RiskScorer();
        Evaluator evaluator = new Evaluator(scorer, /* fpCostPerBlockedCase */ 75.0,
                /* challengeFrictionCost */ 15.0);

        List<String[]> rows = new ArrayList<>();
        Evaluator.Metrics metrics = evaluator.evaluate(dataset, rows);

        writeCsv("../data/benchmark_dataset.csv", dataset, rows);
        writeText("../data/benchmark_metrics.json", metrics.toJson());

        System.out.println(metrics.toReport());
        System.out.println();
        System.out.println("Wrote ../data/benchmark_dataset.csv and ../data/benchmark_metrics.json");
    }

    private static void writeCsv(String path, List<Transaction> dataset, List<String[]> rows) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(Paths.get(path), StandardCharsets.UTF_8)) {
            w.write(Transaction.csvHeader() + ",risk_score,predicted_action,reason");
            w.newLine();
            for (int i = 0; i < dataset.size(); i++) {
                Transaction t = dataset.get(i);
                String[] r = rows.get(i);
                w.write(t.toCsvRow() + "," + q(r[9]) + "," + q(r[10]) + "," + q(r[11]));
                w.newLine();
            }
        }
    }

    private static String q(String s) {
        return "\"" + (s == null ? "" : s.replace("\"", "\"\"")) + "\"";
    }

    private static void writeText(String path, String content) throws IOException {
        Files.writeString(Paths.get(path), content, StandardCharsets.UTF_8);
    }
}
