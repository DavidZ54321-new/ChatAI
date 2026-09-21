package com.zcw.chatai.ui.chat

/**
 * 预览页导出用的注入脚本。
 *
 * 位图转换在页内（Chromium）完成——不在本机截 WebView，也不引第三方栅格化库。
 * PNG 的 base64 分片回传（[com.zcw.chatai.data.media.ExportChunkAssembler] 侧拼接解码），
 * 因为 bridge 单次传输有上限，整张图的 base64 一次传会丢/崩。
 *
 * GIF：Chromium 把 `<img>` 里的 SVG 当静态位图，`drawImage` **抽不到** SMIL 帧
 * （真机实测 distinct=1）。所以先把动画值按时刻**烘焙**进静态 SVG 再栅格化——
 * 逐帧确定性，不依赖引擎的动画时钟。
 */
internal object ExportJs {

    /** 导出位图长边上限：够清晰又不会把 canvas / 堆内存打爆。 */
    const val MAX_EDGE = 2048

    /**
     * 导出位图长边**下限**：必须放大到这个尺寸才够清晰。
     * PlantUML（@plantuml/core）的 SVG 坐标系可以只有 100 出头，只封顶不放大
     * 会导出一张 104×113 的缩略图（真机实测）；SVG 是矢量的，放大不糊。
     */
    private const val MIN_EDGE = 1024

    /** 分片大小（base64 字符）。保守取 128KB：JS bridge 单次传输上限通常在 1MB 级，
     *  base64 到 Java String 还会翻倍成 UTF-16，切小一点更稳。 */
    private const val CHUNK_CHARS = 128 * 1024

    /** GIF 抽帧：帧率 / 单帧长边 / 最多帧数 / 最长时长（超过就只取前几秒）。 */
    const val GIF_FPS = 10
    private const val GIF_MAX_EDGE = 480
    private const val GIF_MAX_FRAMES = 50
    private const val GIF_MAX_MS = 5000

