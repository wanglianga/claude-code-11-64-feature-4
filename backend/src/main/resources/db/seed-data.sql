-- ============================================================================
-- 虚构样例数据（可重复执行：ON CONFLICT DO NOTHING）
-- 场景覆盖：
--   * 反向里程批次（B-2025-02，从下游井起算）
--   * 两批独立检查对同一位置给出不相容等级（WS-002 接口错位 III vs D=4）
--   * 重复上传（VID-B3-WS001-DUP 与 V1 同 content_hash）与重叠片段（V6）
--   * 管段拆分（WS-003 → WS-003A/WS-003B，拆分点 22m；OBS-11 跨拆分点）
--   * 低等级/证据不足观察不入围（OBS-14 等级 I）
-- 对齐候选、聚类、数据集由后端启动任务计算，不在此手工铺设。
-- ============================================================================

-- ---------------------------------------------------------------- 用户
INSERT INTO app_user (id, username, password_hash, display_name, role) VALUES
 (1, 'admin',    '240be518fabd2724ddb6f04eeb1da5967448d7e831c08c8fa822809f74c720a9', '工程处管理员', 'ADMIN'),
 (2, 'engineer', 'f63248efa4a61efc9f4c9f6e5de25b34b6f2b827717cd4c6b3905481c3bd483b', '复核工程师',   'ENGINEER')
ON CONFLICT (username) DO NOTHING;

-- ---------------------------------------------------------------- 资产版本
INSERT INTO app_asset_version (id, version_no, name, effective_at, status, note) VALUES
 (1, 'V2024-01', '2024 版竣工资产图', TIMESTAMP '2024-01-01 00:00:00', 'SUPERSEDED', '初始普查基线版本'),
 (2, 'V2026-06', '2026 年 6 月测绘修订版', TIMESTAMP '2026-06-01 00:00:00', 'ACTIVE', 'WS-003 复测后拆分为 WS-003A/WS-003B，新增井 MH03X')
ON CONFLICT (version_no) DO NOTHING;

-- ---------------------------------------------------------------- 井
INSERT INTO app_manhole (id, version_id, manhole_code, geom_x, geom_y) VALUES
 (1, 1, 'MH01', 100.000, 200.000),
 (2, 1, 'MH02', 160.000, 200.000),
 (3, 1, 'MH03', 210.000, 200.000),
 (4, 1, 'MH04', 250.000, 200.000),
 (5, 2, 'MH01', 100.000, 200.000),
 (6, 2, 'MH02', 160.000, 200.000),
 (7, 2, 'MH03', 210.000, 200.000),
 (8, 2, 'MH04', 250.000, 200.000),
 (9, 2, 'MH03X', 232.000, 200.000)
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------- 管段
-- 旧版本三段
INSERT INTO app_pipe_segment
 (id, version_id, segment_code, upstream_mh_id, downstream_mh_id, length_m, flow_direction, material, diameter_mm, parent_segment_id, lifecycle_status) VALUES
 (1, 1, 'WS-001', 1, 2, 60.000, 'UP_TO_DOWN', '钢筋混凝土', 800, NULL, 'ACTIVE'),
 (2, 1, 'WS-002', 2, 3, 50.000, 'UP_TO_DOWN', '钢筋混凝土', 800, NULL, 'ACTIVE'),
 (3, 1, 'WS-003', 3, 4, 40.000, 'UP_TO_DOWN', 'PVC', 600, NULL, 'SPLIT')
