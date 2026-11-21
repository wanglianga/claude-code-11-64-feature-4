package com.ccdev.drainage.service;

import com.ccdev.drainage.config.ApiException;
import com.ccdev.drainage.domain.Models.AssetVersion;
import com.ccdev.drainage.domain.Models.Attribution;
import com.ccdev.drainage.domain.Models.ContractorBatch;
import com.ccdev.drainage.domain.Models.Observation;
import com.ccdev.drainage.domain.Models.ObservationField;
import com.ccdev.drainage.domain.Models.PipeSegment;
import com.ccdev.drainage.domain.Models.Protocol;
import com.ccdev.drainage.domain.Models.ProtocolCategory;
import com.ccdev.drainage.domain.Models.ProtocolDependency;
import com.ccdev.drainage.domain.Models.ProtocolField;
import com.ccdev.drainage.domain.Models.ProtocolRun;
import com.ccdev.drainage.domain.Models.ProtocolSplit;
import com.ccdev.drainage.domain.Models.SegmentMapping;
import com.ccdev.drainage.domain.Models.SegmentScore;
import com.ccdev.drainage.domain.Models.ShadowClassification;
import com.ccdev.drainage.domain.Models.ShadowSegmentRank;
import com.ccdev.drainage.domain.Models.Video;
import com.ccdev.drainage.dto.Requests;
import com.ccdev.drainage.repo.AnalysisRepository;
import com.ccdev.drainage.repo.AssetRepository;
import com.ccdev.drainage.repo.DatasetRepository;
import com.ccdev.drainage.repo.InspectionRepository;
import com.ccdev.drainage.repo.ProtocolRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 判读规程迁移运行：
 *   1. 读取已归档的原始观察 + 视频索引 + 视频索引里的归档字段，按新规程生成影子分类；
 *   2. 分类结果只写影子表，绝不覆盖原等级（app_observation.raw_grade）或已发布数据集；
 *   3. 证据不足（旧画面无法测量的字段）、候选歧义（缺少登记字段，一条旧类别对应多个新类别）、
 *      规程有效期边界（按检查发生日判断，而非导入日期）分别标注；
 *   4. 每次运行只增不改：再次修订规程只会产生新的运行，既有运行与其影子结果保持不变。
 */
@Service
public class ProtocolMigrationService {

    private static final BigDecimal PROXIMITY = new BigDecimal("0.500");

    private final ProtocolRepository protocolRepo;
    private final InspectionRepository inspectionRepo;
    private final AnalysisRepository analysisRepo;
    private final AssetRepository assetRepo;
    private final DatasetRepository datasetRepo;

    public ProtocolMigrationService(ProtocolRepository protocolRepo, InspectionRepository inspectionRepo,
                                    AnalysisRepository analysisRepo, AssetRepository assetRepo,
                                    DatasetRepository datasetRepo) {
        this.protocolRepo = protocolRepo;
        this.inspectionRepo = inspectionRepo;
        this.analysisRepo = analysisRepo;
        this.assetRepo = assetRepo;
        this.datasetRepo = datasetRepo;
    }

