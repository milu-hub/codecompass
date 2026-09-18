package com.codecompass.web;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.codecompass.persistence.ShareSnapshotEntity;
import com.codecompass.persistence.ShareSnapshotRepository;
import com.codecompass.service.AchievementService;
import com.codecompass.service.AnalysisTaskSnapshot;
import com.codecompass.service.AnalysisTaskStore;
import com.codecompass.service.ClientIdentityHolder;
import com.codecompass.service.ShareService;
import com.codecompass.service.ShareSnapshot;
import com.codecompass.web.dto.ErrorResponse;
import com.codecompass.web.dto.NotReadyResponse;

import tools.jackson.databind.json.JsonMapper;

/**
 * F6 分享 REST：POST 生成快照（返回短链）；GET /share/{id} 返回独立 HTML
 * （无 Cookie、无身份继承，页面内嵌 Mermaid CDN 渲染依赖图）。
 */
@RestController
public class ShareController {

    private final AnalysisTaskStore store;
    private final ShareService shareService;
    private final ShareSnapshotRepository repository;
    private final AchievementService achievementService;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public ShareController(AnalysisTaskStore store, ShareService shareService,
                           ShareSnapshotRepository repository, AchievementService achievementService,
                           JsonMapper jsonMapper, Clock clock) {
        this.store = store;
        this.shareService = shareService;
        this.repository = repository;
        this.achievementService = achievementService;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    public record ShareLinkView(String shareId, String url) {
    }

    @PostMapping("/api/repos/{taskId}/share")
    public ResponseEntity<?> create(@PathVariable String taskId) {
        AnalysisTaskSnapshot snapshot = store.find(taskId).orElse(null);
        if (snapshot == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("任务不存在"));
        }
        if (!AnalysisTaskSnapshot.STATUS_DONE.equals(snapshot.status())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new NotReadyResponse(
                    taskId, snapshot.status(), "分析尚未完成，当前状态：" + snapshot.status()));
        }
        String clientId = ClientIdentityHolder.get();
        ShareSnapshot content = shareService.build(snapshot.outcome().result(),
                snapshot.outcome().graph().mermaid(), snapshot.url(), snapshot.outcome().commitSha(),
                clientId, achievementService.unlocked(clientId));
        String shareId = shareService.newShareId();
        try {
            repository.save(new ShareSnapshotEntity(shareId, snapshot.url(),
                    snapshot.outcome().commitSha(), jsonMapper.writeValueAsString(content),
                    shareService.expiresAt(), clock.instant()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(new ErrorResponse("快照保存失败"));
        }
        return ResponseEntity.ok(new ShareLinkView(shareId, "/share/" + shareId));
    }

    private static final MediaType HTML_UTF8 =
            new MediaType("text", "html", StandardCharsets.UTF_8);

    @GetMapping("/share/{id}")
    public ResponseEntity<String> page(@PathVariable String id) {
        ShareSnapshotEntity entity = repository.findById(id).orElse(null);
        if (entity == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(HTML_UTF8)
                    .body(simplePage("分享不存在", "<p>该分享不存在或已被删除。</p>"));
        }
        if (!entity.getExpiresAt().isAfter(clock.instant())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(HTML_UTF8)
                    .body(simplePage("分享已过期", "<p>该分享已过期。</p>"));
        }
        try {
            ShareSnapshot snapshot = jsonMapper.readValue(entity.getSnapshotJson(), ShareSnapshot.class);
            return ResponseEntity.ok().contentType(HTML_UTF8).body(render(snapshot));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(HTML_UTF8)
                    .body(simplePage("分享损坏", "<p>该分享内容损坏。</p>"));
        }
    }

    // ---------- HTML 渲染（后端独立页面，不复用 SPA） ----------

    private static String simplePage(String title, String body) {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>" + escape(title)
                + "</title></head><body><h1>" + escape(title) + "</h1>" + body
                + "<footer>由 CodeCompass 生成</footer></body></html>";
    }

    private static String render(ShareSnapshot snapshot) {
        StringBuilder body = new StringBuilder();
        body.append("<h1>CodeCompass 分享</h1>");
        body.append("<p>仓库：").append(escape(snapshot.repoUrl()))
                .append("（").append(escape(shortSha(snapshot.commitSha()))).append("）</p>");

        body.append("<h2>依赖图</h2>");
        if (snapshot.graphMermaid() != null && !snapshot.graphMermaid().isBlank()) {
            body.append("<pre class=\"mermaid\">").append(escape(snapshot.graphMermaid()))
                    .append("</pre>");
        } else {
            body.append("<p>无依赖图</p>");
        }

        body.append(renderLearningPath(snapshot));

        body.append("<h2>问答记录</h2>");
        if (snapshot.qaSamples().isEmpty()) {
            body.append("<p>暂无问答记录</p>");
        } else {
            for (ShareSnapshot.QaSample sample : snapshot.qaSamples()) {
                body.append("<p><b>问：</b>").append(escape(sample.question())).append("</p>");
                body.append("<p>").append(escape(sample.answer())).append("</p>");
            }
        }

        // 笔记：只含分享者本人的（快照构建阶段已按 clientId 过滤）
        body.append("<h2>笔记</h2>");
        if (snapshot.notes().isEmpty()) {
            body.append("<p>暂无笔记</p>");
        } else {
            for (ShareSnapshot.NoteBrief note : snapshot.notes()) {
                body.append("<p><b>").append(escape(note.codeUnitName())).append("</b></p>");
                body.append("<pre class=\"note\">").append(escape(note.content())).append("</pre>");
            }
        }

        body.append("<h2>成就</h2><ul>");
        for (ShareSnapshot.AchievementBrief brief : snapshot.achievements()) {
            body.append("<li>").append(escape(brief.name())).append("（").append(escape(brief.code())).append("）</li>");
        }
        body.append("</ul>");

        String html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<title>CodeCompass 分享</title>"
                + "<script src=\"https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.min.js\"></script>"
                + "<style>"
                + "body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;"
                + "color:#1f2a27;max-width:900px;margin:24px auto;padding:0 16px;line-height:1.6;}"
                + "h1{font-size:22px;font-weight:700;}"
                + "h2{font-size:17px;font-weight:600;margin-top:36px;}"
                + "a{color:#0ca678;}"
                + "pre.mermaid{background:#f7faf9;padding:12px;border-radius:8px;overflow:auto;border:1px solid rgba(220,230,225,.6);}"
                + "pre.note{background:#fffbe6;padding:10px;border-radius:8px;white-space:pre-wrap;}"
                + "footer{margin-top:40px;color:#98a6a0;font-size:12px;}"
                + ".path-meta{font-size:13px;color:#98a6a0;margin:4px 0 20px;}"
                + ".path-steps{list-style:none;margin:0;padding:0;}"
                + ".path-step{margin:0 0 24px;padding:0;}"
                + ".path-step-top{display:flex;align-items:center;gap:12px;}"
                + ".path-ordinal{flex-shrink:0;width:28px;height:28px;border-radius:50%;"
                + "background:rgba(12,166,120,.12);color:#0ca678;display:inline-flex;align-items:center;"
                + "justify-content:center;font-size:13px;font-weight:600;}"
                + ".path-name{flex:1 1 auto;min-width:0;overflow-wrap:anywhere;font-size:15px;font-weight:600;}"
                + ".path-minutes{font-size:13px;color:#98a6a0;white-space:nowrap;}"
                + ".path-step-bottom{padding-left:40px;margin-top:2px;}"
                + ".path-reason{font-size:13px;color:#66756f;line-height:1.6;}"
                + ".path-toggle{margin-top:4px;padding:7px 14px;font-size:13px;color:#0ca678;"
                + "background:rgba(12,166,120,.06);border:1px solid rgba(12,166,120,.28);border-radius:8px;cursor:pointer;}"
                + ".path-toggle:hover{background:rgba(12,166,120,.12);}"
                + "@media (max-width:767px){.path-name{flex:0 1 auto;}.path-step-top{flex-wrap:wrap;}}"
                + "</style></head><body>"
                + body
                + "<footer>由 CodeCompass 生成</footer>"
                + "<script>mermaid.initialize({startOnLoad:true});</script>"
                + "<script>(function(){var b=document.getElementById('path-toggle');if(!b)return;"
                + "b.addEventListener('click',function(){var e=b.getAttribute('data-expanded')!=='1';"
                + "var s=document.querySelectorAll('.path-step--extra');"
                + "for(var i=0;i<s.length;i++){if(e){s[i].removeAttribute('hidden');}else{s[i].setAttribute('hidden','');}}"
                + "b.textContent=e?'收起':'展开全部';b.setAttribute('data-expanded',e?'1':'0');});})();</script>"
                + "</body></html>";
        return html;
    }

    /**
     * 学习路线（第 7 步美化）：三段式 —— 第一行「圆形序号 + 类名 + 右侧预计分钟」，
     * 第二行缩进到类名下方展示 reason；步间距 24px、无分隔线、无每步卡片底。
     * 默认只展开前 8 步，超出部分由「展开全部」切换（hidden 属性）。
     *
     * <p>类名是否可点击：只有当分享页内嵌源码视图时才能跳转到对应类；本快照刻意不含源码
     * （FEATURE_SPEC F6 禁止项），所以这里只渲染文本，绝不伪造一个点不动的链接。
     */
    private static String renderLearningPath(ShareSnapshot snapshot) {
        if (snapshot.learningPath().isEmpty()) {
            return "<h2>学习路线</h2><p>尚未生成学习路线</p>";
        }
        int totalMinutes = snapshot.learningPath().stream()
                .mapToInt(ShareSnapshot.PathStep::estimatedMinutes).sum();
        StringBuilder html = new StringBuilder();
        html.append("<h2>学习路线</h2>");
        html.append("<p class=\"path-meta\">共 ").append(snapshot.learningPath().size())
                .append(" 步 · 预计 ").append(totalMinutes).append(" 分钟</p>");
        html.append("<ol class=\"path-steps\">");
        int index = 0;
        for (ShareSnapshot.PathStep step : snapshot.learningPath()) {
            boolean collapsed = index >= 8;
            html.append("<li class=\"path-step")
                    .append(collapsed ? " path-step--extra\" hidden" : "\"")
                    .append(">");
            html.append("<div class=\"path-step-top\">")
                    .append("<span class=\"path-ordinal\">").append(step.order()).append("</span>")
                    .append("<span class=\"path-name\">").append(escape(step.codeUnitName()))
                    .append("</span>")
                    .append("<span class=\"path-minutes\">约 ").append(step.estimatedMinutes())
                    .append(" 分钟</span>")
                    .append("</div>");
            html.append("<div class=\"path-step-bottom\">")
                    .append("<span class=\"path-reason\">").append(escape(step.reason()))
                    .append("</span>")
                    .append("</div>");
            html.append("</li>");
            index++;
        }
        html.append("</ol>");
        if (snapshot.learningPath().size() > 8) {
            html.append("<button type=\"button\" id=\"path-toggle\" class=\"path-toggle\"")
                    .append(" data-expanded=\"0\">展开全部</button>");
        }
        return html.toString();
    }

    private static String shortSha(String commitSha) {
        return commitSha == null ? "" : commitSha.substring(0, Math.min(8, commitSha.length()));
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
