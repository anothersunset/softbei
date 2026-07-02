package com.zhiqian.ops.exec;

public class ExecutorFloodMain {
    public static void main(String[] args) {
        int lines = args.length == 0 ? 1000 : Integer.parseInt(args[0]);
        for (int i = 0; i < lines; i++) {
            System.out.println("OUT-" + i + " " + "x".repeat(80));
            System.err.println("ERR-" + i + " " + "y".repeat(80));
        }
    }
}
