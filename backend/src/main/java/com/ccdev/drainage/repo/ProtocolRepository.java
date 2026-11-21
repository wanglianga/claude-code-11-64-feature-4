package com.ccdev.drainage.repo;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 判读规程目录 / 迁移运行 / 影子分类 / 人工复核 的数据访问层。 */
@Repository
public class ProtocolRepository {

    private final JdbcTemplate jdbc;

    public ProtocolRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Protocol> PROTOCOL_MAPPER = (rs, n) -> new Protocol(
            rs.getLong("id"), rs.getString("protocol_code"), rs.getString("name"),
            rs.getDate("effective_from").toLocalDate(),
            rs.getDate("effective_to") == null ? null : rs.getDate("effective_to").toLocalDate(),
            rs.getString("approval_source"), rs.getString("status"),
            rs.getObject("parent_protocol_id", Long.class), rs.getString("note"));

    private static final RowMapper<ProtocolField> FIELD_MAPPER = (rs, n) -> new ProtocolField(
            rs.getLong("id"), rs.getString("field_code"), rs.getString("field_name"),
            rs.getString("value_type"), rs.getString("unit"), rs.getString("recoverability"),
            rs.getString("description"));

    private static final RowMapper<ProtocolCategory> CATEGORY_MAPPER = (rs, n) -> new ProtocolCategory(
            rs.getLong("id"), rs.getLong("protocol_id"), rs.getString("category_code"),
            rs.getString("category_name"), rs.getString("defect_type"),
            rs.getInt("min_grade"), rs.getInt("max_grade"), rs.getString("required_fields"),
            rs.getInt("severity_grade"), rs.getBoolean("enabled"), rs.getString("note"));

    private static final RowMapper<ProtocolSplit> SPLIT_MAPPER = (rs, n) -> new ProtocolSplit(
            rs.getLong("id"), rs.getLong("from_protocol_id"), rs.getString("from_category_code"),
            rs.getLong("to_protocol_id"), rs.getString("to_category_code"),
            rs.getString("condition_expr"), rs.getInt("priority"), rs.getString("note"));

    private static final RowMapper<ProtocolDependency> DEP_MAPPER = (rs, n) -> new ProtocolDependency(
            rs.getLong("id"), rs.getLong("protocol_id"), rs.getString("category_code"),
            rs.getString("field_name"), rs.getString("source_field"), rs.getString("source_defect_type"),
            rs.getString("dependency_kind"), rs.getString("condition_expr"), rs.getString("note"));

    private static final RowMapper<ProtocolRun> RUN_MAPPER = (rs, n) -> new ProtocolRun(
            rs.getLong("id"), rs.getString("run_code"), rs.getLong("target_protocol_id"),
            rs.getObject("base_protocol_id", Long.class),
            rs.getObject("source_protocol_id", Long.class),
            rs.getObject("as_of_version_id", Long.class),
            rs.getString("mode"), rs.getString("status"),
            rs.getTimestamp("started_at").toLocalDateTime(),
            rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toLocalDateTime(),
            rs.getObject("created_by", Long.class), rs.getInt("observation_count"),
            rs.getInt("shadow_count"), rs.getInt("gap_count"), rs.getInt("ambiguous_count"),
            rs.getInt("reused_count"), rs.getString("affected_categories"),
            rs.getObject("previous_run_id", Long.class), rs.getString("summary"),
            rs.getString("dependency_snapshot"), rs.getString("dependency_warnings"));

    private static final RowMapper<ShadowClassification> SHADOW_MAPPER = (rs, n) -> new ShadowClassification(
            rs.getLong("id"), rs.getLong("run_id"), rs.getLong("observation_id"),
            rs.getObject("segment_id", Long.class), rs.getObject("batch_id", Long.class),
            rs.getString("defect_type"), rs.getString("source_category_code"),
            rs.getString("target_category_code"), rs.getString("classification"),
            rs.getObject("old_grade", Integer.class), rs.getObject("new_grade", Integer.class),
            rs.getString("evidence_strength"), rs.getString("missing_fields"),
            rs.getString("candidate_categories"),
            rs.getString("basis"), rs.getString("applicability"), rs.getString("applicability_note"),
            rs.getObject("projected_segment_id", Long.class), rs.getBigDecimal("projected_start_m"),
            rs.getBigDecimal("projected_end_m"), rs.getString("projection_note"),
            rs.getTimestamp("inspected_at") == null ? null : rs.getTimestamp("inspected_at").toLocalDateTime(),
            rs.getString("evidence_uri"), rs.getString("video_uri"), rs.getBoolean("stale_risk"));