    // ================================================================ 运行入口
    @Transactional
    public Map<String, Object> run(Requests.ProtocolRunRequest req, Long userId) {
        Protocol target = protocolRepo.findProtocolById(req.targetProtocolId())
                .orElseThrow(() -> new ApiException(404, "新规程不存在: " + req.targetProtocolId()));
        String mode = "INCREMENTAL".equalsIgnoreCase(req.mode()) ? "INCREMENTAL" : "FULL_REPLAY";
        Protocol base = resolveBaseProtocol(target, req.baseProtocolId());
        ProtocolRun previous = resolvePreviousRun(target, base, req.previousRunId());
        AssetVersion asOf = resolveAsOfVersion(req.versionId());

        // ---------- 目录读数 ----------
        List<ProtocolCategory> categories = protocolRepo.findCategories(target.id());
        List<ProtocolField> fields = protocolRepo.findAllFields();
        Map<String, ProtocolField> fieldByCode = new HashMap<>();
        for (ProtocolField f : fields) {
            fieldByCode.put(f.fieldCode(), f);
        }
        List<ProtocolDependency> dependencies = protocolRepo.findDependencies(target.id());
        String snapshot = dependencySnapshot(dependencies);
        String dependencyWarnings = dependencyWarnings(categories, dependencies);
        if (previous != null && !Objects.equals(previous.dependencySnapshot(), snapshot)) {
            String changed = changedDependencyCategories(previous.dependencySnapshot(), snapshot);
            String note = "依赖索引相对上一运行发生变化（" + changed + "），本次已按新索引判定";
            if (!dependencyWarnings.isBlank()) {
                note += "；仍存在依赖索引缺口，增量重算可能沿用旧口径";
            }
            dependencyWarnings = dependencyWarnings.isBlank() ? note : dependencyWarnings + "；" + note;
        }

        // ---------- 增量范围 ----------
        Set<String> affected = resolveAffectedCategories(categories, dependencies, previous, mode,
                req.changedCategoryCodes());
        Set<String> affectedSourceCategories = new HashSet<>();
        for (ProtocolSplit sp : protocolRepo.findSplitsToProtocol(target.id())) {
            if (affected.contains(sp.toCategoryCode())) {
                affectedSourceCategories.add(sp.fromCategoryCode());
            }
        }
        Set<String> affectedDefectTypes = new HashSet<>();
        Map<String, ProtocolCategory> categoryByCode = new HashMap<>();
        for (ProtocolCategory c : categories) {
            categoryByCode.put(c.categoryCode(), c);
            if (affected.contains(c.categoryCode())) {
                affectedDefectTypes.add(c.defectType());
            }
        }

        String runCode = nextRunCode(target, mode);
        long runId = protocolRepo.insertRun(runCode, target.id(), base == null ? null : base.id(),
                base == null ? null : base.id(), asOf.id(), mode, userId,
                previous == null ? null : previous.id(), snapshot);

        // ---------- 上下文索引 ----------
        RunContext ctx = new RunContext();
        ctx.target = target;
        ctx.base = base;
        ctx.categories = categories;
        ctx.categoryByCode = categoryByCode;
        ctx.fieldByCode = fieldByCode;
        ctx.depIndex = new HashMap<>();
        for (ProtocolDependency d : dependencies) {
            ctx.depIndex.put(d.categoryCode() + "|" + d.fieldName(), d);
        }
        ctx.legacyByTypeGrade = new HashMap<>();
        if (base != null) {
            for (ProtocolCategory lc : protocolRepo.findLegacyCategories(base.id())) {
                for (int g = lc.minGrade(); g <= lc.maxGrade(); g++) {
                    ctx.legacyByTypeGrade.put(lc.defectType() + "|" + g, lc);
                }
            }
        }
        // 观察、视频、批次、归属、归档字段
        List<Observation> observations = inspectionRepo.findAllObservations();
        Map<Long, Video> videoById = new HashMap<>();
        for (Video v : inspectionRepo.findAllVideos()) {
            videoById.put(v.id(), v);
        }
        Map<Long, ContractorBatch> batchById = new HashMap<>();
        for (ContractorBatch b : inspectionRepo.findAllBatches()) {
            batchById.put(b.id(), b);
        }
        Map<Long, Attribution> attrByObs = new HashMap<>();
        for (Attribution a : analysisRepo.findAllAttributions()) {
            attrByObs.put(a.observationId(), a);
        }
        Map<Long, Map<String, String>> fieldsByObs = new HashMap<>();
        for (ObservationField f : protocolRepo.findAllObservationFields()) {
            fieldsByObs.computeIfAbsent(f.observationId(), k -> new HashMap<>())
                    .put(f.fieldName(), f.fieldValue());
        }
        Map<Long, List<Observation>> obsBySegment = new HashMap<>();
        for (Observation o : observations) {
            Attribution a = attrByObs.get(o.id());
            if (a != null) {
                obsBySegment.computeIfAbsent(a.segmentId(), k -> new ArrayList<>()).add(o);
            }
        }
        ctx.observations = observations;
        Map<Long, Observation> obsById = new HashMap<>();
        for (Observation o : observations) {
            obsById.put(o.id(), o);
        }
        ctx.obsById = obsById;
        ctx.videoById = videoById;
        ctx.batchById = batchById;
        ctx.attrByObs = attrByObs;
        ctx.fieldsByObs = fieldsByObs;
        ctx.obsBySegment = obsBySegment;

        // ---------- 复用上一运行的影子结果（仅增量） ----------
        Map<Long, ShadowClassification> reusable = new HashMap<>();
        if (mode.equals("INCREMENTAL") && previous != null) {
            for (ShadowClassification s : protocolRepo.findShadows(previous.id())) {
                reusable.put(s.observationId(), s);
            }
        }

        int shadowCount = 0;
        int gapCount = 0;
        int ambiguousCount = 0;
        int reusedCount = 0;
        int inScope = 0;
        int notApplicable = 0;
        Map<Long, ShadowClassification> newShadows = new LinkedHashMap<>();

        for (Observation obs : observations) {
            Video video = videoById.get(obs.videoId());
            ContractorBatch batch = batchById.get(obs.batchId());
            Attribution attr = attrByObs.get(obs.id());
            ShadowClassification shadow;
            boolean recompute = mode.equals("FULL_REPLAY")
                    || affectedDefectTypes.contains(obs.defectType())
                    || !reusable.containsKey(obs.id())
                    // 目标资产版本变化时影子顺位/投影需按新版本重算，不能沿用上一运行的投影
                    || (previous != null && !Objects.equals(previous.asOfVersionId(), asOf.id()));
            if (!recompute) {
                ShadowClassification prev = reusable.get(obs.id());
                // 旧类别被拆到本次受影响的新类别，其下游分类可能需要变化
                if (prev.sourceCategoryCode() != null
                        && affectedSourceCategories.contains(prev.sourceCategoryCode())) {
                    recompute = true;
                }
            }
            if (!recompute) {
                ShadowClassification prev = reusable.get(obs.id());
                shadow = new ShadowClassification(prev.id(), runId, prev.observationId(), prev.segmentId(),
                        prev.batchId(), prev.defectType(), prev.sourceCategoryCode(), prev.targetCategoryCode(),
                        prev.classification(), prev.oldGrade(), prev.newGrade(), prev.evidenceStrength(),
                        prev.missingFields(), prev.candidateCategories(), "[增量沿用] " + prev.basis(),
                        prev.applicability(),
                        prev.applicabilityNote(), prev.projectedSegmentId(), prev.projectedStartM(),
                        prev.projectedEndM(), prev.projectionNote(), prev.inspectedAt(), prev.evidenceUri(),
                        prev.videoUri(), staleRisk(prev, previous, snapshot));
                reusedCount++;
            } else {
                shadow = classify(ctx, obs, video, batch, attr, target, asOf, runId);
            }
            newShadows.put(obs.id(), shadow);
            protocolRepo.insertShadow(shadow);
            shadowCount++;
            if ("EVIDENCE_GAP".equals(shadow.classification())) {
                gapCount++;
            } else if ("AMBIGUOUS".equals(shadow.classification())) {
                ambiguousCount++;
            }
            if ("APPLIES".equals(shadow.applicability())) {
                inScope++;
            } else {
                notApplicable++;
            }
        }

        // ---------- 顺位影响 ----------
        Map<Long, SegmentScore> oldMax = latestScores(asOf.id(), "MAX_SEVERITY");
        Map<Long, SegmentScore> oldBurden = latestScores(asOf.id(), "BURDEN");
        List<PipeSegment> targetSegments = assetRepo.findSegmentsByVersion(asOf.id());
        Map<Long, BigDecimal> burdenMap = new HashMap<>();
        Map<Long, Integer> gradeMap = new HashMap<>();
        Map<Long, Integer> segGap = new HashMap<>();
        Map<Long, Integer> segAmbig = new HashMap<>();

        for (ShadowClassification s : newShadows.values()) {
            if (!"APPLIES".equals(s.applicability())) {
                continue;
            }
            if ("EVIDENCE_GAP".equals(s.classification()) && s.projectedSegmentId() != null) {
                segGap.merge(s.projectedSegmentId(), 1, Integer::sum);
            } else if ("AMBIGUOUS".equals(s.classification()) && s.projectedSegmentId() != null) {
                segAmbig.merge(s.projectedSegmentId(), 1, Integer::sum);
            }
            if (!"NEW_CATEGORY".equals(s.classification()) || s.projectedSegmentId() == null
                    || s.newGrade() == null) {
                continue;
            }
            Attribution attr = attrByObs.get(s.observationId());
            BigDecimal length = attr == null ? BigDecimal.ZERO
                    : projectedLength(attr, s);
            Observation burdenObs = obsById.get(s.observationId());
            BigDecimal weight = evidenceWeight(burdenObs == null ? null : burdenObs.evidenceStrength());
            BigDecimal part = length.multiply(BigDecimal.valueOf(s.newGrade())).multiply(weight)
                    .setScale(3, RoundingMode.HALF_UP);
            burdenMap.merge(s.projectedSegmentId(), part, BigDecimal::add);
            gradeMap.merge(s.projectedSegmentId(), s.newGrade(), Math::max);
        }

        // 影子顺位只依据已确定的新类别；证据缺口/候选歧义不清空顺位，而是在备注中标注“顺位为暂定”
        List<Long> rankable = targetSegments.stream()
                .filter(seg -> gradeMap.containsKey(seg.id()))
                .sorted(Comparator
                        .comparing((PipeSegment s) -> gradeMap.get(s.id()), Comparator.reverseOrder())
                        .thenComparing(s -> burdenMap.getOrDefault(s.id(), BigDecimal.ZERO),
                                Comparator.reverseOrder()))
                .map(PipeSegment::id).toList();
        Map<Long, Integer> shadowRanks = new HashMap<>();
        int rank = 1;
        for (Long segId : rankable) {
            shadowRanks.put(segId, rank++);
        }

        int affectedSegments = 0;
        int rankChanged = 0;
        for (PipeSegment seg : targetSegments) {
            SegmentScore om = oldMax.get(seg.id());
            SegmentScore ob = oldBurden.get(seg.id());
            Integer oldRank = om == null ? null : om.rankNo();
            Integer shadowRank = shadowRanks.get(seg.id());
            Integer delta = oldRank != null && shadowRank != null ? oldRank - shadowRank : null;
            boolean affectedSeg = (delta != null && delta != 0)
                    || (oldRank == null) != (shadowRank == null)
                    || segGap.getOrDefault(seg.id(), 0) > 0
                    || segAmbig.getOrDefault(seg.id(), 0) > 0;
            if (affectedSeg) {
                affectedSegments++;
            }
            if (delta != null && delta != 0) {
                rankChanged++;
            }
            StringBuilder note = new StringBuilder();
            note.append("旧发布口径=").append(om == null ? "无数据集" : "第" + (oldRank == null ? "—" : oldRank) + "位");
            note.append("；影子口径=").append(shadowRank == null ? "不排名" : "第" + shadowRank + "位");
            if (segGap.getOrDefault(seg.id(), 0) > 0) {
                note.append("；证据缺口 ").append(segGap.get(seg.id()))
                        .append(" 条（旧画面不可测量）待重检，顺位为暂定");
            }
            if (segAmbig.getOrDefault(seg.id(), 0) > 0) {
                note.append("；候选歧义 ").append(segAmbig.get(seg.id()))
                        .append(" 条待人工复核，顺位为暂定");
            }
            if (om != null && om.rankingPaused()) {
                note.append("；旧口径因冲突暂停排序");
            }
            protocolRepo.insertSegmentRank(new ShadowSegmentRank(null, runId, seg.id(), oldRank,
                    om == null ? null : om.maxGrade(), ob == null ? null : ob.burdenScore(),
                    shadowRank, gradeMap.get(seg.id()), burdenMap.getOrDefault(seg.id(), BigDecimal.ZERO),
                    delta, segGap.getOrDefault(seg.id(), 0), segAmbig.getOrDefault(seg.id(), 0),
                    affectedSeg, note.toString()));
        }

        // ---------- 运行汇总（只增不改） ----------
        String affectedCodes = String.join(",", new LinkedHashSet<>(affected));
        String summary = summaryText(mode, shadowCount, gapCount, ambiguousCount, reusedCount,
                inScope, notApplicable, affectedSegments, rankChanged);
        protocolRepo.completeRun(runId, observations.size(), shadowCount, gapCount, ambiguousCount,
                reusedCount, affectedCodes, summary, dependencyWarnings);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", runId);
        result.put("runCode", runCode);
        result.put("mode", mode);
        result.put("status", "COMPLETED");
        result.put("observationCount", observations.size());
        result.put("shadowCount", shadowCount);
        result.put("gapCount", gapCount);
        result.put("ambiguousCount", ambiguousCount);
        result.put("reusedCount", reusedCount);
        result.put("inScopeCount", inScope);
        result.put("notApplicableCount", notApplicable);
        result.put("affectedSegments", affectedSegments);
        result.put("rankChangedSegments", rankChanged);
        result.put("affectedCategories", affectedCodes.isEmpty() ? "全部（全量重放）" : affectedCodes);
        result.put("dependencyWarnings", dependencyWarnings);
        result.put("summary", summary);
        result.put("asOfVersionId", asOf.id());
        return result;
    }

