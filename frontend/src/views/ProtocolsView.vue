<template>
  <div>
    <div class="notice info">
      判读规程迁移：规则目录保存新旧类别定义、必要观察字段、生效日期与批准来源；迁移运行读取已归档观察与视频索引，
      生成<b>影子分类</b>与<b>证据缺口</b>，<b>不覆盖原等级或已发布风险结果</b>。影子结果、人工复核与顺位影响分别保存，
      规程再次修订只会产生新的运行。
    </div>

    <!-- ============================ 规则目录 ============================ -->
    <div class="panel">
      <h2>规则目录（新旧规程与类别定义）</h2>
      <table>
        <thead><tr><th>规程</th><th>名称</th><th>生效日期</th><th>批准来源</th><th>状态</th><th>类别数</th><th></th></tr></thead>
        <tbody>
          <tr v-for="p in protocols" :key="p.id">
            <td class="mono">{{ p.protocolCode }}</td>
            <td>{{ p.name }}<div class="muted">{{ p.note }}</div></td>
            <td class="mono">{{ p.effectiveFrom }} ~ {{ p.effectiveTo || '至今' }}</td>
            <td style="max-width:300px" class="muted">{{ p.approvalSource }}</td>
            <td><span class="badge" :class="p.status === 'ACTIVE' ? 'green' : 'gray'">{{ p.status }}</span></td>
            <td>{{ categoryCount(p.id) }}</td>
            <td><button class="btn secondary small" @click="openDiff(p)">规则差异</button></td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="diff" class="panel">
      <h2>规则差异 · {{ diff.target.protocolCode }} 相对 {{ diff.base ? diff.base.protocolCode : '（无基线）' }}</h2>
      <div class="muted" style="margin-bottom:10px">
        旧类别 → 新类别拆分关系、每个新类别需要的观察字段，以及因无映射而被废弃的旧类别。
      </div>
      <table>
        <thead><tr><th>新类别</th><th>名称</th><th>缺陷类型</th><th>旧等级范围</th><th>新严重度</th><th>必要观察字段</th><th>来源旧类别与拆分条件</th></tr></thead>
        <tbody>
          <tr v-for="c in diff.categories" :key="c.category.categoryCode">
            <td class="mono">{{ c.category.categoryCode }}</td>
            <td>{{ c.category.categoryName }}</td>
            <td>{{ defectText(c.category.defectType) }}</td>
            <td>{{ c.category.minGrade }}–{{ c.category.maxGrade }}</td>
            <td>{{ c.category.severityGrade }}</td>
            <td>
              <div v-for="f in c.requiredFields" :key="f.field ? f.field.fieldCode : f" class="muted">
                {{ f.field ? f.field.fieldName : '（未登记字典）' }}
                <span class="mono">{{ f.field ? f.field.fieldCode : '' }}</span>
                <span v-if="f.field" class="badge" :class="recoClass(f.field.recoverability)">
                  {{ recoText(f.field.recoverability) }}
                </span>
              </div>
            </td>
            <td>
              <div v-for="s in c.sources" :key="s.split.id">
                <span class="badge blue">{{ s.legacyCategory ? s.legacyCategory.categoryName : s.split.fromCategoryCode }}</span>
                <span class="mono muted"> 若 {{ s.split.conditionExpr || '无条件' }}</span>
              </div>
              <span v-if="!c.sources.length" class="muted">无拆分来源</span>
            </td>
          </tr>
        </tbody>
      </table>
      <div class="notice warn" style="margin-top:12px">
        被废弃的旧类别（新规程无对应）：
        <b>{{ diff.deletedLegacyCategories.length ? diff.deletedLegacyCategories.join('、') : '无' }}</b>
      </div>
      <h3>依赖索引（哪些原始字段参与哪些新类别判定）</h3>
      <table>
        <thead><tr><th>类别</th><th>参与字段</th><th>来源字段</th><th>同源缺陷类型</th><th>依赖类型</th><th>条件</th></tr></thead>
        <tbody>
          <tr v-for="d in diff.dependencies" :key="d.dependency.id">
            <td class="mono">{{ d.dependency.categoryCode }}</td>
            <td>{{ d.field ? d.field.fieldName : d.dependency.fieldName }}</td>
            <td class="mono">{{ d.dependency.sourceField }}</td>
            <td>{{ defectText(d.dependency.sourceDefectType) }}</td>
            <td><span class="badge" :class="d.dependency.dependencyKind === 'CROSS_CATEGORY' ? 'amber' : 'teal'">
              {{ d.dependency.dependencyKind === 'CROSS_CATEGORY' ? '跨类别' : '自身' }}</span></td>
            <td class="mono">{{ d.dependency.conditionExpr }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- ============================ 发起迁移运行 ============================ -->
    <div class="panel">
      <h2>发起迁移运行</h2>
      <div class="form-grid">
        <div>
          <label>目标规程（新口径）</label>
          <select v-model.number="form.targetProtocolId">
            <option v-for="p in runnableProtocols" :key="p.id" :value="p.id">{{ p.protocolCode }} · {{ p.name }}</option>
          </select>
        </div>
        <div>
          <label>运行方式</label>
          <select v-model="form.mode">
            <option value="FULL_REPLAY">全量重放（覆盖跨类别依赖，得到统一新口径；大规模视频复核慢）</option>
            <option value="INCREMENTAL">增量重算（按依赖索引只重算受影响类别；快但需维护依赖索引）</option>
          </select>
        </div>
        <div>
          <label>资产版本（影子顺位对照）</label>
          <select v-model.number="form.versionId">
            <option v-for="v in versions" :key="v.id" :value="v.id">{{ v.versionNo }} · {{ v.name }}</option>
          </select>
        </div>
        <div v-if="form.mode === 'INCREMENTAL'">
          <label>上一运行（增量基线）</label>
          <select v-model.number="form.previousRunId">
            <option :value="null">自动取最新已完成运行</option>
            <option v-for="r in runs" :key="r.id" :value="r.id">{{ r.runCode }}</option>
          </select>
        </div>
        <div style="display:flex;align-items:flex-end">
          <button class="btn" :disabled="running" @click="runMigration">
            {{ running ? '运行中…' : '开始迁移' }}
          </button>
        </div>
      </div>
      <div class="notice info" style="margin-top:12px">
        全量重放：对全部历史观察按新规程完整重算，覆盖跨类别依赖，得到统一新口径，但大规模视频复核等待长；
        增量重算：根据规则依赖只重算受影响类别和字段，发布快、局部易审，但依赖索引遗漏会留下旧口径。
        两种模式都只产生可追溯的新规程结果，不修改原等级与已发布数据集。
      </div>
      <div v-if="message" class="notice" :class="messageType">{{ message }}</div>
    </div>

    <!-- ============================ 运行列表 ============================ -->
    <div class="panel">
      <h2>迁移运行（只增不改）</h2>
      <table>
        <thead><tr><th>运行编码</th><th>目标规程</th><th>方式</th><th>状态</th><th>开始时间</th><th>观察/影子</th><th>证据缺口</th><th>候选歧义</th><th>沿用</th><th></th></tr></thead>
        <tbody>
          <tr v-for="r in runs" :key="r.id" :style="r.id === selectedRunId ? 'background:#f0f8f9' : ''">
            <td class="mono">{{ r.runCode }}</td>
            <td class="mono">{{ protocolCode(r.targetProtocolId) }}</td>
            <td><span class="badge" :class="r.mode === 'INCREMENTAL' ? 'amber' : 'teal'">
              {{ r.mode === 'INCREMENTAL' ? '增量' : '全量' }}</span></td>
            <td><span class="badge" :class="r.status === 'COMPLETED' ? 'green' : 'red'">{{ r.status }}</span></td>
            <td class="mono">{{ r.startedAt }}</td>
            <td>{{ r.observationCount }} / {{ r.shadowCount }}</td>
            <td><span :class="r.gapCount > 0 ? 'badge red' : 'badge gray'">{{ r.gapCount }}</span></td>
            <td><span :class="r.ambiguousCount > 0 ? 'badge amber' : 'badge gray'">{{ r.ambiguousCount }}</span></td>
            <td>{{ r.reusedCount }}</td>
            <td><button class="btn secondary small" @click="openRun(r.id)">查看</button></td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- ============================ 运行详情 ============================ -->
    <template v-if="detail">
      <div class="panel">
        <h2>运行 {{ detail.run.runCode }} · 顺位影响
          <span class="badge" :class="detail.run.mode === 'INCREMENTAL' ? 'amber' : 'teal'">
            {{ detail.run.mode === 'INCREMENTAL' ? '增量' : '全量' }}</span>
        </h2>
        <div class="notice info">{{ detail.run.summary }}</div>
        <div v-if="detail.run.dependencyWarnings" class="notice warn">
          <b>依赖索引告警：</b>{{ detail.run.dependencyWarnings }}
        </div>
        <div class="muted" style="margin-bottom:8px">
          受影响类别：{{ detail.run.affectedCategories }} ｜ 旧发布顺位与影子顺位分别保存，互不覆盖。
        </div>
        <table>
          <thead><tr><th>管段</th><th>旧发布顺位</th><th>旧最高等级</th><th>影子顺位</th><th>影子最高等级</th><th>影子负担分</th><th>顺位变化</th><th>缺口/歧义</th><th>说明</th></tr></thead>
          <tbody>
            <tr v-for="s in detail.segmentRanks" :key="s.id">
              <td>#{{ s.segmentId }}</td>
              <td>{{ s.oldPublishedRank ?? '—' }}</td>
              <td>{{ s.oldPublishedMaxGrade ?? '—' }}</td>
              <td><b>{{ s.shadowRank ?? '—' }}</b></td>
              <td>{{ s.shadowMaxGrade ?? '—' }}</td>
              <td class="mono">{{ s.shadowBurden }}</td>
              <td>
                <span v-if="s.rankDelta == null" class="muted">—</span>
                <span v-else-if="s.rankDelta > 0" class="badge red">↑{{ s.rankDelta }}</span>
                <span v-else-if="s.rankDelta < 0" class="badge green">↓{{ -s.rankDelta }}</span>
                <span v-else class="badge gray">不变</span>
              </td>
              <td>
                <span v-if="s.gapCount" class="badge red">缺口{{ s.gapCount }}</span>
                <span v-if="s.ambiguousCount" class="badge amber">歧义{{ s.ambiguousCount }}</span>
                <span v-if="!s.gapCount && !s.ambiguousCount" class="badge gray">—</span>
              </td>
              <td class="muted" style="max-width:360px">{{ s.note }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="panel">
        <h2>审查视图 · 影子分类候选（按缺陷类型与来源批次复核）</h2>
        <div class="form-grid" style="margin-bottom:12px">
          <div>
            <label>缺陷类型</label>
            <select v-model="filter.defectType" @change="loadShadows">
              <option value="">全部</option>
              <option v-for="t in defectTypes" :key="t.value" :value="t.value">{{ t.text }}</option>
            </select>
          </div>
          <div>
            <label>来源批次</label>
            <select v-model="filter.batchId" @change="loadShadows">
              <option :value="null">全部</option>
              <option v-for="b in batches" :key="b.id" :value="b.id">{{ b.batchCode }}</option>
            </select>
          </div>
          <div>
            <label>分类结论</label>
            <select v-model="filter.classification" @change="loadShadows">
              <option value="">全部</option>
              <option value="NEW_CATEGORY">已定新类别</option>
              <option value="AMBIGUOUS">候选歧义</option>
              <option value="EVIDENCE_GAP">证据不足</option>
              <option value="OUT_OF_SCOPE">无对应</option>
              <option value="SKIPPED_DUPLICATE">重复件</option>
            </select>
          </div>
          <div>
            <label>规程适用性</label>
            <select v-model="filter.applicability" @change="loadShadows">
              <option value="">全部</option>
              <option value="APPLIES">适用</option>
              <option value="NOT_APPLICABLE_BEFORE">检查早于生效日</option>
              <option value="NOT_APPLICABLE_AFTER">检查晚于失效日</option>
            </select>
          </div>
        </div>

        <div class="muted" style="margin-bottom:8px">共 {{ shadows.length }} 条候选；复核记录单列保存，不改写影子分类或原等级。</div>
        <table>
          <thead>
            <tr><th>OBS</th><th>缺陷/批次</th><th>旧类别/等级</th><th>新类别</th><th>结论</th><th>必要性</th><th>适用性</th><th>依据 / 证据</th><th>复核</th></tr>
          </thead>
          <tbody>
            <tr v-for="row in shadows" :key="row.shadow.id">
              <td>#{{ row.shadow.observationId }}</td>
              <td>{{ defectText(row.shadow.defectType) }}<div class="muted">{{ row.batchCode }}</div></td>
              <td>
                <span class="mono">{{ row.shadow.sourceCategoryCode || '—' }}</span>
                <div class="muted">旧{{ row.shadow.oldGrade ?? '—' }} / 原{{ row.rawGrade }}</div>
              </td>
              <td>
                <span v-if="row.shadow.targetCategoryCode" class="mono">{{ row.shadow.targetCategoryCode }}</span>
                <span v-else-if="row.shadow.candidateCategories" class="mono">
                  {{ row.shadow.candidateCategories.split('|').join(' / ') }}
                </span>
                <span v-else class="muted">—</span>
                <div v-if="row.shadow.newGrade" class="muted">新等级{{ row.shadow.newGrade }}</div>
              </td>
              <td><span class="badge" :class="classClass(row.shadow.classification)">{{ classText(row.shadow.classification) }}</span></td>
              <td>
                <span v-if="row.shadow.missingFields" class="mono muted">{{ row.shadow.missingFields }}</span>
                <span v-else class="muted">—</span>
                <div v-if="row.shadow.staleRisk" class="badge red" style="margin-top:4px">旧口径风险</div>
              </td>
              <td>
                <span class="badge" :class="applClass(row.shadow.applicability)">{{ applText(row.shadow.applicability) }}</span>
                <div class="muted" style="max-width:200px">{{ row.shadow.applicabilityNote }}</div>
              </td>
              <td style="max-width:360px">
                <div class="explain">{{ row.shadow.basis }}</div>
                <div class="muted mono" style="margin-top:4px">
                  证据 {{ row.shadow.evidenceUri || '—' }}<br />视频 {{ row.shadow.videoUri || '—' }}
                </div>
                <div v-if="row.reviews && row.reviews.length" class="notice ok" style="padding:4px 8px;margin-top:6px">
                  <div v-for="rv in row.reviews" :key="rv.id" class="muted">
                    复核：{{ reviewText(rv.decision) }}{{ rv.chosenCategory ? '（' + rv.chosenCategory + '）' : '' }} — {{ rv.note }}
                  </div>
                </div>
              </td>
              <td style="min-width:200px">
                <div v-if="canReview(row.shadow)" class="flex" style="gap:6px">
                  <button class="btn small" @click="openReview(row.shadow, 'ACCEPT_SHADOW')">采信影子</button>
                  <button class="btn secondary small" @click="openReview(row.shadow, 'KEEP_OLD')">保留旧等级</button>
                  <button class="btn secondary small" @click="openReview(row.shadow, 'NEEDS_VIDEO')">需复核视频</button>
                  <button v-if="row.shadow.candidateCategories" class="btn secondary small"
                          @click="openReview(row.shadow, 'CONFIRM_CANDIDATE')">选定候选</button>
                </div>
                <span v-else class="muted">无需复核</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <!-- ============================ 复核弹窗 ============================ -->
    <div v-if="reviewTarget" class="modal-mask" @click.self="reviewTarget = null">
      <div class="modal">
        <h3>复核影子分类 OBS#{{ reviewTarget.shadow.observationId }}</h3>
        <p class="muted">{{ reviewTarget.shadow.basis }}</p>
        <label>复核结论</label>
        <select v-model="reviewForm.decision">
          <option value="ACCEPT_SHADOW">采信影子分类</option>
          <option value="KEEP_OLD">保留旧等级口径</option>
          <option value="NEEDS_VIDEO">需要重新调阅/复拍视频</option>
          <option value="CONFIRM_CANDIDATE">从候选中选定</option>
        </select>
        <div v-if="reviewForm.decision === 'CONFIRM_CANDIDATE'" style="margin-top:12px">
          <label>采信类别（候选）</label>
          <select v-model="reviewForm.chosenCategory">
            <option v-for="c in candidates" :key="c" :value="c">{{ c }}</option>
          </select>
        </div>
        <label style="margin-top:12px">复核说明（记录在案，不覆盖原等级）</label>
        <textarea v-model="reviewForm.note" rows="3" placeholder="例如：调阅原始视频帧，确认宽度与渗漏" />
        <div class="flex" style="margin-top:14px">
          <button class="btn" @click="submitReview">保存复核</button>
          <button class="btn secondary" @click="reviewTarget = null">取消</button>
        </div>
        <div v-if="reviewError" class="notice error" style="margin-top:10px">{{ reviewError }}</div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import http from '../api/http'

const protocolList = ref([])
const fields = ref([])
const runs = ref([])
const versions = ref([])
const batches = ref([])
const diff = ref(null)
const detail = ref(null)
const shadows = ref([])
const selectedRunId = ref(null)
const running = ref(false)
const message = ref('')
const messageType = ref('info')
const filter = reactive({ defectType: '', batchId: null, classification: '', applicability: '' })
const reviewTarget = ref(null)
const reviewForm = reactive({ decision: 'ACCEPT_SHADOW', chosenCategory: '', note: '' })
const reviewError = ref('')

const defectTypes = [
  { value: 'CRACK', text: '裂缝' },
  { value: 'DEFORMATION', text: '变形' },
  { value: 'LEAK', text: '渗漏' },
  { value: 'JOINT_DISLOCATION', text: '接口错位' }
]

const form = reactive({ targetProtocolId: null, mode: 'FULL_REPLAY', versionId: null, previousRunId: null })

const protocols = computed(() => protocolList.value)
const runnableProtocols = computed(() => protocolList.value.filter(p => (p.categories || []).length > 0))
const categoryCount = (id) => protocolList.value.find(p => p.id === id)?.categories?.length ?? 0
const candidates = computed(() => (reviewTarget.value?.shadow.candidateCategories || '').split('|').filter(Boolean))

onMounted(async () => {
  const [cat, runResp, ver, batchResp] = await Promise.all([
    http.get('/protocols/catalog'),
    http.get('/protocols/runs'),
    http.get('/assets/versions'),
    http.get('/inspections/batches')
  ])
  protocolList.value = cat.data.protocols.map(row => ({
    ...row.protocol,
    categories: row.categories,
    legacyCategories: row.legacyCategories
  }))
  fields.value = cat.data.fields
  runs.value = runResp.data
  versions.value = ver.data
  batches.value = batchResp.data
  const active = protocolList.value.find(p => p.protocolCode === 'CCT-2026')
    || runnableProtocols.value[0]
  form.targetProtocolId = active?.id
  form.versionId = versions.value[versions.value.length - 1]?.id
  if (runs.value.length) await openRun(runs.value[0].id)
})

async function openDiff(p) {
  // 基线取生效更早、且自带旧类别定义的最近规程
  const base = protocolList.value
    .filter(x => x.effectiveFrom < p.effectiveFrom && (x.legacyCategories || []).length > 0)
    .pop()
  const { data } = await http.get(`/protocols/${p.id}/diff`, { params: { baseProtocolId: base?.id } })
  diff.value = data
}

async function runMigration() {
  running.value = true
  message.value = ''
  try {
    const payload = {
      targetProtocolId: form.targetProtocolId,
      mode: form.mode,
      versionId: form.versionId,
      previousRunId: form.previousRunId || null
    }
    const { data } = await http.post('/protocols/runs', payload)
    message.value = `已生成运行 ${data.runCode}：${data.summary}`
    messageType.value = 'ok'
    runs.value = (await http.get('/protocols/runs')).data
    await openRun(data.runId)
  } catch (e) {
    message.value = e.response?.data?.error || '运行失败'
    messageType.value = 'error'
  } finally {
    running.value = false
  }
}

async function openRun(id) {
  selectedRunId.value = id
  const [runResp, shadowResp] = await Promise.all([
    http.get(`/protocols/runs/${id}`),
    http.get(`/protocols/runs/${id}/shadows`)
  ])
  detail.value = runResp.data
  shadows.value = shadowResp.data.shadows
}

async function loadShadows() {
  if (!selectedRunId.value) return
  const params = {}
  if (filter.defectType) params.defectType = filter.defectType
  if (filter.batchId) params.batchId = filter.batchId
  if (filter.classification) params.classification = filter.classification
  if (filter.applicability) params.applicability = filter.applicability
  const { data } = await http.get(`/protocols/runs/${selectedRunId.value}/shadows`, { params })
  shadows.value = data.shadows
}

function canReview(shadow) {
  return ['AMBIGUOUS', 'EVIDENCE_GAP', 'NEW_CATEGORY'].includes(shadow.classification)
}

function openReview(shadow, decision) {
  reviewTarget.value = shadow
  reviewForm.decision = decision
  reviewForm.chosenCategory = candidates.value[0] || ''
  reviewForm.note = ''
  reviewError.value = ''
}

async function submitReview() {
  try {
    await http.post(`/protocols/runs/${selectedRunId.value}/shadows/${reviewTarget.value.shadow.id}/review`, {
      decision: reviewForm.decision,
      chosenCategory: reviewForm.decision === 'CONFIRM_CANDIDATE' ? reviewForm.chosenCategory : null,
      note: reviewForm.note
    })
    reviewTarget.value = null
    await loadShadows()
  } catch (e) {
    reviewError.value = e.response?.data?.error || '复核失败'
  }
}

function protocolCode(id) { return protocolList.value.find(p => p.id === id)?.protocolCode || id }
function defectText(t) { return defectTypes.find(d => d.value === t)?.text || t }
function recoText(r) {
  return { NOT_MEASURABLE_FROM_ARCHIVE: '旧画面不可测', NOT_RECORDED: '旧归档未登记', ALWAYS_AVAILABLE: '随时可得' }[r] || r
}
function recoClass(r) {
  return { NOT_MEASURABLE_FROM_ARCHIVE: 'red', NOT_RECORDED: 'amber', ALWAYS_AVAILABLE: 'green' }[r] || 'gray'
}
function classText(c) {
  return { NEW_CATEGORY: '已定新类别', AMBIGUOUS: '候选歧义', EVIDENCE_GAP: '证据不足',
    OUT_OF_SCOPE: '无对应', UNCLASSIFIED: '规则未覆盖', SKIPPED_DUPLICATE: '重复件' }[c] || c
}
function classClass(c) {
  return { NEW_CATEGORY: 'green', AMBIGUOUS: 'amber', EVIDENCE_GAP: 'red',
    OUT_OF_SCOPE: 'gray', UNCLASSIFIED: 'gray', SKIPPED_DUPLICATE: 'gray' }[c] || 'gray'
}
function applText(a) {
  return { APPLIES: '适用', NOT_APPLICABLE_BEFORE: '早于生效日', NOT_APPLICABLE_AFTER: '晚于失效日' }[a] || a
}
function applClass(a) {
  return { APPLIES: 'green', NOT_APPLICABLE_BEFORE: 'amber', NOT_APPLICABLE_AFTER: 'amber' }[a] || 'gray'
}
function reviewText(d) {
  return { ACCEPT_SHADOW: '采信影子', KEEP_OLD: '保留旧等级', NEEDS_VIDEO: '需复核视频',
    CONFIRM_CANDIDATE: '选定候选' }[d] || d
}
</script>
