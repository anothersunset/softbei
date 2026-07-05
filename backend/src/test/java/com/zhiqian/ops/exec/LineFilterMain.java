package com.zhiqian.ops.exec;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class LineFilterMain {
    public static void main(String[] args) throws Exception {
        String needle = args.length == 0 ? "" : args[0];
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains(needle)) {
                    System.out.println(line);
                }
            }
        }
    }
}