    // ================================================================ 分类核心
    private ShadowClassification classify(RunContext ctx, Observation obs, Video video,
                                          ContractorBatch batch, Attribution attr,
                                          Protocol target, AssetVersion asOf, long runId) {
        Integer grade = batch == null ? null
                : inspectionRepo.normalizeGrade(batch.gradingStandard(), obs.rawGrade());
        Map<String, String> ownFields = ctx.fieldsByObs.getOrDefault(obs.id(), Map.of());

        LocalDate inspectionDate = batch == null ? null : batch.inspectedAt().toLocalDate();
        String applicability = applicability(target, inspectionDate);
        String applicabilityNote = applicabilityNote(target, inspectionDate, batch);

        Long segmentId = attr == null ? null : attr.segmentId();
        Long batchId = obs.batchId();
        String videoUri = video == null ? null : video.fileUri();

        if (video != null && video.duplicateOfId() != null) {
            return shadow(runId, obs, segmentId, batchId, null, null, "SKIPPED_DUPLICATE", grade, null,
                    null, null,
                    "视频 " + video.videoCode() + " 与视频#" + video.duplicateOfId()
                            + " 内容哈希相同（重复上传），其观察不参与新规程统计",
                    applicability, applicabilityNote, null, null, null, batch, video,
                    obs.evidenceUri(), videoUri, false);
        }

        ProtocolCategory legacy = grade == null ? null
                : ctx.legacyByTypeGrade.get(obs.defectType() + "|" + grade);
        if (legacy == null) {
            return shadow(runId, obs, segmentId, batchId, null, null, "OUT_OF_SCOPE", grade, null,
                    null, null,
                    "旧规程中不存在该缺陷类型/等级的类别（" + obs.defectType() + " 等级"
                            + (grade == null ? obs.rawGrade() : grade) + "），新规程无对应迁移",
                    applicability, applicabilityNote, project(ctx, attr, asOf), projectionNote(ctx, attr, asOf),
                    attr, batch, video, obs.evidenceUri(), videoUri, false);
        }

        // 旧类别拆分定义可能同时被多个修订版复用：只取“目标规程”自己的拆分
        List<ProtocolSplit> splits = ctx.base == null ? List.of()
                : protocolRepo.findSplitsFromCategory(ctx.base.id(), legacy.categoryCode()).stream()
                .filter(sp -> sp.toProtocolId().equals(target.id()))
                .toList();

        if (splits.isEmpty()) {
            return shadow(runId, obs, segmentId, batchId, legacy.categoryCode(), null, "OUT_OF_SCOPE",
                    grade, null, null, null,
                    "旧类别 " + legacy.categoryCode() + "（" + legacy.categoryName()
                            + "）在新规程中没有对应类别定义",
                    applicability, applicabilityNote, null, null, null, batch, video,
                    obs.evidenceUri(), videoUri, false);
        }

        List<Eval> evaluated = new ArrayList<>();
        for (ProtocolSplit split : splits) {
            ProtocolCategory to = ctx.categoryByCode.get(split.toCategoryCode());
            if (to == null || !Boolean.TRUE.equals(to.enabled())) {
                continue;
            }
            ProtocolRuleEngine.ExpressionResult er = ProtocolRuleEngine.evaluate(
                    ProtocolRuleEngine.parse(split.conditionExpr()),
                    field -> lookupField(ctx, obs, attr, ownFields, to.categoryCode(), field));
            evaluated.add(new Eval(split, to, er));
        }

        LinkedHashSet<String> matched = new LinkedHashSet<>();
        LinkedHashSet<String> ambiguous = new LinkedHashSet<>();
        LinkedHashSet<String> gapMissing = new LinkedHashSet<>();
        LinkedHashSet<String> ambigMissing = new LinkedHashSet<>();
        LinkedHashSet<String> gapCategories = new LinkedHashSet<>();

        for (Eval e : evaluated) {
            if (e.result.truth() == ProtocolRuleEngine.Truth.TRUE) {
                matched.add(e.category.categoryCode());
                continue;
            }
            if (e.result.truth() != ProtocolRuleEngine.Truth.UNKNOWN) {
                continue;
            }
            boolean unmeasurable = e.result.missingFields().stream()
                    .anyMatch(f -> recoverability(ctx, f).equals("NOT_MEASURABLE_FROM_ARCHIVE"));
            if (unmeasurable) {
                // 旧归档画面无法测量的字段（如裂缝宽度）：直接判证据不足，禁止由旧等级反推
                gapMissing.addAll(e.result.missingFields());
                gapCategories.add(e.category.categoryCode());
            } else {
                // 未登记但可复观察的字段（如渗漏迹象）：保留多个候选，不任选其一
                ambiguous.add(e.category.categoryCode());
                ambigMissing.addAll(e.result.missingFields());
            }
        }

        String basisPrefix = "旧类别 " + legacy.categoryCode() + "（" + legacy.categoryName() + "，等级"
                + grade + "）→ 新规程";
        if (!gapMissing.isEmpty()) {
            String missing = String.join(",", gapMissing);
            String basis = basisPrefix + "所需字段 [" + missing
                    + "] 在旧归档视频中无法辨认（不可测量）；规程禁止由旧等级反推宽度/挠度等实测值，"
                    + "本次标为证据不足，需重新下井或调阅原始视频复核"
                    + (matched.isEmpty() ? "" : "；已可确定的候选：" + String.join(" / ", matched));
            return shadow(runId, obs, segmentId, batchId, legacy.categoryCode(), null, "EVIDENCE_GAP",
                    grade, null, missing, matched.isEmpty() ? null : String.join("|", matched), basis,
                    applicability, applicabilityNote, project(ctx, attr, asOf), projectionNote(ctx, attr, asOf),
                    attr, batch, video, obs.evidenceUri(), videoUri, false);
        }
        if (ambiguous.isEmpty() && matched.size() == 1) {
            ProtocolCategory to = ctx.categoryByCode.get(matched.iterator().next());
            return shadow(runId, obs, segmentId, batchId, legacy.categoryCode(), to.categoryCode(),
                    "NEW_CATEGORY", grade, to.severityGrade(), null, null,
                    basisPrefix + "判定为 " + to.categoryCode() + "（" + to.categoryName() + "）："
                            + describeTrue(evaluated, to.categoryCode()),
                    applicability, applicabilityNote, project(ctx, attr, asOf), projectionNote(ctx, attr, asOf),
                    attr, batch, video, obs.evidenceUri(), videoUri, false);
        }
        if (!ambiguous.isEmpty()) {
            LinkedHashSet<String> candidates = new LinkedHashSet<>(matched);
            candidates.addAll(ambiguous);
            String missing = String.join(",", ambigMissing);
            String basis = basisPrefix + "缺少登记字段 [" + missing + "]，仍可能属于 "
                    + String.join(" / ", candidates) + "；按规程保留多个候选，不任选其一，等待人工复核";
            if (matched.isEmpty()) {
                basis = basisPrefix + "缺少登记字段 [" + missing + "]，各候选均无法排除："
                        + String.join(" / ", candidates) + "；保留多个候选，不任选其一";
            }
            return shadow(runId, obs, segmentId, batchId, legacy.categoryCode(), null, "AMBIGUOUS",
                    grade, null, missing, String.join("|", candidates), basis, applicability,
                    applicabilityNote, project(ctx, attr, asOf), projectionNote(ctx, attr, asOf),
                    attr, batch, video, obs.evidenceUri(), videoUri, false);
        }
        if (matched.size() > 1) {
            String basis = basisPrefix + "同时满足 " + String.join(" / ", matched)
                    + " 的判定条件；新类别之间并非互斥，保留多候选等待人工定级";
            return shadow(runId, obs, segmentId, batchId, legacy.categoryCode(), null, "AMBIGUOUS",
                    grade, null, null, String.join("|", matched), basis, applicability,
                    applicabilityNote, project(ctx, attr, asOf), projectionNote(ctx, attr, asOf),
                    attr, batch, video, obs.evidenceUri(), videoUri, false);
        }
        return shadow(runId, obs, segmentId, batchId, legacy.categoryCode(), null, "UNCLASSIFIED",
                grade, null, null, null,
                basisPrefix + "没有任何拆分条件满足（等级与字段组合不在新类别范围）",
                applicability, applicabilityNote, project(ctx, attr, asOf), projectionNote(ctx, attr, asOf),
                attr, batch, video, obs.evidenceUri(), videoUri, false);
    }

