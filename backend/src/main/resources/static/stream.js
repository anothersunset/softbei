/*
 * 实时思维链（Live Chain-of-Thought）客户端
 * 通过 EventSource 订阅 GET /api/ops/chat/stream，实时渲染安全护栏八阶段的逐步执行（类 ChatGPT tool_call 展示）。
 * 不依赖也不修改 app.js；所需样式与阶段进度条 DOM 均由本文件动态注入，不依赖 index.html 的结构调整。
 */
(function () {
  var STAGE_ORDER = ['RECEIVE', 'INJECTION_GUARD', 'SENSE', 'RETRIEVE', 'REASON', 'GUARD', 'EXECUTE', 'ANALYZE'];
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

  var CSS = ''
    + '.streampipe{display:flex;align-items:center;flex-wrap:wrap;gap:0;margin:2px 0 14px}'
    + '.streampipe .spp{font-size:11px;color:var(--faint);padding:5px 9px;border:1px solid var(--line);border-radius:7px;background:var(--surface-2);white-space:nowrap;transition:.2s}'
    + '.streampipe .spp.done{color:var(--safe);border-color:color-mix(in srgb,var(--safe) 40%,var(--line));background:color-mix(in srgb,var(--safe) 9%,#fff)}'
    + '.streampipe .spp.active{color:var(--accent);border-color:var(--accent);box-shadow:0 0 0 3px var(--accent-soft)}'
    + '.streampipe .spp.halt{color:var(--block);border-color:var(--block);background:color-mix(in srgb,var(--block) 10%,#fff)}'
    + '.streampipe .spp-sep{width:9px;height:1px;background:var(--line);flex-shrink:0;margin:0 2px}'
    + '.rb-led{margin-top:16px}'
    + '.rb-led .lbl{font-size:11px;letter-spacing:1px;text-transform:uppercase;color:var(--dim);margin:0 0 10px;font-weight:700}'
    + '.rb-item{border:1px solid var(--line);border-left:3px solid var(--accent);border-radius:9px;padding:10px 12px;margin-bottom:8px;background:var(--surface-2)}'
    + '.rb-item.rev{border-left-color:var(--safe)}'
    + '.rb-item.man{border-left-color:var(--review)}'
    + '.rb-top{display:flex;align-items:center;gap:10px;justify-content:space-between;flex-wrap:wrap}'
    + '.rb-top code{font-family:ui-monospace,Consolas,monospace;font-size:12.5px;color:#24344d;word-break:break-word}'
    + '.rb-cmd{font-size:12px;color:var(--dim);margin-top:8px}'
    + '.rb-cmd code{font-family:ui-monospace,Consolas,monospace;font-size:12.5px;color:var(--safe);word-break:break-word}'
    + '.rb-manual{font-size:12px;color:var(--review);margin-top:8px;line-height:1.6}'
    + '.rb-none{font-size:12.5px;color:var(--faint);margin-top:10px}';

  var es = null;
  var idx = 0;
  var pendingTimer = null;
  var doneStages = {};
  var haltStage = null;

  function $(id) { return document.getElementById(id); }

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  /** 首次运行时注入样式与阶段进度条容器，避免修改 index.html。 */
  function ensureUi() {
    if (!document.getElementById('streamPipeStyle')) {
      var st = document.createElement('style');
      st.id = 'streamPipeStyle';
      st.textContent = CSS;
      document.head.appendChild(st);
    }
    if (!document.getElementById('streamPipe')) {
      var box = $('streamBox');
      if (box && box.parentNode) {
        var p = document.createElement('div');
        p.id = 'streamPipe';
        p.className = 'streampipe';
        p.style.display = 'none';
        box.parentNode.insertBefore(p, box);
      }
    }
  }

  function setBtn(running) {
    var b = $('streamBtn');
    if (b) b.disabled = running;
    var l = $('streamLoader');
    if (l) l.classList.toggle('on', running);
  }

  function clearPendingTimer() {
    if (pendingTimer) {
      clearTimeout(pendingTimer);
      pendingTimer = null;
    }
  }

  /** 渲染八阶段实时进度条：已完成点亮为绿色，被拦截阶段标红，下一阶段为 active。 */
  function renderPipe() {
    var pipe = $('streamPipe');
    if (!pipe) return;
    pipe.style.display = '';
    var nextActive = null;
    if (!haltStage) {
      for (var i = 0; i < STAGE_ORDER.length; i++) {
        if (!doneStages[STAGE_ORDER[i]]) { nextActive = STAGE_ORDER[i]; break; }
      }
    }
    var chips = STAGE_ORDER.map(function (s) {
      var cls = 'spp';
      if (haltStage === s) cls += ' halt';
      else if (doneStages[s]) cls += ' done';
      else if (s === nextActive) cls += ' active';
      return '<span class="' + cls + '">' + esc(STAGE_LABEL[s] || s) + '</span>';
    });
    pipe.innerHTML = chips.join('<i class="spp-sep"></i>');
  }

  function resetPipe() {
    doneStages = {};
    haltStage = null;
    var pipe = $('streamPipe');
    if (pipe) { pipe.innerHTML = ''; pipe.style.display = 'none'; }
  }

  function summarizeOutput(stage, output) {
    if (!output || typeof output !== 'object') return '';
    try {
      if (stage === 'SENSE') return '感知工具 ' + (output.toolsRun != null ? output.toolsRun : '?') + ' 个';
      if (stage === 'RETRIEVE') return '检索依据 ' + (output.count != null ? output.count : 0) + ' 条' + (output.degraded ? '（降级）' : '（双语同义召回）');
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

    // 同步阶段进度条
    if (step.stage) {
      doneStages[step.stage] = true;
      if (halt) haltStage = step.stage;
    }
    renderPipe();
  }

  /** 可视化一键回滚动作账本：逐条展示原始变更、是否可自动回滚、补偿命令或人工恢复指引。 */
  function renderLedger(plan, status) {
    if (!Array.isArray(plan) || plan.length === 0) {
      if (status === 'EXECUTED') {
        return '<div class="rb-led"><div class="lbl">一键回滚账本</div><div class="rb-none">本次无已执行的变更类操作，无需回滚。</div></div>';
      }
      return '';
    }
    var autoN = 0;
    var rows = plan.map(function (p) {
      var rev = !!p.reversible;
      if (rev) autoN += 1;
      var badge = rev
        ? '<span class="badge safe">可自动回滚</span>'
        : '<span class="badge review">需人工恢复</span>';
      var body = '';
      if (p.compensate) body += '<div class="rb-cmd">补偿命令：<code>' + esc(p.compensate) + '</code></div>';
      if (p.manual) body += '<div class="rb-manual">' + esc(p.manual) + '</div>';
      return '<div class="rb-item ' + (rev ? 'rev' : 'man') + '">'
        + '<div class="rb-top"><code>' + esc(p.origin) + '</code>' + badge + '</div>'
        + body
        + '</div>';
    }).join('');
    return '<div class="rb-led">'
      + '<div class="lbl">一键回滚账本 · 共 ' + plan.length + ' 项（可自动 ' + autoN + ' 项）</div>'
      + rows
      + '</div>';
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
    // 回滚账本可视化
    parts.push(renderLedger(resp.rollbackPlan, st));
    if (resp.traceId) {
      parts.push('<div style="margin-top:12px"><button class="btn btn-warn" style="width:auto" onclick="streamRollback(\'' + esc(resp.traceId) + '\')">一键回滚（dry-run）</button> '
        + '<span id="streamRbMsg" style="font-size:12px;color:var(--faint);margin-left:8px"></span></div>');
    }
    wrap.innerHTML = parts.join('');
    wrap.style.display = '';
  }

  window.runStream = function () {
    ensureUi();
    var ta = $('streamInstr');
    var v = ta ? ta.value.trim() : '';
    if (!v) { if (ta) ta.focus(); return; }
    if (!window.EventSource) {
      var unsupported = $('streamEmpty');
      if (unsupported) {
        unsupported.style.display = '';
        unsupported.textContent = '当前浏览器不支持实时事件流，请使用普通执行模式。';
      }
      return;
    }
    if (es) { try { es.close(); } catch (e) {} es = null; }
    clearPendingTimer();
    idx = 0;
    resetPipe();
    var box = $('streamBox');
    if (box) { box.innerHTML = ''; box.style.display = 'none'; }
    var done = $('streamDone');
    if (done) { done.innerHTML = ''; done.style.display = 'none'; }
    var empty = $('streamEmpty');
    if (empty) empty.style.display = 'none';
    setBtn(true);

    es = new EventSource('/api/ops/chat/stream?instruction=' + encodeURIComponent(v));
    pendingTimer = setTimeout(function () {
      if (idx === 0 && es) {
        try { es.close(); } catch (e) {}
        es = null;
        setBtn(false);
        var emptyEl = $('streamEmpty');
        if (emptyEl) { emptyEl.style.display = ''; emptyEl.textContent = '服务暂未返回实时事件，请确认后端已启动。'; }
      }
    }, 2500);
    es.addEventListener('start', function () { /* 已启动 */ });
    es.addEventListener('step', function (e) {
      clearPendingTimer();
      try { appendStep(JSON.parse(e.data)); } catch (err) {}
    });
    es.addEventListener('done', function (e) {
      clearPendingTimer();
      try { renderDone(JSON.parse(e.data)); } catch (err) {}
      setBtn(false);
      if (es) { es.close(); es = null; }
    });
    es.addEventListener('error', function (e) {
      // 未收到任何阶段事件时，通常是后端未启动或流端点不可用。
      if (idx === 0) {
        var emptyEl = $('streamEmpty');
        if (emptyEl) { emptyEl.style.display = ''; emptyEl.textContent = '连接中断或服务不可用，请重试。'; }
      }
      clearPendingTimer();
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
