package com.zhiqian.ops.agent.tools;

/** 磁盘感知发现的单个文件级候选：绝对路径、大小（字节）、最后修改时间（epoch 秒）。 */
public record FileCandidate(String path, long sizeBytes, long mtimeEpochSeconds) {}