    /**
     * 字段取值：
     *   1. 观察自身归档字段；
     *   2. 派生字段 length_m / grade；
     *   3. 依赖索引声明为 CROSS_CATEGORY 的字段，取同一管段内里程重叠的同源缺陷观察值；
     *      没有同源观察时返回 NONE（阴性证据），同源观察取值冲突时返回 null（不可判定）。
     */
    private String lookupField(RunContext ctx, Observation obs, Attribution attr,
                               Map<String, String> ownFields, String categoryCode, String field) {
        String own = ownFields.get(field);
        if (own != null) {
            return own;
        }
        if ("length_m".equals(field) && attr != null) {
            return attr.normEndM().subtract(attr.normStartM()).toPlainString();
        }
        if ("grade".equals(field)) {
            ContractorBatch b = ctx.batchById.get(obs.batchId());
            Integer g = b == null ? null : inspectionRepo.normalizeGrade(b.gradingStandard(), obs.rawGrade());
            return g == null ? null : String.valueOf(g);
        }
        ProtocolDependency dep = ctx.depIndex.get(categoryCode + "|" + field);
        if (dep != null && "CROSS_CATEGORY".equals(dep.dependencyKind())) {
            return crossCategoryValue(ctx, obs, attr, dep);
        }
        return null;
    }

