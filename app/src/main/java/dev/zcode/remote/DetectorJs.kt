package dev.zcode.remote

/**
 * 注入远程页（zcode.z.ai/remote）的脚本：任务结束检测 + 下拉刷新防误触探测 + 输入法回车防误发。
 *
 * 任务结束检测原理：relay 传输是多层二进制协议，无法可靠解析；但任务流式执行时页面
 * WebSocket 的字节流量持续处于高位，结束后回落到只剩心跳（约 10s 一次、几十字节）。
 * 本脚本包一层 window.WebSocket 统计流量，「持续高位 → 回落且页面不可见」即判定
 * 任务结束，通过 __ZcodeNative.onTaskFinished 通知 native。对协议升级、界面改版免疫。
 * 异常中断：进入「有任务」后连接断开（3s 内无自动重连），上报 {interrupted:true}——
 * 正常收尾不会有 close，执行中掉线即异常；native 据此发「任务中断」而非「已结束」。
 * payload 区分：{watchedMs:N}=正常结束；{interrupted:true}=执行中断连，任务状态未知。
 *
 * 下拉刷新探测：远程页是「外层不滚、聊天记录是内层滚动容器」的布局，native 侧
 * 只能看到外层滚动位置，内层滚动一律被误判成下拉刷新。脚本在 touchstart 时探测
 * 触点是否落在还能向上滚的内层容器里，经 __ZcodeNative.onPullGate 告知 native，
 * 命中则该手势让给页面滚动。
 *
 * 输入法回车防误发：WebKit 事件序 bug 165004——回车确认输入法组字时 compositionend
 * 先于 keydown 派发、且该 keydown 的 isComposing=false，页面「回车发送」无法把它与
 * 真正的发送回车区分，导致中文打字上屏时消息被打一半就发了出去。脚本吞掉组字收尾后
 * 极短窗口（50ms）内的 Enter keydown。Chromium(WebView) 事件序本身正确，此段是
 * 与 iOS/mac 副本逐字节一致的无害兜底。
 *
 * 注意：ios 与 mac 的 TaskDetector.swift 是同一份脚本的同步副本，改一处要同步三处。
 */
object DetectorJs {
    const val BRIDGE_NAME = "__ZcodeNative"

    val source: String = PROLOGUE + BODY

    private const val PROLOGUE = """
        (function () {
          if (window.__zcTaskDetector) return 'already';
          window.__zcTaskDetector = true;
          var bridge = window['__ZcodeNative'];
          if (!bridge || typeof bridge.onTaskFinished !== 'function') return 'no-bridge';
          function notifyPullGate(blocked) {
            try { bridge.onPullGate(blocked); } catch (e) { /* 旧版 native 无此方法 */ }
          }
          var OrigWS = window.WebSocket;
          if (!OrigWS) return 'no-ws';
    """