ON CONFLICT DO NOTHING;
-- 新版本：WS-001/WS-002 延续，WS-003 拆分
INSERT INTO app_pipe_segment
 (id, version_id, segment_code, upstream_mh_id, downstream_mh_id, length_m, flow_direction, material, diameter_mm, parent_segment_id, lifecycle_status) VALUES
 (4, 2, 'WS-001', 5, 6, 60.000, 'UP_TO_DOWN', '钢筋混凝土', 800, 1, 'ACTIVE'),
 (5, 2, 'WS-002', 6, 7, 50.000, 'UP_TO_DOWN', '钢筋混凝土', 800, 2, 'ACTIVE'),
 (6, 2, 'WS-003A', 7, 9, 22.000, 'UP_TO_DOWN', 'PVC', 600, 3, 'ACTIVE'),
 (7, 2, 'WS-003B', 9, 8, 18.000, 'UP_TO_DOWN', 'PVC', 600, 3, 'ACTIVE')
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------- 新旧管段里程映射（归一里程：上游井为 0）
INSERT INTO app_segment_mapping
 (id, old_segment_id, new_segment_id, old_start_m, old_end_m, new_start_m, new_end_m, basis, note) VALUES
 (1, 1, 4,  0.000, 60.000,  0.000, 60.000, 'STATION_EQUIVALENCE', 'WS-001 几何未变，里程等值映射'),
 (2, 2, 5,  0.000, 50.000,  0.000, 50.000, 'STATION_EQUIVALENCE', 'WS-002 几何未变，里程等值映射'),
 (3, 3, 6,  0.000, 22.000,  0.000, 22.000, 'SURVEY_MEASURE', 'WS-003 前段 → WS-003A，拆分点 22m'),
 (4, 3, 7, 22.000, 40.000,  0.000, 18.000, 'SURVEY_MEASURE', 'WS-003 后段 → WS-003B，新里程自 MH03X 起算')
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------- 等级标准
INSERT INTO app_grade_standard (standard_code, raw_grade, normalized_grade) VALUES
 ('STD-CCT-2021', 'I',   1),
 ('STD-CCT-2021', 'II',  2),
 ('STD-CCT-2021', 'III', 3),
 ('STD-CCT-2021', 'IV',  4),
 ('STD-DX-2023',  'A',   1),
 ('STD-DX-2023',  'B',   2),
 ('STD-DX-2023',  'C',   3),
 ('STD-DX-2023',  'D',   4)
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------- 判读规则（进入评估数据集的门槛）
INSERT INTO app_judgement_rule (defect_type, min_grade, min_length_m, required_evidence, enabled) VALUES
 ('CRACK',             2, 1.000, 'PARTIAL', true),
 ('DEFORMATION',       3, 0.000, 'SPARSE',  true),
 ('LEAK',              2, 0.000, 'SPARSE',  true),
 ('JOINT_DISLOCATION', 3, 0.000, 'PARTIAL', true)
ON CONFLICT (defect_type) DO NOTHING;

-- ---------------------------------------------------------------- 承包商批次
INSERT INTO app_contractor_batch (id, batch_code, contractor, inspected_at, grading_standard, direction_convention, content_hash) VALUES
 (1, 'B-2025-01', '甲勘测院',   TIMESTAMP '2025-03-10 09:00:00', 'STD-CCT-2021', '上游井起算，里程沿前进方向递增', 'hash:batch:b202501'),
 (2, 'B-2025-02', '乙检测公司', TIMESTAMP '2025-09-18 10:30:00', 'STD-DX-2023',  '下游井起算，反向里程（0 在下游井）', 'hash:batch:b202502'),
 (3, 'B-2026-03', '甲勘测院复测', TIMESTAMP '2026-03-05 14:00:00', 'STD-CCT-2021', '上游井起算，里程沿前进方向递增', 'hash:batch:b202603')
ON CONFLICT (batch_code) DO NOTHING;