    private String crossCategoryValue(RunContext ctx, Observation obs, Attribution attr,
                                      ProtocolDependency dep) {
        if (attr == null) {
            return "NONE";
        }
        List<Observation> siblings = ctx.obsBySegment.getOrDefault(attr.segmentId(), List.of());
        List<String> values = new ArrayList<>();
        for (Observation sibling : siblings) {
            if (sibling.id().equals(obs.id()) || !sibling.defectType().equals(dep.sourceDefectType())) {
                continue;
            }
            Attribution siblingAttr = ctx.attrByObs.get(sibling.id());
            if (siblingAttr == null || !near(attr, siblingAttr)) {
                continue;
            }
            String v = ctx.fieldsByObs.getOrDefault(sibling.id(), Map.of()).get(dep.sourceField());
            if (v != null) {
                values.add(v);
            }
        }
        if (values.isEmpty()) {
            return "NONE";
        }
        return values.stream().distinct().count() == 1 ? values.get(0) : null;
    }

    private static boolean near(Attribution a, Attribution b) {
        BigDecimal aStart = a.normStartM().subtract(PROXIMITY);
        BigDecimal aEnd = a.normEndM().add(PROXIMITY);
        return aStart.compareTo(b.normEndM()) <= 0 && b.normStartM().compareTo(aEnd) <= 0;
    }

