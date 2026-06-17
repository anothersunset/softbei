/*
 * 实时思维链（Live Chain-of-Thought）客户端
 * 通过 EventSource 订阅 GET /api/ops/chat/stream，实时渲染安全护栏八阶段的逐步执行（类 ChatGPT tool_call 展示）。
 * 不依赖也不修改 app.js；仅操作本面板自己的 DOM 节点。
 */
(function () {
  var STAGE_LABEL = {
    RECEIVE: '接收指令',
    INJECTION_GUARD: '抗提示词注入',
    SENSE: '环境感知',
    RETRIEVE: '知识检索',
    REASON: '推理决策',
    GUARD: '安全校验',
    EXECUTE: '最小权限执行',
    ANALYZE: '根因分析'
  };
  var STATUS_LABEL = {
    INJECTION_BLOCKED: '已拦截注入',
    BLOCKED: '已拒绝执行',
    REVIEW_PENDING: '待人工确认',
    EXECUTED: '已闭环执行'
  };
  var STATUS_CLASS = {
    INJECTION_BLOCKED: 'block',
    BLOCKED: 'block',
    REVIEW_PENDING: 'review',
    EXECUTED: 'safe'
  };

  var es = null;
  var idx = 0;

  function $(id) { return document.getElementById(id); }

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  function setBtn(running) {
    var b = $('streamBtn');
    if (b) b.disabled = running;
    var l = $('streamLoader');
    if (l) l.classList.toggle('on', running);
  }

  function summarizeOutput(stage, output) {
    if (!output || typeof output !== 'object') return '';
    try {
      if (stage === 'SENSE') return '感知工具 ' + (output.toolsRun != null ? output.toolsRun : '?') + ' 个';
      if (stage === 'RETRIEVE') return '检索依据 ' + (output.count != null ? output.count : 0) + ' 条' + (output.degraded ? '（降级）' : '');
      if (stage === 'INJECTION_GUARD') return output.blocked ? '命中注入特征，已拦截' : '未检出注入';
      if (stage === 'REASON') return (output.summary ? esc(output.summary) : '生成计划') + '（步骤 ' + ((output.steps && output.steps.length) || 0) + '）';
      if (stage === 'GUARD') return '裁决 ' + ((output.decisions && output.decisions.length) || 0) + ' 条，最高风险 ' + esc(output.worstLevel);
      if (stage === 'EXECUTE') return '执行 ' + (output.executedCount != null ? output.executedCount : 0) + ' 条指令';
      if (stage === 'ANALYZE') return output.analysis ? esc(String(output.analysis).slice(0, 80)) : '输出根因分析';
    } catch (e) { /* ignore */ }
    return '';
  }

  function appendStep(step) {
    var box = $('streamBox');
    if (!box) return;
    box.style.display = '';
    idx += 1;
    var label = STAGE_LABEL[step.stage] || step.stage || ('步骤 ' + idx);
    var detail = summarizeOutput(step.stage, step.output);
    var meta = [];
    meta.push('<span><b>' + idx + '</b> 阶段</span>');
    if (step.agent) meta.push('<span>' + esc(step.agent) + '</span>');
    if (step.model) meta.push('<span>模型 <b>' + esc(step.model) + '</b></span>');
    if (step.confidence != null) meta.push('<span>置信 <b>' + esc(step.confidence) + '</b></span>');
    if (step.elapsedMs != null) meta.push('<span>耗时 <b>' + esc(step.elapsedMs) + 'ms</b></span>');
    var halt = step.output && step.output._halt;
    var stCls = halt ? 'st-halt' : 'st-ok';
    var stTxt = halt ? '已拦截' : (step.status || 'ok');
    var html = '<div class="it">'
      + '<div class="nd">' + idx + '</div>'
      + '<div class="mn">'
      + '<div class="hd">' + esc(label) + ' · <span class="' + stCls + '">' + esc(stTxt) + '</span></div>'
      + (detail ? '<div style="font-size:12px;color:var(--dim);margin-top:5px">' + detail + '</div>' : '')
      + '<div class="mt">' + meta.join('') + '</div>'
      + '</div></div>';
    box.insertAdjacentHTML('beforeend', html);
    box.scrollTop = box.scrollHeight;
  }

  function renderDone(resp) {
    var wrap = $('streamDone');
    if (!wrap) return;
    var st = resp.status || 'EXECUTED';
    var cls = STATUS_CLASS[st] || '';
    var sec = resp.securityScore || {};
    var parts = [];
    parts.push('<div class="statbar ' + cls + '" style="margin-top:14px">'
      + '<span class="sig"></span>'
      + '<span class="stt">' + esc(STATUS_LABEL[st] || st) + '</span>'
      + '<span class="msg">' + esc(resp.message || '') + '</span>'
      + (resp.traceId ? '<span class="tid mono">' + esc(resp.traceId) + '</span>' : '')
      + '</div>');
    if (sec && (sec.score != null)) {
      parts.push('<div style="font-size:12.5px;color:var(--dim);margin-top:4px">安全评分：<b style="color:var(--text)">' + esc(sec.score) + '</b>'
        + (sec.grade ? ' · 等级 ' + esc(sec.grade) : '') + '</div>');
    }
    if (resp.analysis) {
      parts.push('<div class="analysis" style="margin-top:12px">' + esc(resp.analysis) + '</div>');
    }
    if (resp.traceId) {
      parts.push('<div style="margin-top:12px"><button class="btn btn-warn" style="width:auto" onclick="streamRollback(\'' + esc(resp.traceId) + '\')">一键回滚（dry-run）</button> '
        + '<span id="streamRbMsg" style="font-size:12px;color:var(--faint);margin-left:8px"></span></div>');
    }
    wrap.innerHTML = parts.join('');
    wrap.style.display = '';
  }

  window.runStream = function () {
    var ta = $('streamInstr');
    var v = ta ? ta.value.trim() : '';
    if (!v) { if (ta) ta.focus(); return; }
    if (es) { try { es.close(); } catch (e) {} es = null; }
    idx = 0;
    var box = $('streamBox');
    if (box) { box.innerHTML = ''; box.style.display = 'none'; }
    var done = $('streamDone');
    if (done) { done.innerHTML = ''; done.style.display = 'none'; }
    var empty = $('streamEmpty');
    if (empty) empty.style.display = 'none';
    setBtn(true);

    es = new EventSource('/api/ops/chat/stream?instruction=' + encodeURIComponent(v));
    es.addEventListener('start', function () { /* 已启动 */ });
    es.addEventListener('step', function (e) {
      try { appendStep(JSON.parse(e.data)); } catch (err) {}
    });
    es.addEventListener('done', function (e) {
      try { renderDone(JSON.parse(e.data)); } catch (err) {}
      setBtn(false);
      if (es) { es.close(); es = null; }
    });
    es.addEventListener('error', function (e) {
      // 正常结束后 readyState=CLOSED 也会触发；仅在未完成时提示
      if (es && es.readyState !== 2 && idx === 0) {
        var emptyEl = $('streamEmpty');
        if (emptyEl) { emptyEl.style.display = ''; emptyEl.textContent = '连接中断或服务不可用，请重试。'; }
      }
      setBtn(false);
      if (es) { es.close(); es = null; }
    });
  };

  window.streamRollback = function (traceId) {
    var msg = $('streamRbMsg');
    if (msg) msg.textContent = '回滚中…';
    fetch('/api/ops/rollback/' + encodeURIComponent(traceId), { method: 'POST' })
      .then(function (r) { return r.json(); })
      .then(function (j) {
        var d = (j && j.data) || j || {};
        if (msg) msg.textContent = d.message || '已提交回滚';
      })
      .catch(function () { if (msg) msg.textContent = '回滚请求失败'; });
  };
})();
