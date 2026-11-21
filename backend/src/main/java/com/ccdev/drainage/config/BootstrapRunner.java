package com.ccdev.drainage.config;

import com.ccdev.drainage.dto.Requests;
import com.ccdev.drainage.repo.AnalysisRepository;
import com.ccdev.drainage.repo.AssetRepository;
import com.ccdev.drainage.repo.DatasetRepository;
import com.ccdev.drainage.repo.ProtocolRepository;
import com.ccdev.drainage.service.AlignmentService;
import com.ccdev.drainage.service.ClusteringService;
import com.ccdev.drainage.service.DatasetService;
import com.ccdev.drainage.service.ProtocolMigrationService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.util.Map;

/**
 * 启动引导：
 *   1. 执行 schema.sql（IF NOT EXISTS，可重复执行）
 *   2. 执行 seed-data.sql（ON CONFLICT DO NOTHING，可重复执行）
 *   3. 若对齐/聚类结果缺失，自动跑一遍 对齐 → 聚类 → 双口径数据集
 */
@Component
public class BootstrapRunner implements ApplicationRunner {

    private final JdbcTemplate jdbc;
    private final AlignmentService alignmentService;
    private final ClusteringService clusteringService;
    private final DatasetService datasetService;
    private final AnalysisRepository analysisRepo;
    private final DatasetRepository datasetRepo;
    private final AssetRepository assetRepo;
    private final ProtocolRepository protocolRepo;
    private final ProtocolMigrationService migrationService;

    public BootstrapRunner(JdbcTemplate jdbc, AlignmentService alignmentService,
                           ClusteringService clusteringService, DatasetService datasetService,
                           AnalysisRepository analysisRepo, DatasetRepository datasetRepo,
                           AssetRepository assetRepo, ProtocolRepository protocolRepo,
                           ProtocolMigrationService migrationService) {
        this.jdbc = jdbc;
        this.alignmentService = alignmentService;
        this.clusteringService = clusteringService;
        this.datasetService = datasetService;
        this.analysisRepo = analysisRepo;
        this.datasetRepo = datasetRepo;
        this.assetRepo = assetRepo;
        this.protocolRepo = protocolRepo;
        this.migrationService = migrationService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        try (Connection conn = jdbc.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(conn, new ClassPathResource("db/schema.sql"));
            ScriptUtils.executeSqlScript(conn, new ClassPathResource("db/seed-data.sql"));
        } catch (Exception e) {
            throw new IllegalStateException("启动建表/种子失败: " + e.getMessage(), e);
        }

        if (analysisRepo.countAttributions() == 0) {
            Map<String, Object> align = alignmentService.realignAll();
            System.out.println("[bootstrap] 位置对齐完成: " + align);
            Map<String, Object> cluster = clusteringService.rebuildClusters();
            System.out.println("[bootstrap] 缺陷聚类完成: " + cluster);
        }
        if (datasetRepo.countDatasets() == 0) {
            long latestVersion = assetRepo.findAllVersions().stream()
                    .map(v -> v.id()).max(Long::compare).orElseThrow();
            System.out.println("[bootstrap] 生成 MAX_SEVERITY 数据集: "
                    + datasetService.generateForVersion(latestVersion, "MAX_SEVERITY", 1L));
            System.out.println("[bootstrap] 生成 BURDEN 数据集: "
                    + datasetService.generateForVersion(latestVersion, "BURDEN", 1L));
        }
        // 判读规程迁移：首次启动按新规程对全部历史观察做一次全量重放（只增不改的迁移运行）
        if (protocolRepo.findProtocolByCode("CCT-2026").isPresent()
                && protocolRepo.findAllRuns().isEmpty()) {
            var cct2026 = protocolRepo.findProtocolByCode("CCT-2026").orElseThrow();
            var run = migrationService.run(new Requests.ProtocolRunRequest(cct2026.id(), null,
                    "FULL_REPLAY", null, null, null, "启动引导全量重放"), 1L);
            System.out.println("[bootstrap] 判读规程迁移全量重放: " + run.get("runCode")
                    + " / " + run.get("summary"));
        }
    }
}