-- ---------------------------------------------------------------- 视频索引
-- V5 与 V1 内容哈希相同：重复上传；V6 为部分重叠片段
INSERT INTO app_video (id, batch_id, video_code, file_uri, segment_hint, start_m, end_m, station_direction, content_hash, duplicate_of_id) VALUES
 (1, 1, 'VID-B1-WS001',     'archive://2025/B1/ws001.mp4',     'WS-001',  0.000, 60.000, 'FORWARD',  'sha256:vid-ws001-full',    NULL),
 (2, 2, 'VID-B2-WS001',     'archive://2025/B2/ws001-rev.mp4', 'WS-001',  0.000, 60.000, 'REVERSED', 'sha256:vid-ws001-rev',     NULL),
 (3, 1, 'VID-B1-WS002',     'archive://2025/B1/ws002.mp4',     'WS-002',  0.000, 50.000, 'FORWARD',  'sha256:vid-ws002',         NULL),
 (4, 2, 'VID-B2-WS002',     'archive://2025/B2/ws002.mp4',     'WS-002',  0.000, 50.000, 'FORWARD',  'sha256:vid-ws002-b2',      NULL),
 (5, 3, 'VID-B3-WS001-DUP', 'archive://2026/B3/ws001-copy.mp4','WS-001',  0.000, 60.000, 'FORWARD',  'sha256:vid-ws001-full',    1),
 (6, 3, 'VID-B3-WS001-OVL', 'archive://2026/B3/ws001-part.mp4','WS-001',  8.000, 20.000, 'FORWARD',  'sha256:vid-ws001-partial', NULL),
 (7, 1, 'VID-B1-WS003',     'archive://2025/B1/ws003.mp4',     'WS-003',  0.000, 40.000, 'FORWARD',  'sha256:vid-ws003',         NULL),
 (8, 2, 'VID-B2-WS003',     'archive://2025/B2/ws003-rev.mp4', 'WS-003',  0.000, 40.000, 'REVERSED', 'sha256:vid-ws003-rev',     NULL)
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------- 观察
-- 归一里程由后端按视频方向换算（REVERSED: norm = length - station），此处仅存原始里程
INSERT INTO app_observation
 (id, video_id, batch_id, defect_type, raw_grade, station_start_m, station_end_m, evidence_uri, evidence_strength, status, duplicate_of_id) VALUES
-- B1 对 WS-001 的检查
 (1,  1, 1, 'CRACK',             'II',  10.000, 12.500, 'archive://2025/B1/frames/obs-001.zip', 'FULL',    'PENDING', NULL),
 (2,  1, 1, 'LEAK',              'III', 30.000, 31.000, 'archive://2025/B1/frames/obs-002.zip', 'PARTIAL', 'PENDING', NULL),
 (14, 1, 1, 'CRACK',             'I',   50.000, 51.000, 'archive://2025/B1/frames/obs-014.zip', 'SPARSE',  'PENDING', NULL),
-- B2 反向里程对 WS-001：47.5–50 反向 ⇒ 归一 10–12.5（与 OBS-1 同位置同等级，重复）；20–21 反向 ⇒ 归一 39–40
 (3,  2, 2, 'CRACK',             'B',   47.500, 50.000, 'archive://2025/B2/frames/obs-003.zip', 'FULL',    'PENDING', NULL),
 (4,  2, 2, 'DEFORMATION',       'D',   20.000, 21.000, 'archive://2025/B2/frames/obs-004.zip', 'SPARSE',  'PENDING', NULL),
-- WS-002 同位置等级不相容：III(FULL) vs D=4(PARTIAL)，证据不足以裁决 ⇒ 冲突暂停
 (5,  3, 1, 'JOINT_DISLOCATION', 'III', 15.000, 15.500, 'archive://2025/B1/frames/obs-005.zip', 'FULL',    'PENDING', NULL),
 (6,  4, 2, 'JOINT_DISLOCATION', 'D',   15.000, 15.600, 'archive://2025/B2/frames/obs-006.zip', 'PARTIAL', 'PENDING', NULL),
-- B3 重复上传视频 V5 中的两条观察，直接标记为 OBS-1/2 的重复件
 (7,  5, 3, 'CRACK', 'II',  10.000, 12.500, 'archive://2026/B3/frames/obs-007.zip', 'FULL',    'DUPLICATE', 1),
 (8,  5, 3, 'LEAK',  'III', 30.000, 31.000, 'archive://2026/B3/frames/obs-008.zip', 'PARTIAL', 'DUPLICATE', 2),
-- B3 重叠片段 V6 中的裂缝，聚类时并入同一簇，不重复累加
 (9,  6, 3, 'CRACK', 'II',  10.200, 12.300, 'archive://2026/B3/frames/obs-009.zip', 'FULL',    'PENDING', NULL),
-- WS-003（后拆分）：8–10 落入 3A；21–23 跨拆分点 22 ⇒ 隔离不得复制；30–31 落入 3B
 (10, 7, 1, 'CRACK',       'III',  8.000, 10.000, 'archive://2025/B1/frames/obs-010.zip', 'FULL',    'PENDING', NULL),
 (11, 7, 1, 'DEFORMATION', 'IV',  21.000, 23.000, 'archive://2025/B1/frames/obs-011.zip', 'FULL',    'PENDING', NULL),
 (12, 7, 1, 'LEAK',        'II',  30.000, 31.000, 'archive://2025/B1/frames/obs-012.zip', 'PARTIAL', 'PENDING', NULL),