    private static final RowMapper<ShadowSegmentRank> RANK_MAPPER = (rs, n) -> new ShadowSegmentRank(
            rs.getLong("id"), rs.getLong("run_id"), rs.getLong("segment_id"),
            rs.getObject("old_published_rank", Integer.class),
            rs.getObject("old_published_max_grade", Integer.class),
            rs.getBigDecimal("old_published_burden"),
            rs.getObject("shadow_rank", Integer.class), rs.getObject("shadow_max_grade", Integer.class),
            rs.getBigDecimal("shadow_burden"), rs.getObject("rank_delta", Integer.class),
            rs.getInt("gap_count"), rs.getInt("ambiguous_count"), rs.getBoolean("affected"),
            rs.getString("note"));

    private static final RowMapper<ShadowReview> REVIEW_MAPPER = (rs, n) -> new ShadowReview(
            rs.getLong("id"), rs.getLong("run_id"), rs.getLong("shadow_id"),
            rs.getObject("reviewer_id", Long.class), rs.getString("decision"),
            rs.getString("chosen_category"), rs.getString("note"),
            rs.getTimestamp("created_at").toLocalDateTime());

    // ------------------------------------------------------------ 规程目录
    public List<Protocol> findAllProtocols() {
        return jdbc.query("SELECT * FROM app_protocol ORDER BY effective_from, id", PROTOCOL_MAPPER);
    }

    public Optional<Protocol> findProtocolById(Long id) {
        return jdbc.query("SELECT * FROM app_protocol WHERE id = ?", PROTOCOL_MAPPER, id).stream().findFirst();
    }

    public Optional<Protocol> findProtocolByCode(String code) {
        return jdbc.query("SELECT * FROM app_protocol WHERE protocol_code = ?", PROTOCOL_MAPPER, code)
                .stream().findFirst();
    }

    public List<ProtocolField> findAllFields() {
        return jdbc.query("SELECT * FROM app_protocol_field ORDER BY field_code", FIELD_MAPPER);
    }

    public List<ProtocolCategory> findCategories(Long protocolId) {
        return jdbc.query("SELECT * FROM app_protocol_category WHERE protocol_id = ? ORDER BY defect_type, min_grade, category_code",
                CATEGORY_MAPPER, protocolId);
    }

    public List<ProtocolCategory> findAllCategories() {
        return jdbc.query("SELECT * FROM app_protocol_category ORDER BY protocol_id, defect_type, category_code",
                CATEGORY_MAPPER);
    }

    public List<ProtocolSplit> findSplitsFromCategory(Long protocolId, String fromCategoryCode) {
        return jdbc.query("SELECT * FROM app_protocol_split WHERE from_protocol_id = ? AND from_category_code = ? "
                + "ORDER BY priority, to_category_code", SPLIT_MAPPER, protocolId, fromCategoryCode);
    }

    public List<ProtocolSplit> findSplitsToProtocol(Long toProtocolId) {
        return jdbc.query("SELECT * FROM app_protocol_split WHERE to_protocol_id = ? ORDER BY priority, id",
                SPLIT_MAPPER, toProtocolId);
    }

    public List<ProtocolSplit> findAllSplits() {
        return jdbc.query("SELECT * FROM app_protocol_split ORDER BY from_protocol_id, priority, id", SPLIT_MAPPER);
    }

    public List<ProtocolDependency> findDependencies(Long protocolId) {
        return jdbc.query("SELECT * FROM app_protocol_dependency WHERE protocol_id = ? "
                + "ORDER BY category_code, field_name", DEP_MAPPER, protocolId);
    }

    /** 旧规程的类别定义快照（规则差异对比用）。 */
    public List<ProtocolCategory> findLegacyCategories(Long protocolId) {
        return jdbc.query("SELECT id, protocol_id, category_code, category_name, defect_type, "
                        + "min_grade, max_grade, '' AS required_fields, min_grade AS severity_grade, "
                        + "true AS enabled, note FROM app_protocol_legacy_category WHERE protocol_id = ? "
                        + "ORDER BY defect_type, min_grade",
                CATEGORY_MAPPER, protocolId);
    }

    // ------------------------------------------------------------ 观察字段
    public List<ObservationField> findFieldsOfObservation(Long observationId) {
        return jdbc.query("SELECT * FROM app_observation_field WHERE observation_id = ? ORDER BY field_name",
                (rs, n) -> new ObservationField(rs.getLong("id"), rs.getLong("observation_id"),
                        rs.getString("field_name"), rs.getString("field_value"), rs.getString("source")),
                observationId);
    }