    private String recoverability(RunContext ctx, String field) {
        ProtocolField f = ctx.fieldByCode.get(field);
        return f == null ? "NOT_RECORDED" : f.recoverability();
    }

    // ================================================================ 投影与排序辅助
    private Long projectSegment(RunContext ctx, Attribution attr, AssetVersion asOf) {
        if (attr == null) {
            return null;
        }
        if (attr.assetVersionId().equals(asOf.id())) {
            return attr.segmentId();
        }
        for (SegmentMapping m : assetRepo.findMappingsFromOldSegment(attr.segmentId())) {
            PipeSegment newSeg = assetRepo.findSegmentById(m.newSegmentId());
            if (!newSeg.versionId().equals(asOf.id())) {
                continue;
            }
            if (attr.normStartM().compareTo(m.oldStartM()) >= 0
                    && attr.normEndM().compareTo(m.oldEndM()) <= 0) {
                return m.newSegmentId();
            }
        }
        return null;
    }

    private Long project(RunContext ctx, Attribution attr, AssetVersion asOf) {
        return projectSegment(ctx, attr, asOf);
    }

    private String projectionNote(RunContext ctx, Attribution attr, AssetVersion asOf) {
        if (attr == null) {
            return "观察无固定归属（对齐隔离），不投影到任何管段";
        }
        if (projectSegment(ctx, attr, asOf) != null) {
            return null;
        }
        return "归一区间[" + attr.normStartM() + "," + attr.normEndM()
                + "]跨越拆分边界或不在目标版本映射内，隔离不投影";
    }

    private BigDecimal projectedLength(Attribution attr, ShadowClassification s) {
        if (s.projectedStartM() == null || s.projectedEndM() == null) {
            return attr.normEndM().subtract(attr.normStartM());
        }
        return s.projectedEndM().subtract(s.projectedStartM());
    }

    private BigDecimal evidenceWeight(String evidence) {
        return switch (evidence == null ? "SPARSE" : evidence) {
            case "FULL" -> BigDecimal.ONE;
            case "PARTIAL" -> new BigDecimal("0.800");
            default -> new BigDecimal("0.600");
        };
    }

    private Map<Long, SegmentScore> latestScores(Long versionId, String mode) {
        Optional<Long> datasetId = protocolRepo.findLatestPublishedDataset(versionId, mode);
        Map<Long, SegmentScore> result = new HashMap<>();
        if (datasetId.isEmpty()) {
            return result;
        }
        for (SegmentScore s : datasetRepo.findScoresByDataset(datasetId.get())) {
            result.put(s.segmentId(), s);
        }
        return result;
    }

    // ================================================================ 运行参数解析
    /**
     * 旧规程（拆分来源）解析：
     *   显式指定 > 沿 parent 链回溯到“自带旧类别定义”的规程 > 生效日早于目标的最近规程。
     *   这样同一份旧类别以 from_protocol_id 指向该规程，修订版可直接复用拆分定义。
     */
    private Protocol resolveBaseProtocol(Protocol target, Long explicitBase) {
        if (explicitBase != null) {
            Protocol p = protocolRepo.findProtocolById(explicitBase)
                    .orElseThrow(() -> new ApiException(404, "旧规程不存在: " + explicitBase));
            return legacySourceOf(p);
        }
        if (target.parentProtocolId() != null) {
            Protocol parent = protocolRepo.findProtocolById(target.parentProtocolId()).orElse(null);
            if (parent != null) {
                return legacySourceOf(parent);
            }
        }
        return protocolRepo.findAllProtocols().stream()
                .filter(p -> !p.id().equals(target.id()))
                .filter(p -> p.effectiveFrom().isBefore(target.effectiveFrom()))
                .filter(p -> !protocolRepo.findLegacyCategories(p.id()).isEmpty())
                .max(Comparator.comparing(Protocol::effectiveFrom))
                .orElse(null);
    }

    /** 沿 parent 链找到第一份带有旧类别定义的规程。 */
    private Protocol legacySourceOf(Protocol p) {
        Protocol current = p;
        int guard = 0;
        while (current != null && guard++ < 20) {
            if (!protocolRepo.findLegacyCategories(current.id()).isEmpty()) {
                return current;
            }
            if (current.parentProtocolId() == null) {
                break;
            }
            current = protocolRepo.findProtocolById(current.parentProtocolId()).orElse(null);
        }
        return p;
    }

    private ProtocolRun resolvePreviousRun(Protocol target, Protocol base, Long explicitPrevious) {
        if (explicitPrevious != null) {
            return protocolRepo.findRunById(explicitPrevious)
                    .orElseThrow(() -> new ApiException(404, "上一运行不存在: " + explicitPrevious));
        }
        Optional<ProtocolRun> sameTarget = protocolRepo.findLatestCompletedRun(target.id());
        if (sameTarget.isPresent()) {
            return sameTarget.get();
        }
        // 修订版首次运行时，以被修订规程的最近一次运行为对照
        if (target.parentProtocolId() != null) {
            return protocolRepo.findLatestCompletedRun(target.parentProtocolId()).orElse(null);
        }
        return null;
    }

    private AssetVersion resolveAsOfVersion(Long explicitVersion) {
        if (explicitVersion != null) {
            return assetRepo.findVersionById(explicitVersion);
        }
        List<AssetVersion> versions = assetRepo.findAllVersions();
        return versions.stream().max(Comparator.comparing(AssetVersion::effectiveAt))
                .orElseThrow(() -> new ApiException(500, "系统中没有资产版本"));
    }

