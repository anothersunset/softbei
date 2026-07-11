package com.zhiqian.ops.exec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 测试专用：模拟真实 {@code tee} 行为——读取全部 stdin，原样回显到 stdout，
 * 同时写入 args[0] 指定的文件。用于证明"管道右侧确实执行了真正的写入"，
 * 而不是被当成字面参数传给管道左侧命令、写入从未发生。
 */
public class TeeLikeMain {
    public static void main(String[] args) throws IOException {
        byte[] all = System.in.readAllBytes();
        System.out.write(all);
        System.out.flush();
        if (args.length > 0) {
            Files.write(Path.of(args[0]), all);
        }
    }
}