-- B2 反向里程复检 WS-003：反向 30–32 ⇒ 归一 8–10，与 OBS-10 同位置同等级
 (13, 8, 2, 'CRACK',       'C',   30.000, 32.000, 'archive://2025/B2/frames/obs-013.zip', 'FULL',    'PENDING', NULL)
ON CONFLICT DO NOTHING;

-- 校正显式插入后的 identity 序列，避免后续自增冲突
SELECT setval(pg_get_serial_sequence('app_user','id'),               (SELECT max(id) FROM app_user));
SELECT setval(pg_get_serial_sequence('app_asset_version','id'),      (SELECT max(id) FROM app_asset_version));
SELECT setval(pg_get_serial_sequence('app_manhole','id'),            (SELECT max(id) FROM app_manhole));
SELECT setval(pg_get_serial_sequence('app_pipe_segment','id'),       (SELECT max(id) FROM app_pipe_segment));
SELECT setval(pg_get_serial_sequence('app_segment_mapping','id'),    (SELECT max(id) FROM app_segment_mapping));
SELECT setval(pg_get_serial_sequence('app_contractor_batch','id'),   (SELECT max(id) FROM app_contractor_batch));
SELECT setval(pg_get_serial_sequence('app_video','id'),              (SELECT max(id) FROM app_video));
SELECT setval(pg_get_serial_sequence('app_observation','id'),        (SELECT max(id) FROM app_observation));

-- ============================================================================
-- 判读规程迁移样例（虚构）
--   旧规程 CCT-2021：裂缝/变形/渗漏/接口错位 各按 1-4 级整类判定
--   新规程 CCT-2026：裂缝按宽度与渗漏拆为多个类别；变形按挠度拆；接口错位按错位量拆
--   规程 CCT-2026-R1：再次修订（仅调整渗漏门槛），用于验证“再次修订不得修改既有运行”
-- 场景覆盖：
--   * 类别拆分：旧裂缝 III → 新 CRACK_WIDE_SEEPS / CRACK_WIDE_DRY
--   * 字段不足：裂缝宽度在旧视频中不可测量 ⇒ EVIDENCE_GAP，禁止由旧等级反推
--   * 候选歧义：缺少渗漏迹象 ⇒ 保留两个候选，不任选其一
--   * 有效期边界：CCT-2026 自 2025-03-01 起适用；R1 自 2026-01-01 起适用，
--     因此 R1 运行中 2025 年两批检查标为“检查早于生效日，不适用”，只有 2026-03 批次适用
--     （按检查发生日判断，不按导入日期套用）
--   * 跨类别依赖：接口错位 III 的错位量取自同管段重叠渗漏观察的渗漏迹象
-- ============================================================================

-- ---------------------------------------------------------------- 规程
INSERT INTO app_protocol
 (id, protocol_code, name, effective_from, effective_to, approval_source, status, parent_protocol_id, note) VALUES
 (1, 'CCT-2021', '旧版缺陷判读规程（2021）', DATE '2021-01-01', DATE '2025-02-28',
     '市住建局《城镇排水管道检测与评估技术规程》CJJ 181-2012 附录', 'SUPERSEDED', NULL,
     '整类分级：裂缝/变形/渗漏/接口错位按 1-4 级'),
 (2, 'CCT-2026', '新版缺陷判读规程（2026）', DATE '2025-03-01', NULL,
     '市水务局 2025 年第 3 号技术通告，经市政工程处 2025-01 评审批准', 'ACTIVE', 1,
     '裂缝按宽度与渗漏拆分、变形按挠度拆分、接口错位按错位量拆分'),
 (3, 'CCT-2026-R1', '新版判读规程第一次修订（2026-R1）', DATE '2026-01-01', NULL,
     '市水务局 2025 年第 9 号技术通告（补登接口错位跨类别依赖索引）', 'ACTIVE', 2,
     '修订文本自同日替代 CCT-2026：仅补登接口错位跨类别依赖索引，用于验证既有运行不被改写')