    private String nextRunCode(Protocol target, String mode) {
        long seq = protocolRepo.countRunsByTarget(target.id()) + 1;
        String base = "RUN-" + target.protocolCode() + "-" + mode + "-" + seq;
        String code = base;
        int suffix = 1;
        while (protocolRepo.existsRunCode(code)) {
            code = base + "-" + (++suffix);
        }
        return code;
    }

    // ================================================================ 增量范围与依赖
    private Set<String> resolveAffectedCategories(List<ProtocolCategory> categories,
                                                  List<ProtocolDependency> dependencies,
                                                  ProtocolRun previous, String mode,
                                                  List<String> changedFromRequest) {
        Set<String> affected = new LinkedHashSet<>();
        if ("FULL_REPLAY".equals(mode) || previous == null) {
            for (ProtocolCategory c : categories) {
                affected.add(c.categoryCode());
            }
            return affected;
        }
        LinkedHashSet<String> seed = new LinkedHashSet<>();
        if (changedFromRequest != null) {
            seed.addAll(changedFromRequest);
        }
        if (seed.isEmpty()) {
            seed.addAll(changedDependencyCategorySet(previous.dependencySnapshot(),
                    dependencySnapshot(dependencies)));
        }
        if (seed.isEmpty()) {
            // 无法确定变更范围：保守地全部重算，避免遗漏依赖留下旧口径
            for (ProtocolCategory c : categories) {
                affected.add(c.categoryCode());
            }
            return affected;
        }
        affected.addAll(seed);
        Map<String, ProtocolCategory> byCode = new HashMap<>();
        for (ProtocolCategory c : categories) {
            byCode.put(c.categoryCode(), c);
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            Set<String> defectTypes = new HashSet<>();
            for (String code : affected) {
                ProtocolCategory c = byCode.get(code);
                if (c != null) {
                    defectTypes.add(c.defectType());
                }
            }
            for (ProtocolCategory c : categories) {
                if (affected.contains(c.categoryCode())) {
                    continue;
                }
                boolean sameType = defectTypes.contains(c.defectType());
                boolean crossDep = dependencies.stream().anyMatch(d ->
                        d.categoryCode().equals(c.categoryCode())
                                && "CROSS_CATEGORY".equals(d.dependencyKind())
                                && defectTypes.contains(d.sourceDefectType()));
                if (sameType || crossDep) {
                    affected.add(c.categoryCode());
                    changed = true;
                }
            }
        }
        return affected;
    }

    private static String dependencySnapshot(List<ProtocolDependency> deps) {
        List<String> lines = new ArrayList<>();
        for (ProtocolDependency d : deps) {
            lines.add(d.categoryCode() + "|" + d.fieldName() + "|" + d.sourceField() + "|"
                    + d.sourceDefectType() + "|" + d.dependencyKind() + "|"
                    + (d.conditionExpr() == null ? "" : d.conditionExpr()));
        }
        lines.sort(String::compareTo);
        return String.join("\n", lines);
    }

    private static Set<String> changedDependencyCategorySet(String before, String after) {
        Set<String> changed = new LinkedHashSet<>();
        Map<String, String> b = snapshotByCategory(before);
        Map<String, String> a = snapshotByCategory(after);
        for (Map.Entry<String, String> e : a.entrySet()) {
            if (!Objects.equals(b.get(e.getKey()), e.getValue())) {
                changed.add(e.getKey());
            }
        }
        for (String k : b.keySet()) {
            if (!a.containsKey(k)) {
                changed.add(k);
            }
        }
        return changed;
    }

    private static Map<String, String> snapshotByCategory(String snapshot) {
        Map<String, String> map = new HashMap<>();
        if (snapshot == null || snapshot.isBlank()) {
            return map;
        }
        for (String line : snapshot.split("\n")) {
            String code = line.split("\\|", 2)[0];
            map.merge(code, line, (x, y) -> x + "\n" + y);
        }
        return map;
    }

    private static String changedDependencyCategories(String before, String after) {
        Set<String> changed = changedDependencyCategorySet(before, after);
        return changed.isEmpty() ? "无" : String.join(",", changed);
    }

    /** 依赖索引缺口：类别必要字段未登记依赖，或依赖指向未知字段。 */
    private static String dependencyWarnings(List<ProtocolCategory> categories,
                                             List<ProtocolDependency> dependencies) {
        Map<String, Set<String>> covered = new HashMap<>();
        List<String> unknown = new ArrayList<>();
        for (ProtocolDependency d : dependencies) {
            covered.computeIfAbsent(d.categoryCode(), k -> new HashSet<>()).add(d.fieldName());
        }
        for (ProtocolCategory c : categories) {
            if (!Boolean.TRUE.equals(c.enabled()) || c.requiredFields() == null
                    || c.requiredFields().isBlank()) {
                continue;
            }
            for (String f : c.requiredFields().split(",")) {
                String field = f.trim();
                if (!field.isEmpty() && !covered.getOrDefault(c.categoryCode(), Set.of()).contains(field)) {
                    unknown.add(c.categoryCode() + "." + field);
                }
            }
        }
        if (unknown.isEmpty()) {
            return "";
        }
        return "依赖索引缺口：" + String.join("，", unknown)
                + " 未登记；增量模式可能漏算这些字段，建议使用全量重放";
    }

    /**
     * 复用上一运行的影子结果时，若依赖索引已相对上一运行发生变化，则该结果可能仍沿用旧口径，
     * 标记 stale_risk 供审查视图优先复核。
     */
    private boolean staleRisk(ShadowClassification prev, ProtocolRun previous, String snapshot) {
        if (previous == null) {
            return false;
        }
        return !Objects.equals(previous.dependencySnapshot(), snapshot);
    }