    /** 通用工具：SVG → PNG dataURL、SMIL 烘焙、base64 分片回传。所有可导出页面都注入一次。 */
    val HELPERS: String = """
        (function () {
          if (window.__chataiExportReady) return;
          window.__chataiExportReady = true;
          var CHUNK = $CHUNK_CHARS;
          var MIN_EDGE = $MIN_EDGE;

          // 尺寸优先读 viewBox 属性（游离 clone 的 viewBox.baseVal 不可靠）。
          function svgSize(svg) {
            var vb = svg.getAttribute('viewBox');
            if (vb) {
              var parts = vb.trim().split(/[\s,]+/);
              if (parts.length === 4) {
                var vw = parseFloat(parts[2]), vh = parseFloat(parts[3]);
                if (vw > 0 && vh > 0) return [vw, vh];
              }
            }
            var aw = parseFloat(svg.getAttribute('width'));
            var ah = parseFloat(svg.getAttribute('height'));
            if (aw > 0 && ah > 0) return [aw, ah];
            var box = svg.getBoundingClientRect();
            return [box.width, box.height];
          }

          window.__chataiSvgToPng = function (svg, maxEdge, bg) {
            return new Promise(function (resolve, reject) {
              try {
                var size = svgSize(svg);
                var w = size[0], h = size[1];
                if (!w || !h || w <= 0 || h <= 0) { reject('无法获取图形尺寸'); return; }
                // 长边取「至多 maxEdge、至少 MIN_EDGE」：小坐标系放大、大图缩小。
                var natural = Math.max(w, h);
                var target = Math.min(maxEdge, Math.max(natural, MIN_EDGE));
                var scale = target / natural;
                var cw = Math.max(1, Math.round(w * scale));
                var ch = Math.max(1, Math.round(h * scale));

                var clone = svg.cloneNode(true);
                if (!clone.getAttribute('xmlns')) {
                  clone.setAttribute('xmlns', 'http://www.w3.org/2000/svg');
                }
                if (!clone.getAttribute('xmlns:xlink')) {
                  clone.setAttribute('xmlns:xlink', 'http://www.w3.org/1999/xlink');
                }
                // viewBox 仍是自然坐标，宽高设成目标像素：浏览器直接按目标分辨率栅格化。
                if (!clone.getAttribute('viewBox')) {
                  clone.setAttribute('viewBox', '0 0 ' + w + ' ' + h);
                }
                clone.setAttribute('width', cw);
                clone.setAttribute('height', ch);
                clone.style.maxWidth = 'none';
                clone.style.height = 'auto';

                var data = new XMLSerializer().serializeToString(clone);
                var url = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(data);
                var img = new Image();
                img.onload = function () {
                  try {
                    var canvas = document.createElement('canvas');
                    canvas.width = cw;
                    canvas.height = ch;
                    var ctx = canvas.getContext('2d');
                    ctx.fillStyle = bg;
                    ctx.fillRect(0, 0, cw, ch);
                    ctx.drawImage(img, 0, 0, cw, ch);
                    resolve(canvas.toDataURL('image/png'));
                  } catch (e) { reject((e && e.message) || String(e)); }
                };
                img.onerror = function () { reject('SVG 栅格化失败'); };
                img.src = url;
              } catch (e) { reject((e && e.message) || String(e)); }
            });
          };

          // 一帧 → 纯 base64（去掉 data URL 前缀）
          window.__chataiFrameBase64 = function (svg, maxEdge, bg) {
            return window.__chataiSvgToPng(svg, maxEdge, bg).then(function (url) {
              var comma = url.indexOf(',');
              return comma >= 0 ? url.substring(comma + 1) : url;
            });
          };

          window.__chataiEmitPng = function (dataUrl) {
            try {
              var comma = dataUrl.indexOf(',');
              var b64 = comma >= 0 ? dataUrl.substring(comma + 1) : dataUrl;
              for (var i = 0; i < b64.length; i += CHUNK) {
                ExportSink.append(b64.substring(i, Math.min(i + CHUNK, b64.length)));
              }
              ExportSink.finish(true, '');
            } catch (e) {
              ExportSink.finish(false, (e && e.message) || String(e));
            }
          };

          window.__chataiParseDur = function (text) {
            if (!text) return 0;
            var s = String(text).trim();
            if (s.slice(-2) === 'ms') return parseFloat(s) / 1000;
            if (s.slice(-1) === 's') return parseFloat(s);
            var n = parseFloat(s);
            return isNaN(n) ? 0 : n;
          };

          function lerpTokens(from, to, p) {
            var ta = String(from).trim().split(/[\s,]+/);
            var tb = String(to).trim().split(/[\s,]+/);
            if (ta.length !== tb.length) return p < 0.5 ? from : to;
            var out = [];
            for (var i = 0; i < ta.length; i++) {
              var na = parseFloat(ta[i]), nb = parseFloat(tb[i]);
              if (isNaN(na) || isNaN(nb)) return p < 0.5 ? from : to;
              out.push(na + (nb - na) * p);
            }
            return out.join(' ');
          }

          // 一个 SMIL 动画在 t 时刻的取值：支持 from/to，也支持 values + keyTimes + calcMode。
          // 返回 null = 这个动画烘焙不了（调用方保留原样，不影响其它动画）。
          function smilValueAt(a, t) {
            var dur = window.__chataiParseDur(a.getAttribute('dur'));
            var values = a.getAttribute('values');
            if (values !== null) {
              var list = values.split(';');
              for (var i = 0; i < list.length; i++) list[i] = list[i].trim();
              if (list.length === 0) return null;
              if (list.length === 1 || !(dur > 0)) return list[0];
              var p = (t % dur) / dur;
              var times = null;
              var keyTimes = a.getAttribute('keyTimes');
              if (keyTimes) {
                var raw = keyTimes.split(';');
                if (raw.length === list.length) {
                  times = [];
                  for (var j = 0; j < raw.length; j++) times.push(parseFloat(raw[j]));
                }
              }
              if (!times) {
                times = [];
                for (var k = 0; k < list.length; k++) times.push(k / (list.length - 1));
              }
              if ((a.getAttribute('calcMode') || 'linear').toLowerCase() === 'discrete') {
                var pick = 0;
                for (var m = 0; m < times.length; m++) if (p >= times[m]) pick = m;
                return list[pick];
              }
              var seg = 0;
              while (seg < times.length - 2 && p >= times[seg + 1]) seg++;
              var lo = times[seg], hi = times[seg + 1];
              var span = hi - lo;
              var f = span > 0 ? (p - lo) / span : 0;
              f = Math.max(0, Math.min(1, f));
              return lerpTokens(list[seg], list[seg + 1], f);
            }
            var from = a.getAttribute('from');
            var to = a.getAttribute('to');
            if (from !== null && to !== null) {
              if (!(dur > 0)) return from;
              return lerpTokens(from, to, (t % dur) / dur);
            }
            // <set> / 只给 to 的写法：整段就是终值。
            if (to !== null) return to;
            return null;
          }

          function collectElements(root, list) {
            list.push(root);
            var kids = root.children;
            for (var i = 0; i < kids.length; i++) collectElements(kids[i], list);
          }

          // CSS 动画（@keyframes/animation）烘焙：Web Animations API 把时钟拨到 t，
          // 读**计算样式**写成内联样式，并关掉该元素的 animation。
          // 内联 transform 用的是已解析矩阵，transform-origin 仍由原 CSS 规则生效，位置不会偏。
          function bakeCssAnimations(live, clone, tMs) {
            if (!live.getAnimations) return;
            var anims = live.getAnimations({ subtree: true });
            if (!anims.length) return;
            for (var i = 0; i < anims.length; i++) {
              try { anims[i].pause(); anims[i].currentTime = tMs; } catch (e) {}
            }
            var liveNodes = [], cloneNodes = [];
            collectElements(live, liveNodes);
            collectElements(clone, cloneNodes);
            var done = {};
            for (var j = 0; j < anims.length; j++) {
              var effect = anims[j].effect;
              var target = effect && effect.target;
              if (!target) continue;
              var index = liveNodes.indexOf(target);
              if (index < 0 || done[index]) continue;
              done[index] = 1;
              var el = cloneNodes[index];
              if (!el || !el.style) continue;
              var cs = getComputedStyle(target);
              el.style.animation = 'none';
              if (cs.transform && cs.transform !== 'none') el.style.transform = cs.transform;
              if (cs.opacity !== '' && cs.opacity !== '1') el.style.opacity = cs.opacity;
            }
            for (var k = 0; k < anims.length; k++) {
              try { anims[k].play(); } catch (e) {}
            }
          }

          // 把 t 时刻的动画值写死成静态属性，并移除动画节点：
          // 之后就是一张普通静态 SVG，可安全序列化 / 栅格化。
          window.__chataiBake = function (svg, t) {
            var clone = svg.cloneNode(true);
            // CSS 先烘（此刻两边结构一致，才能按先序下标配对元素）。
            bakeCssAnimations(svg, clone, t * 1000);
            var anims = clone.querySelectorAll(
              'animate, animateTransform, animateMotion, animateColor, set'
            );
            for (var i = 0; i < anims.length; i++) {
              var a = anims[i];
              var name = a.getAttribute('attributeName');
              var value = smilValueAt(a, t);
              if (name && value !== null) {
                if (a.nodeName.toLowerCase() === 'animatetransform') {
                  var type = a.getAttribute('type') || 'translate';
                  a.parentNode.setAttribute('transform', type + '(' + value + ')');
                } else {
                  a.parentNode.setAttribute(name, value);
                }
              }
              a.parentNode.removeChild(a);
            }
            return clone;
          };

          // 动画总时长（秒）：SMIL 的 dur 与 CSS 动画 duration 取最大，都不行返回 0。
          window.__chataiAnimationSeconds = function (svg) {
            var max = 0;
            var anims = svg.querySelectorAll(
              'animate, animateTransform, animateMotion, animateColor, set'
            );
            for (var i = 0; i < anims.length; i++) {
              var d = window.__chataiParseDur(anims[i].getAttribute('dur'));
              if (d > max) max = d;
            }
            if (svg.getAnimations) {
              var list = svg.getAnimations({ subtree: true });
              for (var j = 0; j < list.length; j++) {
                var effect = list[j].effect;
                var timing = effect && effect.getTiming ? effect.getTiming() : null;
                var duration = timing && timing.duration;
                if (typeof duration === 'number' && duration / 1000 > max) {
                  max = duration / 1000;
                }
              }
            }
            return max;
          };
        })();
    """.trimIndent()