ON CONFLICT (protocol_code) DO NOTHING;

-- 旧规程类别定义快照（规则差异与迁移映射的来源）
INSERT INTO app_protocol_legacy_category
 (protocol_id, category_code, category_name, defect_type, min_grade, max_grade, note) VALUES
 (1, 'LEG-CRACK',  '裂缝（旧）',      'CRACK',              1, 4, '按整类 1-4 级'),
 (1, 'LEG-DEFORM', '变形（旧）',      'DEFORMATION',        1, 4, '按整类 1-4 级'),
 (1, 'LEG-LEAK',   '渗漏（旧）',      'LEAK',               1, 4, '按整类 1-4 级'),
 (1, 'LEG-JOINT',  '接口错位（旧）',  'JOINT_DISLOCATION',  1, 4, '按整类 1-4 级')
ON CONFLICT (protocol_id, category_code) DO NOTHING;

-- 新规程类别定义：必要观察字段直接写在 required_fields
INSERT INTO app_protocol_category
 (id, protocol_id, category_code, category_name, defect_type, min_grade, max_grade, required_fields,
  severity_grade, enabled, note) VALUES
 (1, 2, 'CRACK_NARROW',        '窄裂缝（宽度<2mm）',          'CRACK',             1, 4, 'crack_width_mm',                    2, true, '按宽度细分，不涉及渗漏'),
 (2, 2, 'CRACK_WIDE_SEEPS',    '宽裂缝伴渗漏（宽度≥2mm）',    'CRACK',             2, 4, 'crack_width_mm,seepage',            4, true, '宽度≥2mm 且存在渗漏迹象'),
 (3, 2, 'CRACK_WIDE_DRY',      '宽裂缝无渗漏（宽度≥2mm）',    'CRACK',             2, 4, 'crack_width_mm,seepage',            3, true, '宽度≥2mm 但无渗漏迹象'),
 (4, 2, 'DEFORM_MINOR',        '轻微变形（挠度<5%）',         'DEFORMATION',       1, 4, 'deflection_pct',                    2, true, '按挠度细分'),
 (5, 2, 'DEFORM_MAJOR',        '显著变形（挠度≥5%）',         'DEFORMATION',       2, 4, 'deflection_pct',                    4, true, '按挠度细分'),
 (6, 2, 'LEAK_SEEP',           '渗漏（渗出）',                'LEAK',              1, 4, 'leak_rate_lpm',                     3, true, '按渗漏量细分'),
 (7, 2, 'JOINT_HORIZONTAL',    '接口横向错位（<20mm）',       'JOINT_DISLOCATION', 1, 4, 'offset_mm',                         2, true, '按错位量细分'),
 (8, 2, 'JOINT_VERTICAL',      '接口纵向错位（≥20mm）',       'JOINT_DISLOCATION', 1, 4, 'offset_mm',                         4, true, '按错位量细分')
ON CONFLICT (protocol_id, category_code) DO NOTHING;

-- 修订版 R1：继承 CCT-2026 的类别定义，仅调整宽裂缝伴渗漏的等级门槛
INSERT INTO app_protocol_category
 (id, protocol_id, category_code, category_name, defect_type, min_grade, max_grade, required_fields,
  severity_grade, enabled, note) VALUES
 (9,  3, 'CRACK_NARROW',     '窄裂缝（宽度<2mm）',       'CRACK',             1, 4, 'crack_width_mm',         2, true, '沿用 CCT-2026'),
 (10, 3, 'CRACK_WIDE_SEEPS', '宽裂缝伴渗漏（宽度≥2mm）', 'CRACK',             2, 4, 'crack_width_mm,seepage', 4, true, 'R1 沿用，仅补登依赖索引'),
 (11, 3, 'CRACK_WIDE_DRY',   '宽裂缝无渗漏（宽度≥2mm）', 'CRACK',             2, 4, 'crack_width_mm,seepage', 3, true, '沿用 CCT-2026'),
 (12, 3, 'DEFORM_MINOR',     '轻微变形（挠度<5%）',      'DEFORMATION',       1, 4, 'deflection_pct',         2, true, '沿用 CCT-2026'),
 (13, 3, 'DEFORM_MAJOR',     '显著变形（挠度≥5%）',      'DEFORMATION',       2, 4, 'deflection_pct',         4, true, '沿用 CCT-2026'),
 (14, 3, 'LEAK_SEEP',        '渗漏（渗出）',             'LEAK',              1, 4, 'leak_rate_lpm',          3, true, '沿用 CCT-2026'),
 (15, 3, 'JOINT_HORIZONTAL', '接口横向错位（<20mm）',    'JOINT_DISLOCATION', 1, 4, 'offset_mm',              2, true, '沿用 CCT-2026'),
 (16, 3, 'JOINT_VERTICAL',   '接口纵向错位（≥20mm）',    'JOINT_DISLOCATION', 1, 4, 'offset_mm',              4, true, '沿用 CCT-2026')
