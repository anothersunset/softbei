package com.zhiqian.ops.exec;

public class SecretEchoMain {
    public static void main(String[] args) {
        System.out.println("password=plain-secret");
        System.err.println("token: stderr-token");
    }
}
