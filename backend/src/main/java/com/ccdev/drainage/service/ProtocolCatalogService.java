package com.ccdev.drainage.service;

import com.ccdev.drainage.config.ApiException;
import com.ccdev.drainage.domain.Models.Observation;
import com.ccdev.drainage.domain.Models.ObservationField;
import com.ccdev.drainage.domain.Models.Protocol;
import com.ccdev.drainage.domain.Models.ProtocolCategory;
import com.ccdev.drainage.domain.Models.ProtocolDependency;
import com.ccdev.drainage.domain.Models.ProtocolField;
import com.ccdev.drainage.domain.Models.ProtocolRun;
import com.ccdev.drainage.domain.Models.ProtocolSplit;
import com.ccdev.drainage.domain.Models.ShadowClassification;
import com.ccdev.drainage.domain.Models.ShadowReview;
import com.ccdev.drainage.domain.Models.ShadowSegmentRank;
import com.ccdev.drainage.repo.InspectionRepository;
import com.ccdev.drainage.repo.ProtocolRepository;
import com.ccdev.drainage.repo.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 规程目录与查询服务（只读聚合 + 人工复核写入）：
 *   - 规则差异：旧类别 → 新类别的拆分关系、必要字段与证据来源
 *   - 影子分类与受影响管段查询
 *   - 人工裁决单列保存，绝不回写影子分类、原等级或已发布数据集
 */
@Service
public class ProtocolCatalogService {

    private final ProtocolRepository protocolRepo;
    private final InspectionRepository inspectionRepo;
    private final UserRepository userRepo;

    public ProtocolCatalogService(ProtocolRepository protocolRepo, InspectionRepository inspectionRepo,
                                  UserRepository userRepo) {
        this.protocolRepo = protocolRepo;
        this.inspectionRepo = inspectionRepo;
        this.userRepo = userRepo;
    }