    // ================================================================ 结果构造
    private ShadowClassification shadow(long runId, Observation obs, Long segmentId, Long batchId,
                                        String sourceCategory, String targetCategory,
                                        String classification, Integer oldGrade, Integer newGrade,
                                        String missingFields, String candidates, String basis,
                                        String applicability, String applicabilityNote,
                                        Long projectedSegmentId, String projectionNote,
                                        Attribution attr, ContractorBatch batch, Video video,
                                        String evidenceUri, String videoUri, boolean staleRisk) {
        BigDecimal pStart = null;
        BigDecimal pEnd = null;
        if (projectedSegmentId != null && attr != null) {
            if (attr.assetVersionId() != null) {
                SegmentMapping mapping = assetRepo.findMappingsFromOldSegment(attr.segmentId()).stream()
                        .filter(m -> m.newSegmentId().equals(projectedSegmentId))
                        .findFirst().orElse(null);
                if (mapping == null) {
                    pStart = attr.normStartM();
                    pEnd = attr.normEndM();
                } else {
                    pStart = scaleTo(attr.normStartM(), mapping);
                    pEnd = scaleTo(attr.normEndM(), mapping);
                }
            }
        }
        return new ShadowClassification(null, runId, obs.id(), segmentId, batchId, obs.defectType(),
                sourceCategory, targetCategory, classification, oldGrade, newGrade,
                obs.evidenceStrength(), missingFields, candidates, basis, applicability,
                applicabilityNote, projectedSegmentId, pStart, pEnd,
                projectionNote, batch == null ? null : batch.inspectedAt(), evidenceUri, videoUri,
                staleRisk);
    }

    private BigDecimal scaleTo(BigDecimal value, SegmentMapping m) {
        BigDecimal spanOld = m.oldEndM().subtract(m.oldStartM());
        BigDecimal spanNew = m.newEndM().subtract(m.newStartM());
        if (spanOld.signum() == 0) {
            return m.newStartM();
        }
        return m.newStartM().add(value.subtract(m.oldStartM()).multiply(spanNew)
                .divide(spanOld, 3, RoundingMode.HALF_UP)).setScale(3, RoundingMode.HALF_UP);
    }

    private static String describeTrue(List<Eval> evaluated, String code) {
        return evaluated.stream().filter(e -> e.category.categoryCode().equals(code))
                .findFirst().map(e -> e.split.conditionExpr() == null || e.split.conditionExpr().isBlank()
                        ? "无条件映射" : "条件 " + e.split.conditionExpr() + " 成立")
                .orElse("");
    }

    private String applicability(Protocol protocol, LocalDate inspectionDate) {
        if (inspectionDate == null) {
            return "APPLIES";
        }
        if (inspectionDate.isBefore(protocol.effectiveFrom())) {
            return "NOT_APPLICABLE_BEFORE";
        }
        if (protocol.effectiveTo() != null && inspectionDate.isAfter(protocol.effectiveTo())) {
            return "NOT_APPLICABLE_AFTER";
        }
        return "APPLIES";
    }

    private String applicabilityNote(Protocol protocol, LocalDate inspectionDate, ContractorBatch batch) {
        if (inspectionDate == null) {
            return "无检查时间，无法判断规程有效期边界";
        }
        String base = "检查发生日 " + inspectionDate + "，规程生效日 " + protocol.effectiveFrom()
                + (protocol.effectiveTo() == null ? "" : "，失效日 " + protocol.effectiveTo())
                + "；按检查发生日判断适用性，不按导入日期套用";
        if (inspectionDate.equals(protocol.effectiveFrom())) {
            return base + "；检查日与生效日同日，按生效处理";
        }
        if ("NOT_APPLICABLE_BEFORE".equals(applicability(protocol, inspectionDate))) {
            return base + "；检查早于生效日，新规程不适用于该次检查";
        }
        if ("NOT_APPLICABLE_AFTER".equals(applicability(protocol, inspectionDate))) {
            return base + "；检查晚于失效日，新规程不适用于该次检查";
        }
        return base + "；新规程适用于该次检查";
    }

    private static String summaryText(String mode, int shadowCount, int gap, int ambiguous, int reused,
                                      int inScope, int notApplicable, int affectedSegments, int rankChanged) {
        return ("INCREMENTAL".equals(mode) ? "增量重算" : "全量重放") + "：" + shadowCount
                + " 条观察生成影子结果，其中证据不足 " + gap + " 条、候选歧义 " + ambiguous
                + " 条、沿用上一运行 " + reused + " 条；规程适用 " + inScope + " 条，因有效期不适用 "
                + notApplicable + " 条；受影响管段 " + affectedSegments + " 段（顺位变化 " + rankChanged + " 段）";
    }

    private static final class RunContext {
        Protocol target;
        Protocol base;
        List<ProtocolCategory> categories;
        Map<String, ProtocolCategory> categoryByCode;
        Map<String, ProtocolField> fieldByCode;
        Map<String, ProtocolDependency> depIndex;
        Map<String, ProtocolCategory> legacyByTypeGrade;
        List<Observation> observations;
        Map<Long, Observation> obsById;
        Map<Long, Video> videoById;
        Map<Long, ContractorBatch> batchById;
        Map<Long, Attribution> attrByObs;
        Map<Long, Map<String, String>> fieldsByObs;
        Map<Long, List<Observation>> obsBySegment;
    }

    private record Eval(ProtocolSplit split, ProtocolCategory category,
                        ProtocolRuleEngine.ExpressionResult result) {
    }
}
