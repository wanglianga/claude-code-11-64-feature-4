package com.ccdev.drainage.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 领域模型集合：全部以不可变 record 承载，数据库写入通过 JdbcTemplate 显式 SQL 完成。
 */
public final class Models {

    private Models() {
    }

    public record AssetVersion(Long id, String versionNo, String name, LocalDateTime effectiveAt,
                               String status, String note) {
    }

    public record Manhole(Long id, Long versionId, String manholeCode, BigDecimal geomX, BigDecimal geomY) {
    }

    public record PipeSegment(Long id, Long versionId, String segmentCode, Long upstreamMhId,
                              Long downstreamMhId, BigDecimal lengthM, String flowDirection,
                              String material, Integer diameterMm, Long parentSegmentId,
                              String lifecycleStatus) {
    }

    public record SegmentMapping(Long id, Long oldSegmentId, Long newSegmentId,
                                 BigDecimal oldStartM, BigDecimal oldEndM,
                                 BigDecimal newStartM, BigDecimal newEndM,
                                 String basis, String note) {
    }

    public record ContractorBatch(Long id, String batchCode, String contractor, LocalDateTime inspectedAt,
                                  String gradingStandard, String directionConvention, String contentHash) {
    }

    public record Video(Long id, Long batchId, String videoCode, String fileUri, String segmentHint,
                        BigDecimal startM, BigDecimal endM, String stationDirection,
                        String contentHash, Long duplicateOfId) {
    }

    public record Observation(Long id, Long videoId, Long batchId, String defectType, String rawGrade,
                              BigDecimal stationStartM, BigDecimal stationEndM, String evidenceUri,
                              String evidenceStrength, String status, Long duplicateOfId, Long clusterId) {
    }

    public record JudgementRule(String defectType, Integer minGrade, BigDecimal minLengthM,
                                String requiredEvidence, Boolean enabled) {
    }

    public record AlignmentCandidate(Long id, Long observationId, Long segmentId, Long assetVersionId,
                                     BigDecimal normStartM, BigDecimal normEndM, BigDecimal score,
                                     Boolean selected, String reason) {
    }

    public record Attribution(Long observationId, Long segmentId, Long assetVersionId,
                              BigDecimal normStartM, BigDecimal normEndM, LocalDateTime fixedAt, String note) {
    }

    public record DefectCluster(Long id, String clusterCode, Long segmentId, Long assetVersionId,
                                String defectType, BigDecimal normStartM, BigDecimal normEndM,
                                String status, Boolean conflictFlag, Integer resolvedGrade,
                                String resolutionNote, Long resolvedBy, LocalDateTime resolvedAt) {
    }

    public record ClusterMember(Long id, Long clusterId, Long observationId, String memberRole,
                                Integer gradeAtInspection, String evidenceStrength, Long batchId) {
    }

    public record Dataset(Long id, String datasetCode, Long asOfVersionId, String scoringMode,
                          String status, LocalDateTime generatedAt, Long generatedBy, String formulaNote) {
    }

    public record SegmentScore(Long id, Long datasetId, Long segmentId, Integer maxGrade,
                               BigDecimal burdenScore, Integer rankNo, Boolean rankingPaused,
                               String pauseReasons, Integer eligibleClusterCount, Integer conflictCount,
                               BigDecimal evidenceCompleteness, String explanation) {
    }

    public record DatasetImpact(Long id, Long datasetId, Long oldSegmentId, Long newSegmentId,
                                String impactType, Integer inheritedCount, Integer quarantinedCount,
                                String note) {
    }

    public record AppUser(Long id, String username, String passwordHash, String displayName, String role) {
    }

    public record AuditLog(Long id, Long userId, String action, String targetRef, String detail,
                           LocalDateTime createdAt) {
    }

    // ------------------------------------------------------------ 判读规程迁移
    public record Protocol(Long id, String protocolCode, String name, LocalDate effectiveFrom,
                           LocalDate effectiveTo, String approvalSource, String status,
                           Long parentProtocolId, String note) {
    }

    public record ProtocolCategory(Long id, Long protocolId, String categoryCode, String categoryName,
                                   String defectType, Integer minGrade, Integer maxGrade,
                                   String requiredFields, Integer severityGrade, Boolean enabled, String note) {
    }

    public record ProtocolSplit(Long id, Long fromProtocolId, String fromCategoryCode,
                                Long toProtocolId, String toCategoryCode, String conditionExpr,
                                Integer priority, String note) {
    }

    public record ProtocolDependency(Long id, Long protocolId, String categoryCode, String fieldName,
                                     String sourceField, String sourceDefectType, String dependencyKind,
                                     String conditionExpr, String note) {
    }

    public record ObservationField(Long id, Long observationId, String fieldName, String fieldValue,
                                   String source) {
    }

    public record ProtocolField(Long id, String fieldCode, String fieldName, String valueType,
                                String unit, String recoverability, String description) {
    }

    public record ProtocolRun(Long id, String runCode, Long targetProtocolId, Long baseProtocolId,
                              Long sourceProtocolId, Long asOfVersionId, String mode, String status,
                              LocalDateTime startedAt, LocalDateTime finishedAt,
                              Long createdBy, Integer observationCount, Integer shadowCount, Integer gapCount,
                              Integer ambiguousCount, Integer reusedCount, String affectedCategories,
                              Long previousRunId, String summary, String dependencySnapshot,
                              String dependencyWarnings) {
    }

    public record ShadowClassification(Long id, Long runId, Long observationId, Long segmentId,
                                       Long batchId, String defectType, String sourceCategoryCode,
                                       String targetCategoryCode, String classification, Integer oldGrade,
                                       Integer newGrade, String evidenceStrength, String missingFields,
                                       String candidateCategories,
                                       String basis, String applicability, String applicabilityNote,
                                       Long projectedSegmentId, BigDecimal projectedStartM,
                                       BigDecimal projectedEndM, String projectionNote,
                                       LocalDateTime inspectedAt, String evidenceUri, String videoUri,
                                       Boolean staleRisk) {
    }

    public record ShadowSegmentRank(Long id, Long runId, Long segmentId, Integer oldPublishedRank,
                                    Integer oldPublishedMaxGrade, BigDecimal oldPublishedBurden,
                                    Integer shadowRank, Integer shadowMaxGrade, BigDecimal shadowBurden,
                                    Integer rankDelta, Integer gapCount, Integer ambiguousCount,
                                    Boolean affected, String note) {
    }

    public record ShadowReview(Long id, Long runId, Long shadowId, Long reviewerId, String decision,
                               String chosenCategory, String note, LocalDateTime createdAt) {
    }
}
