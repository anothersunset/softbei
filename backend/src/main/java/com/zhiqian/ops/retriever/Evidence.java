package com.zhiqian.ops.retriever;

/**
 * 一条可引用的运维知识依据（用于让模型「有证据地操作」并支持溯源展示）。
 * kind: doc(文档/runbook) | rule(安全规则) | history(历史trace)。
 */
public record Evidence(String id, String kind, String source, String title, String snippet, double score) {}