ON CONFLICT (protocol_id, category_code) DO NOTHING;

-- 必要观察字段字典：recoverability 决定缺失时是“证据不足”还是“候选歧义”
INSERT INTO app_protocol_field (field_code, field_name, value_type, unit, recoverability, description) VALUES
 ('crack_width_mm', '裂缝宽度', 'NUMERIC', 'mm', 'NOT_MEASURABLE_FROM_ARCHIVE',
     '旧视频分辨率不足以回溯测量宽度，缺失时必须判证据不足，禁止由旧等级反推'),
 ('deflection_pct', '变形挠度', 'NUMERIC', '%', 'NOT_MEASURABLE_FROM_ARCHIVE',
     '旧视频无法回溯测量挠度百分比，缺失时必须判证据不足'),
 ('seepage',        '渗漏迹象', 'CODE',    NULL, 'NOT_RECORDED',
     '旧归档未逐条登记渗漏迹象，缺失时保留“伴渗漏/无渗漏”多个候选'),
 ('leak_rate_lpm',  '渗漏量',   'NUMERIC', 'L/min', 'NOT_MEASURABLE_FROM_ARCHIVE',
     '渗漏量需现场量测，旧归档缺失时判证据不足'),
 ('offset_mm',      '接口错位量', 'NUMERIC', 'mm', 'NOT_RECORDED',
     '错位量部分归档有记录；缺失时保留横向/纵向多个候选')
ON CONFLICT (field_code) DO NOTHING;

-- 拆分关系：旧类别 → 新类别（条件表达式由 ProtocolRuleEngine 求值）
INSERT INTO app_protocol_split
 (id, from_protocol_id, from_category_code, to_protocol_id, to_category_code, condition_expr, priority, note) VALUES
 (1, 1, 'LEG-CRACK',  2, 'CRACK_NARROW',      'crack_width_mm < 2',                                10, '旧裂缝按宽度拆分为窄裂缝'),
 (2, 1, 'LEG-CRACK',  2, 'CRACK_WIDE_SEEPS',  'crack_width_mm >= 2 AND seepage = TRUE',            20, '宽裂缝且存在渗漏迹象'),
 (3, 1, 'LEG-CRACK',  2, 'CRACK_WIDE_DRY',    'crack_width_mm >= 2 AND seepage = FALSE',           30, '宽裂缝但无渗漏迹象'),
 (4, 1, 'LEG-DEFORM', 2, 'DEFORM_MINOR',      'deflection_pct < 5',                                10, '旧变形按挠度拆分'),
 (5, 1, 'LEG-DEFORM', 2, 'DEFORM_MAJOR',      'deflection_pct >= 5',                               20, '显著变形'),
 (6, 1, 'LEG-LEAK',   2, 'LEAK_SEEP',         'leak_rate_lpm >= 0',                                10, '旧渗漏整类归并为渗出'),
 (7, 1, 'LEG-JOINT',  2, 'JOINT_HORIZONTAL',  'offset_mm < 20',                                    10, '接口错位按错位量拆分'),
 (8, 1, 'LEG-JOINT',  2, 'JOINT_VERTICAL',    'offset_mm >= 20',                                   20, '纵向错位'),