    public Map<String, Object> catalog() {
        List<Protocol> protocols = protocolRepo.findAllProtocols();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Protocol p : protocols) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("protocol", p);
            row.put("categories", protocolRepo.findCategories(p.id()));
            row.put("legacyCategories", protocolRepo.findLegacyCategories(p.id()));
            row.put("categoryCount", protocolRepo.findCategories(p.id()).size());
            rows.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocols", rows);
        result.put("fields", protocolRepo.findAllFields());
        return result;
    }

    /** 规则差异：新规程每个类别由哪些旧类别、字段条件、拆分产生。 */
    public Map<String, Object> ruleDiff(Long targetProtocolId, Long baseProtocolId) {
        Protocol target = protocolRepo.findProtocolById(targetProtocolId)
                .orElseThrow(() -> new ApiException(404, "规程不存在: " + targetProtocolId));
        Protocol base = baseProtocolId == null ? null
                : protocolRepo.findProtocolById(baseProtocolId).orElse(null);
        List<ProtocolCategory> targetCategories = protocolRepo.findCategories(target.id());
        List<ProtocolSplit> splits = protocolRepo.findSplitsToProtocol(target.id());
        List<ProtocolCategory> legacyCategories = base == null ? List.of()
                : protocolRepo.findLegacyCategories(base.id());
        List<ProtocolDependency> dependencies = protocolRepo.findDependencies(target.id());
        Map<String, ProtocolField> fieldByCode = new HashMap<>();
        for (ProtocolField f : protocolRepo.findAllFields()) {
            fieldByCode.put(f.fieldCode(), f);
        }
        Map<String, List<ProtocolSplit>> byTarget = new LinkedHashMap<>();
        for (ProtocolSplit s : splits) {
            byTarget.computeIfAbsent(s.toCategoryCode(), k -> new ArrayList<>()).add(s);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ProtocolCategory c : targetCategories) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("category", c);
            List<Map<String, Object>> sources = new ArrayList<>();
            for (ProtocolSplit s : byTarget.getOrDefault(c.categoryCode(), List.of())) {
                Map<String, Object> src = new LinkedHashMap<>();
                src.put("split", s);
                legacyCategories.stream()
                        .filter(l -> l.categoryCode().equals(s.fromCategoryCode()))
                        .findFirst().ifPresent(l -> src.put("legacyCategory", l));
                sources.add(src);
            }
            row.put("sources", sources);
            List<Map<String, Object>> requiredFields = new ArrayList<>();
            if (c.requiredFields() != null && !c.requiredFields().isBlank()) {
                for (String f : c.requiredFields().split(",")) {
                    String field = f.trim();
                    if (field.isEmpty()) {
                        continue;
                    }
                    Map<String, Object> fw = new LinkedHashMap<>();
                    fw.put("field", fieldByCode.get(field));
                    dependencies.stream()
                            .filter(d -> d.categoryCode().equals(c.categoryCode())
                                    && d.fieldName().equals(field))
                            .findFirst().ifPresent(d -> fw.put("dependency", d));
                    requiredFields.add(fw);
                }
            }
            row.put("requiredFields", requiredFields);
            rows.add(row);
        }
        // 旧类别 → 新类别 概览（拆分/合并/废弃）
        List<Map<String, Object>> legacyRows = new ArrayList<>();
        for (ProtocolCategory l : legacyCategories) {
            List<ProtocolSplit> out = protocolRepo.findSplitsFromCategory(l.protocolId(), l.categoryCode())
                    .stream().filter(sp -> sp.toProtocolId().equals(target.id())).toList();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("legacyCategory", l);
            row.put("splits", out);
            row.put("splitCount", out.size());
            legacyRows.add(row);
        }
        List<Map<String, Object>> deps = new ArrayList<>();
        for (ProtocolDependency d : dependencies) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("dependency", d);
            row.put("field", fieldByCode.get(d.fieldName()));
            deps.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("target", target);
        result.put("base", base);
        result.put("categories", rows);
        result.put("legacyCategories", legacyRows);
        result.put("dependencies", deps);
        result.put("deletedLegacyCategories", legacyRows.stream()
                .filter(r -> ((Number) r.get("splitCount")).intValue() == 0)
                .map(r -> ((ProtocolCategory) r.get("legacyCategory")).categoryCode())
                .toList());
        return result;
    }

    public Map<String, Object> runDetail(Long runId) {
        ProtocolRun run = protocolRepo.findRunById(runId)
                .orElseThrow(() -> new ApiException(404, "迁移运行不存在: " + runId));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("run", run);
        result.put("shadows", enrichedShadows(run));
        result.put("segmentRanks", segmentRanks(run));
        result.put("reviews", protocolRepo.findReviews(runId));
        return result;
    }

    public Map<String, Object> shadowQuery(Long runId, String defectType, Long batchId,
                                           String classification, String applicability) {
        protocolRepo.findRunById(runId)
                .orElseThrow(() -> new ApiException(404, "迁移运行不存在: " + runId));
        List<ShadowClassification> shadows = protocolRepo.findShadowsFiltered(runId, defectType, batchId,
                classification);
        if (applicability != null && !applicability.isBlank()) {
            shadows = shadows.stream().filter(s -> applicability.equals(s.applicability())).toList();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", runId);
        result.put("count", shadows.size());
        result.put("shadows", enrichedShadows(runId, shadows));
        return result;
    }

    public Map<String, Object> affectedSegments(Long runId, Boolean onlyAffected) {
        ProtocolRun run = protocolRepo.findRunById(runId)
                .orElseThrow(() -> new ApiException(404, "迁移运行不存在: " + runId));
        List<ShadowSegmentRank> ranks = protocolRepo.findSegmentRanks(runId);
        if (Boolean.TRUE.equals(onlyAffected)) {
            ranks = ranks.stream().filter(ShadowSegmentRank::affected).toList();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("run", run);
        result.put("segments", ranks);
        return result;
    }

    @Transactional
    public Map<String, Object> review(Long runId, Long shadowId, String decision, String chosenCategory,
                                      String note, Long userId, String username, String displayName) {
        ProtocolRun run = protocolRepo.findRunById(runId)
                .orElseThrow(() -> new ApiException(404, "迁移运行不存在: " + runId));
        ShadowClassification shadow = protocolRepo.findShadowById(shadowId)
                .orElseThrow(() -> new ApiException(404, "影子分类不存在: " + shadowId));
        if (!shadow.runId().equals(run.id())) {
            throw new IllegalArgumentException("影子分类 " + shadowId + " 不属于运行 " + run.runCode());
        }
        String normalized = decision == null ? "" : decision.trim().toUpperCase();
        Set<String> allowed = Set.of("ACCEPT_SHADOW", "KEEP_OLD", "NEEDS_VIDEO", "CONFIRM_CANDIDATE");
        if (!allowed.contains(normalized)) {
            throw new IllegalArgumentException("复核结论必须是 " + String.join(" / ", allowed) + " 之一");
        }
        if ("CONFIRM_CANDIDATE".equals(normalized) && (chosenCategory == null || chosenCategory.isBlank())) {
            throw new IllegalArgumentException("从候选中裁决时，必须指定 chosenCategory");
        }
        if ("CONFIRM_CANDIDATE".equals(normalized) && shadow.candidateCategories() != null
                && !shadow.candidateCategories().isBlank()
                && !List.of(shadow.candidateCategories().split("\\|")).contains(chosenCategory)) {
            throw new IllegalArgumentException("chosenCategory 必须是该观察的候选之一: "
                    + shadow.candidateCategories());
        }
        long reviewId = protocolRepo.insertReview(runId, shadowId, userId, normalized, chosenCategory, note);
        userRepo.audit(userId, "PROTOCOL_SHADOW_REVIEW", "shadow:" + shadowId,
                "运行 " + run.runCode() + "，观察#" + shadow.observationId() + "，结论 " + normalized
                        + (chosenCategory == null ? "" : "，采信 " + chosenCategory) + "，说明：" + note);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reviewId", reviewId);
        result.put("runId", runId);
        result.put("shadowId", shadowId);
        result.put("decision", normalized);
        result.put("reviewer", displayName);
        result.put("note", note);
        return result;
    }

    private List<Map<String, Object>> enrichedShadows(ProtocolRun run) {
        return enrichedShadows(run.id(), protocolRepo.findShadows(run.id()));
    }

    private List<Map<String, Object>> enrichedShadows(Long runId, List<ShadowClassification> shadows) {
        Map<Long, List<ShadowReview>> reviewByShadow = new HashMap<>();
        for (ShadowReview r : protocolRepo.findReviews(runId)) {
            reviewByShadow.computeIfAbsent(r.shadowId(), k -> new ArrayList<>()).add(r);
        }
        Map<Long, String> batchCode = new HashMap<>();
        for (var b : inspectionRepo.findAllBatches()) {
            batchCode.put(b.id(), b.batchCode());
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ShadowClassification s : shadows) {
            Observation obs = inspectionRepo.findObservationById(s.observationId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("shadow", s);
            row.put("rawGrade", obs == null ? null : obs.rawGrade());
            row.put("station", obs == null ? null : "[" + obs.stationStartM() + "," + obs.stationEndM() + "]");
            row.put("batchCode", s.batchId() == null ? null : batchCode.get(s.batchId()));
            row.put("reviews", reviewByShadow.getOrDefault(s.id(), List.of()));
            rows.add(row);
        }
        return rows;
    }

    private List<ShadowSegmentRank> segmentRanks(ProtocolRun run) {
        return protocolRepo.findSegmentRanks(run.id());
    }

    public List<ObservationField> observationFields(Long observationId) {
        return protocolRepo.findFieldsOfObservation(observationId);
    }

    /** 依赖索引变更：对比两次运行的依赖快照，列出受影响类别。 */
    public Map<String, Object> dependencyDiff(Long runId) {
        ProtocolRun run = protocolRepo.findRunById(runId)
                .orElseThrow(() -> new ApiException(404, "迁移运行不存在: " + runId));
        ProtocolRun previous = run.previousRunId() == null ? null
                : protocolRepo.findRunById(run.previousRunId()).orElse(null);
        List<ProtocolDependency> current = protocolRepo.findDependencies(run.targetProtocolId());
        Set<String> currentCodes = new LinkedHashSet<>();
        for (ProtocolDependency d : current) {
            currentCodes.add(d.categoryCode());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("run", run);
        result.put("previousRun", previous);
        result.put("snapshotChanged", previous != null
                && !java.util.Objects.equals(previous.dependencySnapshot(), run.dependencySnapshot()));
        result.put("dependencies", current);
        result.put("dependencyWarnings", run.dependencyWarnings());
        result.put("affectedCategories", run.affectedCategories());
        result.put("body", "依赖索引决定“哪些原始字段参与哪些新类别判定”：增量重算只重算受影响类别，"
                + "索引遗漏会留下旧口径，因此每次运行都保存依赖快照并按缺失项给出告警。");
        return result;
    }
}