    /** 静态/动效 SVG 页（loadDataWithBaseURL）专用：文档里已有 svg。 */
    val SVG_PAGE_EXPORT: String = """
        window.exportPng = function (maxEdge) {
          var svg = document.querySelector('svg');
          if (!svg) { ExportSink.finish(false, '页面里没有 SVG'); return; }
          window.__chataiSvgToPng(svg, maxEdge, '#ffffff').then(function (url) {
            window.__chataiEmitPng(url);
          }).catch(function (e) {
            ExportSink.finish(false, (e && e.message) || String(e));
          });
        };

        // 动效 SVG → GIF：逐帧烘焙 + 栅格化，帧 PNG 逐张回传（Kotlin 侧编 GIF）。
        window.exportGif = function (maxEdge, fps, maxFrames, maxMs) {
          var svg = document.querySelector('svg');
          if (!svg) { ExportSink.finish(false, '页面里没有 SVG'); return; }
          var dur = window.__chataiAnimationSeconds(svg);
          if (!(dur > 0)) dur = 2;
          var totalMs = Math.min(maxMs, Math.max(200, dur * 1000));
          var count = Math.min(maxFrames, Math.max(1, Math.round(totalMs / 1000 * fps)));
          ExportSink.beginGif();
          var index = 0;
          (function next() {
            if (index >= count) { ExportSink.finish(true, ''); return; }
            var baked = window.__chataiBake(svg, index / fps);
            window.__chataiFrameBase64(baked, maxEdge, '#ffffff').then(function (b64) {
              ExportSink.frame(b64);
              index++;
              ExportSink.progress(index, count);
              setTimeout(next, 0);
            }).catch(function (e) {
              ExportSink.finish(false, (e && e.message) || String(e));
            });
          })();
        };
    """.trimIndent()

    /** GIF 导出的 JS 参数（帧率 / 长边 / 帧数 / 时长上限）。 */
    fun gifArgs(): String = "$GIF_MAX_EDGE, $GIF_FPS, $GIF_MAX_FRAMES, $GIF_MAX_MS"
}