    private const val BODY = """
          // —— 阈值：每秒收包字节数判定 ——
          var TICK_MS = 1000;        // 统计窗口
          var HIGH_BYTES = 2048;     // 窗口内收到这么多字节视为「执行中」流量
          var ARM_MS = 15000;        // 高位需持续这么久才进入「有任务」状态（过滤翻历史等突发）
          var QUIET_MS = 6000;       // 「有任务」后需连续安静这么久才判定结束（过滤网络抖动）
          var GRACE_MS = 3000;       // 断连后等这么久排除页面自动重连，再定性为异常中断

          var bytesInWindow = 0;
          var highSince = 0;         // 本次连续高位起点，0 = 当前不是高位
          var armedAt = 0;           // 进入「有任务」状态的时刻
          var armed = false;
          var quietSince = 0;        // 回落后的安静起点
          var resolved = false;      // 本轮任务是否已出结果（已通知或用户在场看到）
          var openSockets = 0;
          var lastHighAt = 0;        // 最近一次高位窗口的时刻，断连时区分「执行中断」与「结束后断」
          var interruptTimer = 0;

          function sizeOf(data) {
            if (typeof data === 'string') return data.length;
            if (data && typeof data.size === 'number') return data.size;
            if (data && typeof data.byteLength === 'number') return data.byteLength;
            return 0;
          }

          function reset() {
            armed = false; resolved = false;
            highSince = 0; armedAt = 0; quietSince = 0;
            if (interruptTimer) { clearTimeout(interruptTimer); interruptTimer = 0; }
          }

          function onTick() {
            var now = Date.now();
            var high = bytesInWindow >= HIGH_BYTES && openSockets > 0;
            bytesInWindow = 0;
            if (high) {
              quietSince = 0;
              lastHighAt = now;
              if (!highSince) {
                highSince = now;
                if (resolved) { armed = false; resolved = false; } // 安静后流量再起 = 新一轮任务
              }
              if (!armed && now - highSince >= ARM_MS) {
                armed = true; armedAt = now; resolved = false;
              }
            } else {
              highSince = 0;
              if (armed && !resolved) {
                if (!quietSince) quietSince = now;
                if (now - quietSince >= QUIET_MS && openSockets > 0) {
                  resolved = true;
                  // 页面不可见（用户没在盯）才提醒；否则视为用户亲眼看到结束
                  if (document.hidden) {
                    try {
                      bridge.onTaskFinished(JSON.stringify({ watchedMs: now - armedAt }));
                      console.info('[zc-task-detector] task finished, notified');
                    } catch (e) { /* bridge 不可用即放弃 */ }
                  }
                }
              }
            }
          }

          function ZcWebSocket(url, protocols) {
            var ws = protocols === undefined ? new OrigWS(url) : new OrigWS(url, protocols);
            ws.addEventListener('open', function () {
              openSockets += 1;
              if (interruptTimer) { clearTimeout(interruptTimer); interruptTimer = 0; } // 自动重连成功，不算中断
            });
            ws.addEventListener('close', function () {
              openSockets = Math.max(0, openSockets - 1);
              if (openSockets > 0) return;
              if (armed && !resolved && !interruptTimer) {
                // 执行中连接断开：正常收尾不会有 close。等一小段排除自动重连，
                // 仍无连接则定性——最近还有高位流量=执行中断，安静已久=结束但没赶上判定
                interruptTimer = setTimeout(function () {
                  interruptTimer = 0;
                  if (!armed || resolved || openSockets > 0) return;
                  var interrupted = Date.now() - lastHighAt < QUIET_MS;
                  resolved = true;
                  if (document.hidden) {
                    try {
                      bridge.onTaskFinished(JSON.stringify(
                        interrupted ? { interrupted: true } : { watchedMs: Date.now() - armedAt }));
                    } catch (e) { /* bridge 不可用即放弃 */ }
                  }
                  reset();
                }, GRACE_MS);
              } else {
                reset();
              }
            });
            ws.addEventListener('error', function () {});
            ws.addEventListener('message', function (ev) { bytesInWindow += sizeOf(ev.data); });
            return ws;
          }
          ZcWebSocket.prototype = OrigWS.prototype;
          ZcWebSocket.CONNECTING = OrigWS.CONNECTING;
          ZcWebSocket.OPEN = OrigWS.OPEN;
          ZcWebSocket.CLOSING = OrigWS.CLOSING;
          ZcWebSocket.CLOSED = OrigWS.CLOSED;
          window.WebSocket = ZcWebSocket;

          setInterval(onTick, TICK_MS);

          // —— 下拉刷新防误触探测（双端 BODY 同步块，勿单边改动）——
          // 触点命中「还能向上滚」的内层容器（overflowY 可滚且 scrollTop>0）时，
          // 本次手势是内容滚动而非下拉刷新，探测结果交给 native 决定是否放行。
          document.addEventListener('touchstart', function (e) {
            var t = e.touches[0];
            if (!t) return;
            var el = document.elementFromPoint(t.clientX, t.clientY);
            var blocked = false;
            for (var n = el; n && n.nodeType === 1; n = n.parentElement) {
              var oy = getComputedStyle(n).overflowY;
              if ((oy === 'auto' || oy === 'scroll' || oy === 'overlay') && n.scrollTop > 0) {
                blocked = true; break;
              }
            }
            notifyPullGate(blocked);
          }, { capture: true, passive: true });

          // —— 输入法回车防误发（三端 BODY 同步块，勿单边改动）——
          // WebKit(WKWebView/Safari) 事件序 bug 165004：回车确认输入法组字时
          // compositionend 先于 keydown 派发、且该 keydown 的 isComposing=false，
          // 页面的「回车发送」无法把它与真正的发送回车区分，打字上屏时消息被误发。
          // 对策：记录最近一次组字活动时刻，其后 IME_GRACE_MS 内的 Enter keydown 一律
          // 吞掉（preventDefault + stopImmediatePropagation；挂 window 捕获层，本脚本
          // 注入于 documentStart、先于页面脚本注册，必然最先收到）。真想发送的回车在
          // 时序上远离组字收尾，不受影响。Chromium(Android WebView) 本就把组字期 Enter
          // 标为 isComposing=true，本守卫对它是无害兜底。
          var IME_GRACE_MS = 50;
          var imeActiveAt = 0;
          ['compositionstart', 'compositionupdate', 'compositionend'].forEach(function (t) {
            window.addEventListener(t, function () { imeActiveAt = Date.now(); }, true);
          });
          window.addEventListener('keydown', function (e) {
            if (e.key !== 'Enter' && e.keyCode !== 13) return;
            if (e.isComposing || e.keyCode === 229 || Date.now() - imeActiveAt < IME_GRACE_MS) {
              e.preventDefault();
              e.stopImmediatePropagation();
            }
          }, true);

          return 'ok';
        })();
    """
}
