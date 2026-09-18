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

        body.append("<h2>学习路线</h2>");
        if (snapshot.learningPath().isEmpty()) {
            body.append("<p>尚未生成学习路线</p>");
        } else {
            body.append("<ol>");
            for (ShareSnapshot.PathStep step : snapshot.learningPath()) {
                body.append("<li>").append(escape(step.codeUnitName()))
                        .append("（约 ").append(step.estimatedMinutes()).append(" 分钟）— ")
                        .append(escape(step.reason())).append("</li>");
            }
            body.append("</ol>");
        }

        body.append("<h2>问答记录</h2>");
        if (snapshot.qaSamples().isEmpty()) {
            body.append("<p>暂无问答记录</p>");
        } else {
            for (ShareSnapshot.QaSample sample : snapshot.qaSamples()) {
                body.append("<p><b>问：</b>").append(escape(sample.question())).append("</p>");
                body.append("<p>").append(escape(sample.answer())).append("</p>");
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
                + "<style>body{font-family:sans-serif;max-width:900px;margin:24px auto;padding:0 16px;}"
                + "pre.mermaid{background:#fafafa;padding:12px;border-radius:6px;overflow:auto;}"
                + "footer{margin-top:32px;color:#999;font-size:12px;}</style></head><body>"
                + body
                + "<footer>由 CodeCompass 生成</footer>"
                + "<script>mermaid.initialize({startOnLoad:true});</script></body></html>";
        return html;
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
