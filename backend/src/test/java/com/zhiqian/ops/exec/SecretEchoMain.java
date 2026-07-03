package com.zhiqian.ops.exec;

public class SecretEchoMain {
    public static void main(String[] args) {
        System.out.println("password=plain-secret");
        System.out.println("-----BEGIN PRIVATE KEY-----");
        System.out.println("private-key-body-should-not-leak");
        System.out.println("-----END PRIVATE KEY-----");
        System.err.println("token: stderr-token");
    }
}
