package com.ccdev.drainage.web;

import com.ccdev.drainage.auth.AuthInterceptor;
import com.ccdev.drainage.domain.Models.AppUser;
import com.ccdev.drainage.dto.Requests;
import com.ccdev.drainage.repo.ProtocolRepository;
import com.ccdev.drainage.repo.UserRepository;
import com.ccdev.drainage.service.ProtocolCatalogService;
import com.ccdev.drainage.service.ProtocolMigrationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 判读规程迁移分析：
 *   - 规则目录与规则差异
 *   - 迁移运行（全量重放 / 增量重算），只增不改
 *   - 影子分类、证据缺口、新旧顺位变化、受影响管段查询
 *   - 审查视图按缺陷类型与来源批次人工复核（不回写原等级或已发布风险结果）
 */
@RestController
@RequestMapping("/api/protocols")
public class ProtocolController {

    private final ProtocolRepository protocolRepo;
    private final ProtocolCatalogService catalogService;
    private final ProtocolMigrationService migrationService;
    private final UserRepository userRepo;

    public ProtocolController(ProtocolRepository protocolRepo, ProtocolCatalogService catalogService,
                              ProtocolMigrationService migrationService, UserRepository userRepo) {
        this.protocolRepo = protocolRepo;
        this.catalogService = catalogService;
        this.migrationService = migrationService;
        this.userRepo = userRepo;
    }

    @GetMapping
    public Map<String, Object> protocols() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocols", protocolRepo.findAllProtocols());
        return result;
    }

    /** 规则目录：规程、类别定义、必要观察字段、生效日期、批准来源。 */
    @GetMapping("/catalog")
    public Map<String, Object> catalog() {
        return catalogService.catalog();
    }

    @GetMapping("/{id}/categories")
    public List<?> categories(@PathVariable Long id) {
        return protocolRepo.findCategories(id);
    }

    /** 规则差异：旧类别 → 新类别拆分、必要字段、依赖来源、被废弃类别。 */
    @GetMapping("/{id}/diff")
    public Map<String, Object> diff(@PathVariable Long id,
                                    @RequestParam(required = false) Long baseProtocolId) {
        return catalogService.ruleDiff(id, baseProtocolId);
    }

    @GetMapping("/{id}/dependencies")
    public Map<String, Object> dependencies(@PathVariable Long id) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("targetProtocolId", id);
        result.put("dependencies", protocolRepo.findDependencies(id));
        result.put("fields", protocolRepo.findAllFields());
        return result;
    }

    @GetMapping("/runs")
    public List<?> runs() {
        return protocolRepo.findAllRuns();
    }

    @GetMapping("/runs/{runId}")
    public Map<String, Object> run(@PathVariable Long runId) {
        return catalogService.runDetail(runId);
    }

    @GetMapping("/runs/{runId}/shadows")
    public Map<String, Object> shadows(@PathVariable Long runId,
                                       @RequestParam(required = false) String defectType,
                                       @RequestParam(required = false) Long batchId,
                                       @RequestParam(required = false) String classification,
                                       @RequestParam(required = false) String applicability) {
        return catalogService.shadowQuery(runId, defectType, batchId, classification, applicability);
    }

    @GetMapping("/runs/{runId}/segments")
    public Map<String, Object> segments(@PathVariable Long runId,
                                        @RequestParam(required = false) Boolean affected) {
        return catalogService.affectedSegments(runId, affected);
    }

    @GetMapping("/runs/{runId}/dependency-diff")
    public Map<String, Object> dependencyDiff(@PathVariable Long runId) {
        return catalogService.dependencyDiff(runId);
    }

    @GetMapping("/observations/{observationId}/fields")
    public List<?> observationFields(@PathVariable Long observationId) {
        return catalogService.observationFields(observationId);
    }

    /** 触发迁移运行：全量重放（覆盖跨类别依赖）或增量重算（按依赖索引局部重算）。 */
    @PostMapping("/runs")
    public Map<String, Object> runMigration(@Valid @RequestBody Requests.ProtocolRunRequest req,
                                            HttpServletRequest request) {
        AppUser user = (AppUser) request.getAttribute(AuthInterceptor.CURRENT_USER);
        Map<String, Object> result = migrationService.run(req, user.id());
        userRepo.audit(user.id(), "PROTOCOL_MIGRATION_RUN", "run:" + result.get("runCode"),
                "规程迁移 " + result.get("mode") + "：" + result.get("summary"));
        return result;
    }

    /** 审查：按单条影子候选复核；只写复核记录，不改影子分类或已发布结果。 */
    @PostMapping("/runs/{runId}/shadows/{shadowId}/review")
    public Map<String, Object> review(@PathVariable Long runId, @PathVariable Long shadowId,
                                      @Valid @RequestBody Requests.ShadowReviewRequest req,
                                      HttpServletRequest request) {
        AppUser user = (AppUser) request.getAttribute(AuthInterceptor.CURRENT_USER);
        return catalogService.review(runId, shadowId, req.decision(), req.chosenCategory(),
                req.note(), user.id(), user.username(), user.displayName());
    }
}