-- 修订版 R1 复用同一份旧类别拆分定义
 (9,  1, 'LEG-CRACK',  3, 'CRACK_NARROW',     'crack_width_mm < 2',                                10, 'R1 沿用'),
 (10, 1, 'LEG-CRACK',  3, 'CRACK_WIDE_SEEPS', 'crack_width_mm >= 2 AND seepage = TRUE',            20, 'R1 沿用'),
 (11, 1, 'LEG-CRACK',  3, 'CRACK_WIDE_DRY',   'crack_width_mm >= 2 AND seepage = FALSE',           30, 'R1 沿用'),
 (12, 1, 'LEG-DEFORM', 3, 'DEFORM_MINOR',     'deflection_pct < 5',                                10, 'R1 沿用'),
 (13, 1, 'LEG-DEFORM', 3, 'DEFORM_MAJOR',     'deflection_pct >= 5',                               20, 'R1 沿用'),
 (14, 1, 'LEG-LEAK',   3, 'LEAK_SEEP',        'leak_rate_lpm >= 0',                                10, 'R1 沿用'),
 (15, 1, 'LEG-JOINT',  3, 'JOINT_HORIZONTAL', 'offset_mm < 20',                                    10, 'R1 沿用'),
 (16, 1, 'LEG-JOINT',  3, 'JOINT_VERTICAL',   'offset_mm >= 20',                                   20, 'R1 沿用')
ON CONFLICT (from_protocol_id, from_category_code, to_protocol_id, to_category_code) DO NOTHING;

-- 依赖索引：哪些原始字段参与了哪些新类别判定
--   CCT-2026 故意遗漏 JOINT_VERTICAL.offset_mm，用于展示“依赖索引缺口”告警与增量旧口径风险
INSERT INTO app_protocol_dependency
 (protocol_id, category_code, field_name, source_field, source_defect_type, dependency_kind, condition_expr, note) VALUES
 (2, 'CRACK_NARROW',     'crack_width_mm', 'crack_width_mm', 'CRACK',             'SELF',           'crack_width_mm < 2',            '自身归档字段'),
 (2, 'CRACK_WIDE_SEEPS', 'crack_width_mm', 'crack_width_mm', 'CRACK',             'SELF',           'crack_width_mm >= 2',           '自身归档字段'),
 (2, 'CRACK_WIDE_SEEPS', 'seepage',        'seepage',        'CRACK',             'SELF',           'seepage = TRUE',                '自身归档字段'),
 (2, 'CRACK_WIDE_DRY',   'crack_width_mm', 'crack_width_mm', 'CRACK',             'SELF',           'crack_width_mm >= 2',           '自身归档字段'),
 (2, 'CRACK_WIDE_DRY',   'seepage',        'seepage',        'CRACK',             'SELF',           'seepage = FALSE',               '自身归档字段'),
 (2, 'DEFORM_MINOR',     'deflection_pct', 'deflection_pct', 'DEFORMATION',       'SELF',           'deflection_pct < 5',            '自身归档字段'),
 (2, 'DEFORM_MAJOR',     'deflection_pct', 'deflection_pct', 'DEFORMATION',       'SELF',           'deflection_pct >= 5',           '自身归档字段'),
 (2, 'LEAK_SEEP',        'leak_rate_lpm',  'leak_rate_lpm',  'LEAK',              'SELF',           'leak_rate_lpm >= 0',            '自身归档字段'),
 (2, 'JOINT_HORIZONTAL', 'offset_mm',      'offset_mm',      'JOINT_DISLOCATION', 'CROSS_CATEGORY', 'offset_mm < 20',
     '错位量取同管段重叠接口错位观察的现场量测'),
 (3, 'CRACK_NARROW',     'crack_width_mm', 'crack_width_mm', 'CRACK',             'SELF',           'crack_width_mm < 2',            'R1 沿用'),
 (3, 'CRACK_WIDE_SEEPS', 'crack_width_mm', 'crack_width_mm', 'CRACK',             'SELF',           'crack_width_mm >= 2',           'R1 沿用'),
 (3, 'CRACK_WIDE_SEEPS', 'seepage',        'seepage',        'CRACK',             'SELF',           'seepage = TRUE',                'R1 沿用'),
 (3, 'CRACK_WIDE_DRY',   'crack_width_mm', 'crack_width_mm', 'CRACK',             'SELF',           'crack_width_mm >= 2',           'R1 沿用'),
 (3, 'CRACK_WIDE_DRY',   'seepage',        'seepage',        'CRACK',             'SELF',           'seepage = FALSE',               'R1 沿用'),
 (3, 'DEFORM_MINOR',     'deflection_pct', 'deflection_pct', 'DEFORMATION',       'SELF',           'deflection_pct < 5',            'R1 沿用'),
 (3, 'DEFORM_MAJOR',     'deflection_pct', 'deflection_pct', 'DEFORMATION',       'SELF',           'deflection_pct >= 5',           'R1 沿用'),
 (3, 'LEAK_SEEP',        'leak_rate_lpm',  'leak_rate_lpm',  'LEAK',              'SELF',           'leak_rate_lpm >= 0',            'R1 沿用'),
 (3, 'JOINT_HORIZONTAL', 'offset_mm',      'offset_mm',      'JOINT_DISLOCATION', 'CROSS_CATEGORY', 'offset_mm < 20',
     'R1 沿用'),
 (3, 'JOINT_VERTICAL',   'offset_mm',      'offset_mm',      'JOINT_DISLOCATION', 'CROSS_CATEGORY', 'offset_mm >= 20',
     'R1 补登：CCT-2026 遗漏的依赖，修订后补齐')