    public List<ObservationField> findAllObservationFields() {
        return jdbc.query("SELECT * FROM app_observation_field ORDER BY observation_id, field_name",
                (rs, n) -> new ObservationField(rs.getLong("id"), rs.getLong("observation_id"),
                        rs.getString("field_name"), rs.getString("field_value"), rs.getString("source")));
    }

    // ------------------------------------------------------------ 迁移运行
    public long insertRun(String runCode, Long targetProtocolId, Long baseProtocolId, Long sourceProtocolId,
                          Long asOfVersionId, String mode, Long createdBy, Long previousRunId,
                          String dependencySnapshot) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO app_protocol_run(run_code, target_protocol_id, base_protocol_id, "
                            + "source_protocol_id, as_of_version_id, mode, status, created_by, previous_run_id, "
                            + "dependency_snapshot) VALUES (?,?,?,?,?,?,'RUNNING',?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, runCode);
            ps.setLong(2, targetProtocolId);
            if (baseProtocolId == null) {
                ps.setNull(3, java.sql.Types.BIGINT);
            } else {
                ps.setLong(3, baseProtocolId);
            }
            if (sourceProtocolId == null) {
                ps.setNull(4, java.sql.Types.BIGINT);
            } else {
                ps.setLong(4, sourceProtocolId);
            }
            if (asOfVersionId == null) {
                ps.setNull(5, java.sql.Types.BIGINT);
            } else {
                ps.setLong(5, asOfVersionId);
            }
            ps.setString(6, mode);
            if (createdBy == null) {
                ps.setNull(7, java.sql.Types.BIGINT);
            } else {
                ps.setLong(7, createdBy);
            }
            if (previousRunId == null) {
                ps.setNull(8, java.sql.Types.BIGINT);
            } else {
                ps.setLong(8, previousRunId);
            }
            ps.setString(9, dependencySnapshot);
            return ps;
        }, kh);
        return ((Number) kh.getKeys().get("id")).longValue();
    }

    public void completeRun(Long runId, int observationCount, int shadowCount, int gapCount,
                            int ambiguousCount, int reusedCount, String affectedCategories,
                            String summary, String dependencyWarnings) {
        jdbc.update("UPDATE app_protocol_run SET status = 'COMPLETED', finished_at = now(), "
                        + "observation_count = ?, shadow_count = ?, gap_count = ?, ambiguous_count = ?, "
                        + "reused_count = ?, affected_categories = ?, summary = ?, dependency_warnings = ? "
                        + "WHERE id = ? AND status = 'RUNNING'",
                observationCount, shadowCount, gapCount, ambiguousCount, reusedCount, affectedCategories,
                summary, dependencyWarnings, runId);
    }

    public void failRun(Long runId, String reason) {
        jdbc.update("UPDATE app_protocol_run SET status = 'FAILED', finished_at = now(), summary = ? "
                + "WHERE id = ? AND status = 'RUNNING'", reason, runId);
    }

    public List<ProtocolRun> findAllRuns() {
        return jdbc.query("SELECT * FROM app_protocol_run ORDER BY id DESC", RUN_MAPPER);
    }

    public Optional<ProtocolRun> findRunById(Long id) {
        return jdbc.query("SELECT * FROM app_protocol_run WHERE id = ?", RUN_MAPPER, id).stream().findFirst();
    }

    public boolean existsRunCode(String runCode) {
        return jdbc.queryForObject("SELECT count(*) FROM app_protocol_run WHERE run_code = ?",
                Long.class, runCode) > 0;
    }

    public long countRunsByTarget(Long targetProtocolId) {
        return jdbc.queryForObject("SELECT count(*) FROM app_protocol_run WHERE target_protocol_id = ?",
                Long.class, targetProtocolId);
    }

    public Optional<ProtocolRun> findLatestCompletedRun(Long targetProtocolId) {
        return jdbc.query("SELECT * FROM app_protocol_run WHERE target_protocol_id = ? AND status = 'COMPLETED' "
                        + "ORDER BY id DESC LIMIT 1", RUN_MAPPER, targetProtocolId).stream().findFirst();
    }

    // ------------------------------------------------------------ 影子分类
    public void insertShadow(ShadowClassification s) {
        jdbc.update("INSERT INTO app_shadow_classification(run_id, observation_id, segment_id, batch_id, "
                        + "defect_type, source_category_code, target_category_code, classification, old_grade, "
                        + "new_grade, evidence_strength, missing_fields, candidate_categories, basis, "
                        + "applicability, applicability_note, projected_segment_id, projected_start_m, "
                        + "projected_end_m, projection_note, inspected_at, evidence_uri, video_uri, stale_risk) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                s.runId(), s.observationId(), s.segmentId(), s.batchId(), s.defectType(),
                s.sourceCategoryCode(), s.targetCategoryCode(), s.classification(), s.oldGrade(),
                s.newGrade(), s.evidenceStrength(), s.missingFields(), s.candidateCategories(),
                s.basis(), s.applicability(),
                s.applicabilityNote(), s.projectedSegmentId(), s.projectedStartM(), s.projectedEndM(),
                s.projectionNote(),
                s.inspectedAt() == null ? null : Timestamp.valueOf(s.inspectedAt()),
                s.evidenceUri(), s.videoUri(), s.staleRisk());
    }

    public List<ShadowClassification> findShadows(Long runId) {
        return jdbc.query("SELECT * FROM app_shadow_classification WHERE run_id = ? ORDER BY observation_id",
                SHADOW_MAPPER, runId);
    }

    public List<ShadowClassification> findShadowsFiltered(Long runId, String defectType, Long batchId,
                                                         String classification) {
        StringBuilder sql = new StringBuilder("SELECT * FROM app_shadow_classification WHERE run_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(runId);
        if (defectType != null && !defectType.isBlank()) {
            sql.append(" AND defect_type = ?");
            args.add(defectType);
        }
        if (batchId != null) {
            sql.append(" AND batch_id = ?");
            args.add(batchId);
        }
        if (classification != null && !classification.isBlank()) {
            sql.append(" AND classification = ?");
            args.add(classification);
        }
        sql.append(" ORDER BY defect_type, batch_id, observation_id");
        return jdbc.query(sql.toString(), SHADOW_MAPPER, args.toArray());
    }

    public Optional<ShadowClassification> findShadowById(Long id) {
        return jdbc.query("SELECT * FROM app_shadow_classification WHERE id = ?", SHADOW_MAPPER, id)
                .stream().findFirst();
    }

    // ------------------------------------------------------------ 顺位影响
    public void insertSegmentRank(ShadowSegmentRank r) {
        jdbc.update("INSERT INTO app_shadow_segment_rank(run_id, segment_id, old_published_rank, "
                        + "old_published_max_grade, old_published_burden, shadow_rank, shadow_max_grade, "
                        + "shadow_burden, rank_delta, gap_count, ambiguous_count, affected, note) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                r.runId(), r.segmentId(), r.oldPublishedRank(), r.oldPublishedMaxGrade(),
                r.oldPublishedBurden(), r.shadowRank(), r.shadowMaxGrade(), r.shadowBurden(),
                r.rankDelta(), r.gapCount(), r.ambiguousCount(), r.affected(), r.note());
    }

    public List<ShadowSegmentRank> findSegmentRanks(Long runId) {
        return jdbc.query("SELECT * FROM app_shadow_segment_rank WHERE run_id = ? ORDER BY "
                + "COALESCE(shadow_rank, 999999), segment_id", RANK_MAPPER, runId);
    }

    // ------------------------------------------------------------ 人工复核
    public long insertReview(Long runId, Long shadowId, Long reviewerId, String decision,
                             String chosenCategory, String note) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO app_shadow_review(run_id, shadow_id, reviewer_id, decision, "
                            + "chosen_category, note) VALUES (?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, runId);
            ps.setLong(2, shadowId);
            if (reviewerId == null) {
                ps.setNull(3, java.sql.Types.BIGINT);
            } else {
                ps.setLong(3, reviewerId);
            }
            ps.setString(4, decision);
            ps.setString(5, chosenCategory);
            ps.setString(6, note);
            return ps;
        }, kh);
        return ((Number) kh.getKeys().get("id")).longValue();
    }

    public List<ShadowReview> findReviews(Long runId) {
        return jdbc.query("SELECT * FROM app_shadow_review WHERE run_id = ? ORDER BY id", REVIEW_MAPPER, runId);
    }

    public List<ShadowReview> findReviewsForShadow(Long shadowId) {
        return jdbc.query("SELECT * FROM app_shadow_review WHERE shadow_id = ? ORDER BY id",
                REVIEW_MAPPER, shadowId);
    }

    /** 目标版本上最近一次已发布数据集的 id（影子顺位以此为对照，只读不改写）。 */
    public Optional<Long> findLatestPublishedDataset(Long asOfVersionId, String scoringMode) {
        return jdbc.query("SELECT id FROM app_dataset WHERE as_of_version_id = ? AND scoring_mode = ? "
                        + "ORDER BY id DESC LIMIT 1",
                (rs, n) -> rs.getLong(1), asOfVersionId, scoringMode).stream().findFirst();
    }

    public static LocalDateTime now() {
        return LocalDateTime.now();
    }
}
