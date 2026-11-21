package com.ccdev.drainage;

import com.ccdev.drainage.domain.Models.ProtocolRun;
import com.ccdev.drainage.domain.Models.ShadowClassification;
import com.ccdev.drainage.domain.Models.ShadowSegmentRank;
import com.ccdev.drainage.dto.Requests;
import com.ccdev.drainage.repo.AnalysisRepository;
import com.ccdev.drainage.repo.AssetRepository;
import com.ccdev.drainage.repo.DatasetRepository;
import com.ccdev.drainage.repo.InspectionRepository;
import com.ccdev.drainage.repo.ProtocolRepository;
import com.ccdev.drainage.service.AlignmentService;
import com.ccdev.drainage.service.ClusteringService;
import com.ccdev.drainage.service.DatasetService;
import com.ccdev.drainage.service.ProtocolCatalogService;
import com.ccdev.drainage.service.ProtocolMigrationService;
import com.ccdev.drainage.service.ProtocolRuleEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 判读规程迁移集成测试：在独立库 ccdev_test 上验证
 *   全量重放 / 增量重算、类别拆分、字段不足、候选歧义、有效期边界、依赖索引变更、
 *   复核不回写、再次修订不改写既有运行。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProtocolMigrationIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired ProtocolRepository protocolRepo;
    @Autowired ProtocolMigrationService migrationService;
    @Autowired ProtocolCatalogService catalogService;
    @Autowired AnalysisRepository analysisRepo;
    @Autowired InspectionRepository inspectionRepo;
    @Autowired AlignmentService alignmentService;
    @Autowired ClusteringService clusteringService;
    @Autowired DatasetService datasetService;
    @Autowired AssetRepository assetRepo;
    @Autowired DatasetRepository datasetRepo;

    /** 独立重置测试库 schema/种子，保证本类可单独运行且与当前 schema.sql 一致。 */
    static {
        try (Connection conn = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/ccdev_test", "ccdev", "ccdev")) {
            try (var st = conn.createStatement()) {
                st.execute("DROP SCHEMA public CASCADE");
                st.execute("CREATE SCHEMA public");
            }
            ScriptUtils.executeSqlScript(conn, new ClassPathResource("db/schema.sql"));
            ScriptUtils.executeSqlScript(conn, new ClassPathResource("db/seed-data.sql"));
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private long cct2026;
    private long cct2026r1;
    private long fullRunId;
    private long incrementalRunId;
    private long revisionRunId;

    @BeforeAll
    void prepareRuns() {
        // 自愈：若上下文启动时未跑到流水线（例如其他测试类重置了库），在此补齐
        if (analysisRepo.countAttributions() == 0) {
            alignmentService.realignAll();
            clusteringService.rebuildClusters();
        }
        if (datasetRepo.countDatasets() == 0) {
            long latest = assetRepo.findAllVersions().stream().map(v -> v.id()).max(Long::compare).orElseThrow();
            datasetService.generateForVersion(latest, "MAX_SEVERITY", 1L);
            datasetService.generateForVersion(latest, "BURDEN", 1L);
        }
        cct2026 = protocolRepo.findProtocolByCode("CCT-2026").orElseThrow().id();
        cct2026r1 = protocolRepo.findProtocolByCode("CCT-2026-R1").orElseThrow().id();

        Map<String, Object> full = migrationService.run(new Requests.ProtocolRunRequest(
                cct2026, null, "FULL_REPLAY", null, null, null, "全量重放"), 1L);
        fullRunId = (long) full.get("runId");

        Map<String, Object> inc = migrationService.run(new Requests.ProtocolRunRequest(
                cct2026, null, "INCREMENTAL", fullRunId, null, List.of("CRACK_NARROW"),
                "仅裂缝窄类变更"), 1L);
        incrementalRunId = (long) inc.get("runId");

        Map<String, Object> rev = migrationService.run(new Requests.ProtocolRunRequest(
                cct2026r1, null, "FULL_REPLAY", null, null, null, "修订版全量重放"), 1L);
        revisionRunId = (long) rev.get("runId");
    }

    private ShadowClassification shadow(long runId, long observationId) {
        return protocolRepo.findShadows(runId).stream()
                .filter(s -> s.observationId() == observationId).findFirst().orElseThrow();
    }

    private ShadowSegmentRank rank(long runId, long segmentId) {
        return protocolRepo.findSegmentRanks(runId).stream()
                .filter(r -> r.segmentId() == segmentId).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------ 1. 全量重放与拆分
    @Test
    @Order(1)
    void fullReplayClassifiesAllArchivedObservationsIntoSplitCategories() {
        ProtocolRun run = protocolRepo.findRunById(fullRunId).orElseThrow();
        assertEquals("COMPLETED", run.status());
        assertEquals("FULL_REPLAY", run.mode());
        assertEquals(inspectionRepo.findAllObservations().size(), run.observationCount());
        assertEquals(run.observationCount(), run.shadowCount());

        // OBS-3（裂缝 II，旧 LEG-CRACK）有宽度 1.5mm ⇒ 新规程窄裂缝，等级映射为 2（B→2）
        ShadowClassification narrow = shadow(fullRunId, 3L);
        assertEquals("NEW_CATEGORY", narrow.classification());
        assertEquals("CRACK_NARROW", narrow.targetCategoryCode());
        assertEquals(2, narrow.newGrade());
        assertEquals(2, narrow.oldGrade());
        assertFalse(narrow.staleRisk());

        // OBS-13（裂缝 III，宽度 2.4mm + 渗漏 TRUE）⇒ 宽裂缝伴渗漏，新等级 4（严重度提升）
        ShadowClassification wide = shadow(fullRunId, 13L);
        assertEquals("NEW_CATEGORY", wide.classification());
        assertEquals("CRACK_WIDE_SEEPS", wide.targetCategoryCode());
        assertEquals(4, wide.newGrade());
        assertEquals(3, wide.oldGrade());

        // 原等级不被覆盖：观察 raw_grade 保持原始标准写法（B-2025-02 为 DX 标准）
        assertEquals("B", inspectionRepo.findObservationById(3L).rawGrade());
        assertEquals("C", inspectionRepo.findObservationById(13L).rawGrade());
    }

    // ------------------------------------------------------------ 2. 证据不足
    @Test
    @Order(2)
    void missingUnmeasurableWidthIsEvidenceGapNotInferredFromOldGrade() {
        // OBS-9（裂缝 II，旧画面缺宽度）：必须证据不足，且不得由旧等级推出宽度或新等级
        ShadowClassification gap = shadow(fullRunId, 9L);
        assertEquals("EVIDENCE_GAP", gap.classification());
        assertNull(gap.targetCategoryCode());
        assertNull(gap.newGrade());
        assertTrue(gap.missingFields().contains("crack_width_mm"));
        assertTrue(gap.basis().contains("无法辨认"));
        assertTrue(gap.basis().contains("反推"));

        // OBS-10（裂缝 III，缺宽度）同样证据不足
        assertEquals("EVIDENCE_GAP", shadow(fullRunId, 10L).classification());
        // OBS-4（变形 D，缺挠度）证据不足
        ShadowClassification defGap = shadow(fullRunId, 4L);
        assertEquals("EVIDENCE_GAP", defGap.classification());
        assertTrue(defGap.missingFields().contains("deflection_pct"));

        // 证据缺口计入管段备注，但不阻断其他已确定类别排名
        ShadowSegmentRank ws001 = rank(fullRunId, 4L);
        assertTrue(ws001.gapCount() >= 1);
        assertTrue(ws001.note().contains("证据缺口"));
    }

    // ------------------------------------------------------------ 3. 候选歧义
    @Test
    @Order(3)
    void missingSeepageKeepsBothCandidatesInsteadOfPickingOne() {
        // OBS-1（裂缝 II，有宽度 3.2mm，但无渗漏登记）⇒ 伴渗漏/无渗漏两候选均保留
        ShadowClassification amb = shadow(fullRunId, 1L);
        assertEquals("AMBIGUOUS", amb.classification());
        assertNull(amb.targetCategoryCode());
        assertNotNull(amb.candidateCategories());
        assertTrue(amb.candidateCategories().contains("CRACK_WIDE_SEEPS"));
        assertTrue(amb.candidateCategories().contains("CRACK_WIDE_DRY"));
        assertTrue(amb.basis().contains("不任选其一"));
        assertTrue(amb.missingFields().contains("seepage"));

        // 接口错位 OBS-5：自身无错位量，CCT-2026 依赖索引未登记纵向错位的跨类别依赖
        // ⇒ 只能保留“纵向错位”候选，不能任选，判为候选歧义
        ShadowClassification joint = shadow(fullRunId, 5L);
        assertEquals("AMBIGUOUS", joint.classification());
        assertTrue(joint.candidateCategories().contains("JOINT_VERTICAL"));
        assertTrue(joint.missingFields().contains("offset_mm"));
    }

    // ------------------------------------------------------------ 4. 有效期边界
    @Test
    @Order(4)
    void applicabilityFollowsInspectionDateNotImportDate() {
        // R1 自 2026-01-01 生效：B-2025-01（2025-03）与 B-2025-02（2025-09）均早于生效日
        ShadowClassification before = shadow(revisionRunId, 1L);
        assertEquals("NOT_APPLICABLE_BEFORE", before.applicability());
        assertTrue(before.applicabilityNote().contains("检查发生日"));
        assertTrue(before.applicabilityNote().contains("不按导入日期"));

        ShadowClassification before2 = shadow(revisionRunId, 13L);
        assertEquals("NOT_APPLICABLE_BEFORE", before2.applicability());

        // B-2026-03（2026-03-05）在生效日之后 ⇒ 适用
        ShadowClassification after = shadow(revisionRunId, 9L);
        assertEquals("APPLIES", after.applicability());

        // CCT-2026（2025-03-01 生效）对 B-2025-02 与 B-2026-03 均适用
        assertEquals("APPLIES", shadow(fullRunId, 13L).applicability());
        assertEquals("APPLIES", shadow(fullRunId, 9L).applicability());
    }

    // ------------------------------------------------------------ 5. 依赖索引变更 + 增量
    @Test
    @Order(5)
    void dependencyIndexGapIsFlaggedAndRevisionsDoNotRewriteExistingRuns() {
        ProtocolRun full = protocolRepo.findRunById(fullRunId).orElseThrow();
        assertTrue(full.dependencyWarnings().contains("依赖索引缺口"));
        assertTrue(full.dependencyWarnings().contains("JOINT_VERTICAL.offset_mm"));
        String fullSnapshot = full.dependencySnapshot();

        // 再次修订（R1 补齐依赖）只产生新运行，不修改既有运行
        ProtocolRun revision = protocolRepo.findRunById(revisionRunId).orElseThrow();
        assertNotEquals(fullSnapshot, revision.dependencySnapshot());
        ProtocolRun fullReload = protocolRepo.findRunById(fullRunId).orElseThrow();
        assertEquals(fullSnapshot, fullReload.dependencySnapshot());
        assertTrue(fullReload.dependencyWarnings().contains("JOINT_VERTICAL"));

        // 增量运行只重算受影响类别，其余观察沿用
        ProtocolRun inc = protocolRepo.findRunById(incrementalRunId).orElseThrow();
        assertEquals("INCREMENTAL", inc.mode());
        assertTrue(inc.reusedCount() > 0);
        assertTrue(inc.affectedCategories().contains("CRACK_NARROW"));
        // 沿用了上一运行的观察，其 basis 前缀标识增量
        ShadowClassification reused = shadow(incrementalRunId, 4L); // 变形，未被选中重算
        assertTrue(reused.basis().startsWith("[增量沿用]"));
    }

    // ------------------------------------------------------------ 6. 跨类别依赖（R1 补齐）
    @Test
    @Order(6)
    void crossCategoryDependencyResolvesJointWhenIndexIsComplete() {
        // CCT-2026 未登记 JOINT_VERTICAL.offset_mm，OBS-5 只能候选歧义
        assertEquals("AMBIGUOUS", shadow(fullRunId, 5L).classification());
        // R1 补齐依赖后，OBS-5 自身无错位量，但同管段重叠观察 OBS-6 的 22mm 使其可判定为纵向错位
        ShadowClassification r1 = shadow(revisionRunId, 5L);
        assertEquals("NEW_CATEGORY", r1.classification());
        assertEquals("JOINT_VERTICAL", r1.targetCategoryCode());
    }

    // ------------------------------------------------------------ 7. 顺位影响
    @Test
    @Order(7)
    void shadowRanksCompareAgainstPublishedRanksWithoutOverwriting() {
        List<ShadowSegmentRank> ranks = protocolRepo.findSegmentRanks(fullRunId);
        assertFalse(ranks.isEmpty());
        boolean anyWithOldRank = ranks.stream().anyMatch(r -> r.oldPublishedRank() != null);
        assertTrue(anyWithOldRank, "影子顺位应与已发布数据集对照");
        // 新旧顺位分别保存：旧顺位来自 app_dataset_segment_score，未被改写
        Long published = protocolRepo.findLatestPublishedDataset(2L, "MAX_SEVERITY").orElseThrow();
        long scoreCount = jdbc.queryForObject(
                "SELECT count(*) FROM app_dataset_segment_score WHERE dataset_id = ?", Long.class, published);
        assertTrue(scoreCount > 0);
        // 影子管段排序独立于已发布数据集
        ShadowSegmentRank ws001 = rank(fullRunId, 4L);
        assertTrue(ws001.note().contains("旧发布口径"));
        assertTrue(ws001.note().contains("影子口径"));
    }

    // ------------------------------------------------------------ 8. 人工复核独立保存
    @Test
    @Order(8)
    void reviewIsStoredSeparatelyAndNeverOverwritesShadowOrRawGrade() {
        ShadowClassification amb = shadow(fullRunId, 1L);
        int reviewBefore = protocolRepo.findReviews(fullRunId).size();
        catalogService.review(fullRunId, amb.id(), "CONFIRM_CANDIDATE", "CRACK_WIDE_DRY",
                "复核原始视频帧，确认无渗漏", 2L, "engineer", "复核工程师");

        assertEquals(reviewBefore + 1, protocolRepo.findReviews(fullRunId).size());
        // 影子分类本身不被复核改写
        ShadowClassification reloaded = protocolRepo.findShadowById(amb.id()).orElseThrow();
        assertEquals("AMBIGUOUS", reloaded.classification());
        assertNull(reloaded.targetCategoryCode());
        // 原等级不被覆盖
        assertEquals("II", inspectionRepo.findObservationById(1L).rawGrade());
        // 复核必须从候选内选择
        assertThrows(IllegalArgumentException.class, () -> catalogService.review(
                fullRunId, amb.id(), "CONFIRM_CANDIDATE", "NOT_A_CANDIDATE", "越界候选", 2L,
                "engineer", "复核工程师"));
    }

    // ------------------------------------------------------------ 9. 规则差异与查询
    @Test
    @Order(9)
    void ruleDiffAndQueriesCoverSplitGapAndDependency() {
        Map<String, Object> diff = catalogService.ruleDiff(cct2026, null);
        assertNotNull(diff.get("categories"));
        assertNotNull(diff.get("legacyCategories"));
        assertNotNull(diff.get("dependencies"));

        // 按缺陷类型 + 来源批次过滤
        Map<String, Object> byType = catalogService.shadowQuery(fullRunId, "CRACK", null, null, null);
        assertTrue((int) byType.get("count") > 0);
        Map<String, Object> byBatch = catalogService.shadowQuery(fullRunId, "CRACK", 2L, null, null);
        assertEquals(2, byBatch.get("count")); // B-2025-02 的 OBS-3（窄）与 OBS-13（宽伴渗漏）
        Map<String, Object> gaps = catalogService.shadowQuery(fullRunId, null, null, "EVIDENCE_GAP", null);
        assertTrue((int) gaps.get("count") >= 3);

        // 受影响管段查询
        Map<String, Object> affected = catalogService.affectedSegments(fullRunId, true);
        assertNotNull(affected.get("segments"));

        // 依赖差异查询
        Map<String, Object> depDiff = catalogService.dependencyDiff(revisionRunId);
        assertTrue((boolean) depDiff.get("snapshotChanged"));
    }

    // ------------------------------------------------------------ 11. 有效期上界
    @Test
    @Order(11)
    void applicabilityAfterEndDateIsMarkedNotApplicable() {
        jdbc.update("INSERT INTO app_protocol(protocol_code, name, effective_from, effective_to, "
                        + "approval_source, status, note) VALUES ('CCT-PILOT-TEST', '限时试行规程', "
                        + "DATE '2021-01-01', DATE '2021-12-31', '测试用', 'SUPERSEDED', '测试有效期上界') "
                        + "ON CONFLICT (protocol_code) DO NOTHING");
        long pilotId = protocolRepo.findProtocolByCode("CCT-PILOT-TEST").orElseThrow().id();
        Map<String, Object> run = migrationService.run(new Requests.ProtocolRunRequest(
                pilotId, null, "FULL_REPLAY", null, null, null, "有效期上界测试"), 1L);
        long pilotRunId = (long) run.get("runId");
        List<ShadowClassification> rows = protocolRepo.findShadows(pilotRunId);
        assertFalse(rows.isEmpty());
        assertTrue(rows.stream().allMatch(r -> "NOT_APPLICABLE_AFTER".equals(r.applicability())));
        assertTrue(rows.get(0).applicabilityNote().contains("晚于失效日"));
    }

    // ------------------------------------------------------------ 12. 只增不改
    @Test
    @Order(12)
    void rerunAppendsNewRunWithoutTouchingEarlierResults() {
        List<ShadowClassification> before = protocolRepo.findShadows(fullRunId);
        Map<String, Object> again = migrationService.run(new Requests.ProtocolRunRequest(
                cct2026, null, "FULL_REPLAY", null, null, null, "重复全量重放"), 1L);
        long secondRunId = (long) again.get("runId");
        assertNotEquals(fullRunId, secondRunId);

        List<ShadowClassification> after = protocolRepo.findShadows(fullRunId);
        assertEquals(before.size(), after.size());
        for (int i = 0; i < before.size(); i++) {
            assertEquals(before.get(i).classification(), after.get(i).classification());
            assertEquals(before.get(i).targetCategoryCode(), after.get(i).targetCategoryCode());
            assertEquals(before.get(i).basis(), after.get(i).basis());
        }
        // 新运行与原运行互不影响，且各自保留完整观察数
        assertEquals(before.size(), protocolRepo.findShadows(secondRunId).size());
    }

    // ------------------------------------------------------------ 10. 规则引擎三态
    @Test
    @Order(10)
    void ruleEngineEvaluatesNumericBooleanAndUnknown() {
        var cond = ProtocolRuleEngine.parse("crack_width_mm >= 2 AND seepage = TRUE");
        var result = ProtocolRuleEngine.evaluate(cond, field -> switch (field) {
            case "crack_width_mm" -> "2.5";
            case "seepage" -> "TRUE";
            default -> null;
        });
        assertEquals(ProtocolRuleEngine.Truth.TRUE, result.truth());

        var unknown = ProtocolRuleEngine.evaluate(cond, field -> switch (field) {
            case "crack_width_mm" -> "3.0";
            default -> null;
        });
        assertEquals(ProtocolRuleEngine.Truth.UNKNOWN, unknown.truth());
        assertEquals(List.of("seepage"), unknown.missingFields());

        var falsey = ProtocolRuleEngine.evaluate(cond, field -> switch (field) {
            case "crack_width_mm" -> "1.0";
            case "seepage" -> "TRUE";
            default -> null;
        });
        assertEquals(ProtocolRuleEngine.Truth.FALSE, falsey.truth());
    }
}
