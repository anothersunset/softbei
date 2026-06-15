package com.zhiqian.ops.exec;

import java.util.List;

/**
 * 执行请求。argv 为已拆分的参数数组（绝不走 shell，避免注入）。
 */
public record ExecRequest(List<String> argv, boolean readOnly, Integer timeoutSeconds) {}