ON CONFLICT (protocol_id, category_code, field_name) DO NOTHING;

-- 已归档的原始观察可量化字段（宽度/挠度/渗漏等）
--   OBS-1（裂缝 II，WS-001[10,12.5]）：有宽度 3.2mm，但无渗漏登记 ⇒ 候选歧义
--   OBS-3（裂缝 B=2，WS-001 同位置反向复检）：有宽度 1.5mm ⇒ 窄裂缝，可判定
--   OBS-9（裂缝 II，重叠片段）：无宽度（旧画面不可测量）⇒ 证据不足
--   OBS-10（裂缝 III，WS-003）：无宽度 ⇒ 证据不足
--   OBS-13（裂缝 C=3，WS-003 反向复检）：有宽度 2.4mm、有渗漏 TRUE ⇒ 宽裂缝伴渗漏
--   OBS-4（变形 D=4，WS-001[39,40]）：无挠度 ⇒ 证据不足
--   OBS-11（变形 IV，WS-003[21,23]）：有挠度 6.5% ⇒ 显著变形
--   OBS-5（接口错位 III，WS-002[15,15.5]）：自身无错位量，旧规程依赖索引未登记 ⇒ 候选歧义；
--          R1 补齐跨类别依赖后，可从同管段重叠观察 OBS-6（22mm）判定为纵向错位
--   OBS-2（渗漏 III，WS-001[30,31]）：有渗漏量 1.8
INSERT INTO app_observation_field (observation_id, field_name, field_value, source) VALUES
 (1,  'crack_width_mm', '3.2',  'ARCHIVE_FRAME'),
 (3,  'crack_width_mm', '1.5',  'ARCHIVE_FRAME'),
 (13, 'crack_width_mm', '2.4',  'ARCHIVE_FRAME'),
 (13, 'seepage',        'TRUE', 'CONTRACTOR_SHEET'),
 (11, 'deflection_pct', '6.5',  'ARCHIVE_FRAME'),
 (6,  'offset_mm',      '22',   'CONTRACTOR_SHEET'),
 (2,  'leak_rate_lpm',  '1.8',  'ARCHIVE_FRAME')
ON CONFLICT (observation_id, field_name) DO NOTHING;

-- 校正规程域显式插入后的 identity 序列
SELECT setval(pg_get_serial_sequence('app_protocol','id'),           (SELECT max(id) FROM app_protocol));
SELECT setval(pg_get_serial_sequence('app_protocol_category','id'),  (SELECT max(id) FROM app_protocol_category));
SELECT setval(pg_get_serial_sequence('app_protocol_split','id'),     (SELECT max(id) FROM app_protocol_split));
SELECT setval(pg_get_serial_sequence('app_observation_field','id'),  (SELECT max(id) FROM app_observation_field));
