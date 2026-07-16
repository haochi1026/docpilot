<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { api, clearToken, saveToken, token } from './api'

const logged = ref(Boolean(token()))
const loginForm = ref({ username: 'student', password: '123456' })
const user = ref(JSON.parse(localStorage.getItem('docpilot_user') || 'null'))
const kbs = ref([])
const currentKb = ref(null)
const documents = ref([])
const overview = ref({})
const capabilities = ref({})
const members = ref([])
const indexStatus = ref({ enabled: false, totalChunks: 0, indexedChunks: 0, complete: false, model: '' })
const modelForm = ref({
  aiMode: 'local', aiBaseUrl: 'http://host.docker.internal:11434/v1', aiModel: 'qwen3.5:2b',
  embeddingMode: 'local', embeddingBaseUrl: 'http://host.docker.internal:11434/v1', embeddingModel: 'qwen3-embedding:0.6b'
})
const availableModels = ref([])
const modelBusy = ref(false)
const reindexing = ref(false)
const activeView = ref('overview')
const question = ref('这个知识库主要讲了什么？')
const messages = ref([])
const conversations = ref([])
const currentConversationId = ref(null)
const conversationBusy = ref(false)
const uploading = ref(false)
const asking = ref(false)
const pendingApproval = ref(null)
const notice = ref('')
const error = ref('')
const documentKeyword = ref('')
const documentStatus = ref('ALL')
const detailOpen = ref(false)
const documentDetail = ref(null)
const createOpen = ref(false)
const kbForm = ref({ name: '', description: '' })
const memberForm = ref({ username: '', permission: 'READ' })
let poller = null

const readyCount = computed(() => documents.value.filter(item => item.status === 'SUCCESS').length)
const filteredDocuments = computed(() => documents.value.filter(item => {
  const keyword = documentKeyword.value.trim().toLowerCase()
  return (documentStatus.value === 'ALL' || item.status === documentStatus.value)
    && (!keyword || item.originalName.toLowerCase().includes(keyword))
}))
const isAdmin = computed(() => user.value?.role === 'ADMIN')
const canCreateKb = computed(() => ['ADMIN', 'MANAGER'].includes(user.value?.role))
const canManageKb = computed(() => currentKb.value?.permission === 'MANAGE')
const currentConversation = computed(() => conversations.value.find(item => item.id === currentConversationId.value) || null)

function flash(text, bad = false) {
  notice.value = bad ? '' : text
  error.value = bad ? text : ''
  setTimeout(() => { notice.value = ''; error.value = '' }, 4000)
}
function size(value = 0) {
  if (value < 1024) return `${value} B`
  if (value < 1048576) return `${(value / 1024).toFixed(1)} KB`
  return `${(value / 1048576).toFixed(1)} MB`
}
function time(value) {
  return value ? value.replace('T', ' ').slice(0, 16) : '-'
}
function statusLabel(status) {
  return ({ SUCCESS: '已就绪', PROCESSING: '解析中', PENDING: '等待解析', FAILED: '解析失败' })[status] || status
}
function permissionLabel(permission) {
  return ({ OWNER: '负责人', MANAGE: '可管理', READ: '仅使用' })[permission] || permission
}
function roleLabel(role) {
  return ({ ADMIN: '平台管理员', MANAGER: '部门主管', USER: '普通成员' })[role] || role
}
function formatIcon(name = '') {
  return name.includes('.') ? name.split('.').pop().toUpperCase().slice(0, 4) : 'FILE'
}

async function login() {
  try {
    const data = await api('/api/auth/login', { method: 'POST', body: JSON.stringify(loginForm.value) })
    saveToken(data.token)
    localStorage.setItem('docpilot_user', JSON.stringify(data))
    user.value = data
    logged.value = true
    await loadKbs()
  } catch (e) { flash(e.message, true) }
}
function logout() {
  clearToken()
  localStorage.removeItem('docpilot_user')
  logged.value = false
  user.value = null
  clearInterval(poller)
}
async function loadKbs(preferredId) {
  try {
    const [kbData, capabilityData] = await Promise.all([
      api('/api/kbs'), api('/api/system/capabilities')
    ])
    kbs.value = kbData
    capabilities.value = capabilityData
    const selected = kbs.value.find(item => item.id === preferredId)
      || kbs.value.find(item => item.id === currentKb.value?.id)
      || kbs.value[0]
    if (selected) await selectKb(selected)
  } catch (e) { flash(e.message, true) }
}
async function selectKb(kb) {
  currentKb.value = kb
  messages.value = []
  conversations.value = []
  currentConversationId.value = null
  detailOpen.value = false
  await loadWorkspace()
  await refreshConversationList()
  if (conversations.value.length) await selectConversation(conversations.value[0], false)
}
async function loadWorkspace() {
  if (!currentKb.value) return
  try {
    const [docData, overviewData, memberData, indexData] = await Promise.all([
      api(`/api/documents?kbId=${currentKb.value.id}`),
      api(`/api/workspace/overview?kbId=${currentKb.value.id}`),
      api(`/api/kbs/${currentKb.value.id}/members`),
      api(`/api/kbs/${currentKb.value.id}/index-status`)
    ])
    documents.value = docData
    overview.value = overviewData
    members.value = memberData
    indexStatus.value = indexData
  } catch (e) { flash(e.message, true) }
}
async function createKnowledgeBase() {
  try {
    const result = await api('/api/kbs', { method: 'POST', body: JSON.stringify(kbForm.value) })
    kbForm.value = { name: '', description: '' }
    createOpen.value = false
    flash('知识库已创建')
    await loadKbs(result.id)
  } catch (e) { flash(e.message, true) }
}
async function grantMember() {
  if (!canManageKb.value) return
  try {
    await api(`/api/kbs/${currentKb.value.id}/members`, { method: 'POST', body: JSON.stringify(memberForm.value) })
    flash('成员权限已更新')
    memberForm.value = { username: '', permission: 'READ' }
    await loadWorkspace()
  } catch (e) { flash(e.message, true) }
}
async function revokeMember(member) {
  if (!confirm(`确认移除 ${member.displayName} 的知识库权限吗？`)) return
  try {
    await api(`/api/kbs/${currentKb.value.id}/members/${member.userId}`, { method: 'DELETE' })
    flash('成员权限已移除')
    await loadWorkspace()
  } catch (e) { flash(e.message, true) }
}
async function upload(event) {
  const file = event.target.files?.[0]
  if (!file || !currentKb.value) return
  uploading.value = true
  try {
    const form = new FormData()
    form.append('file', file)
    await api(`/api/documents?kbId=${currentKb.value.id}`, { method: 'POST', body: form, raw: true })
    flash('文件已入库，解析任务将在后台执行')
    activeView.value = 'documents'
    await loadWorkspace()
  } catch (e) { flash(e.message, true) }
  finally { uploading.value = false; event.target.value = '' }
}
async function openDocument(item) {
  try {
    documentDetail.value = await api(`/api/documents/${item.id}`)
    detailOpen.value = true
  } catch (e) { flash(e.message, true) }
}
async function retryDocument(item) {
  try {
    await api(`/api/documents/${item.id}/retry`, { method: 'POST' })
    flash('已重新提交解析任务')
    await loadWorkspace()
  } catch (e) { flash(e.message, true) }
}
async function deleteDocument(item) {
  if (!confirm(`确认删除“${item.originalName}”及其全部检索片段吗？`)) return
  try {
    await api(`/api/documents/${item.id}`, { method: 'DELETE' })
    flash('文档及其检索索引已删除')
    await loadWorkspace()
  } catch (e) { flash(e.message, true) }
}
async function rebuildIndex() {
  if (!indexStatus.value.enabled) {
    flash('请先在模型设置中启用向量检索', true)
    return
  }
  reindexing.value = true
  try {
    const result = await api(`/api/kbs/${currentKb.value.id}/reindex`, { method: 'POST' })
    flash(`向量索引重建完成，共处理 ${result.indexedChunks} 个片段`)
    indexStatus.value = await api(`/api/kbs/${currentKb.value.id}/index-status`)
  } catch (e) { flash(e.message, true) }
  finally { reindexing.value = false }
}
async function openModelSettings() {
  activeView.value = 'settings'
  if (!isAdmin.value) return
  try {
    modelForm.value = await api('/api/system/settings')
    availableModels.value = await api('/api/system/models').catch(() => [])
  } catch (e) { flash(e.message, true) }
}
async function saveModelSettings() {
  modelBusy.value = true
  try {
    await api('/api/system/settings', { method: 'PUT', body: JSON.stringify(modelForm.value) })
    capabilities.value = await api('/api/system/capabilities')
    indexStatus.value = await api(`/api/kbs/${currentKb.value.id}/index-status`)
    availableModels.value = await api('/api/system/models').catch(() => [])
    flash('模型配置已立即生效；如更换了向量模型，请重建当前知识库索引')
  } catch (e) { flash(e.message, true) }
  finally { modelBusy.value = false }
}
async function testModelSettings() {
  modelBusy.value = true
  try {
    const result = await api('/api/system/test-models', { method: 'POST', body: JSON.stringify(modelForm.value) })
    const embedding = result.embeddingDimensions ? `，向量维度 ${result.embeddingDimensions}` : ''
    flash(`模型连接正常${embedding}`)
  } catch (e) { flash(`连接检测失败：${e.message}`, true) }
  finally { modelBusy.value = false }
}
async function refreshConversationList(preferredId) {
  if (!currentKb.value) return
  try {
    conversations.value = await api(`/api/conversations?kbId=${currentKb.value.id}`)
    if (preferredId && conversations.value.some(item => item.id === preferredId)) {
      currentConversationId.value = preferredId
    }
  } catch (e) { flash(e.message, true) }
}
async function selectConversation(conversation, navigate = true) {
  if (asking.value || !conversation) return
  conversationBusy.value = true
  try {
    messages.value = await api(`/api/conversations/${conversation.id}/messages`)
    currentConversationId.value = conversation.id
    if (navigate) activeView.value = 'chat'
  } catch (e) { flash(e.message, true) }
  finally { conversationBusy.value = false }
}
async function createConversation() {
  if (!currentKb.value) return null
  conversationBusy.value = true
  try {
    const created = await api('/api/conversations', {
      method: 'POST', body: JSON.stringify({ kbId: currentKb.value.id, title: '新对话' })
    })
    await refreshConversationList(created.id)
    await selectConversation(created)
    return created
  } catch (e) { flash(e.message, true); return null }
  finally { conversationBusy.value = false }
}
async function renameConversation(conversation) {
  const title = prompt('输入新的对话标题', conversation.title)
  if (!title?.trim()) return
  try {
    await api(`/api/conversations/${conversation.id}`, {
      method: 'PATCH', body: JSON.stringify({ title: title.trim() })
    })
    await refreshConversationList(conversation.id)
  } catch (e) { flash(e.message, true) }
}
async function deleteConversation(conversation) {
  if (!confirm(`确认删除对话“${conversation.title}”及全部消息吗？`)) return
  try {
    await api(`/api/conversations/${conversation.id}`, { method: 'DELETE' })
    const wasCurrent = currentConversationId.value === conversation.id
    await refreshConversationList()
    if (wasCurrent) {
      messages.value = []
      currentConversationId.value = null
      if (conversations.value.length) await selectConversation(conversations.value[0])
    }
    await loadWorkspace()
    flash('对话已删除')
  } catch (e) { flash(e.message, true) }
}
async function openConversationFromOverview(item) {
  activeView.value = 'chat'
  await refreshConversationList(item.id)
  const target = conversations.value.find(conversation => conversation.id === item.id)
  if (target) await selectConversation(target)
  else flash('该历史对话已被删除', true)
}

function approvalActions(payload) {
  const interrupts = Array.isArray(payload) ? payload : [payload]
  return interrupts.flatMap(item => item?.action_requests || item?.actionRequests || [])
}

async function consumeChatStream(response, answer) {
  if (!response.ok) {
    const body = await response.json().catch(() => ({}))
    throw new Error(body.message || `请求失败 (${response.status})`)
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  while (true) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const blocks = buffer.split('\n\n')
    buffer = blocks.pop() || ''
    for (const block of blocks) {
      let event = 'message'; let data = ''
      for (const line of block.split('\n')) {
        if (line.startsWith('event:')) event = line.slice(6).trim()
        if (line.startsWith('data:')) data += line.slice(5).trim()
      }
      if (event === 'conversation') currentConversationId.value = Number(data)
      else if (event === 'token') answer.content += data
      else if (event === 'replace') answer.content = data
      else if (event === 'status') answer.status = data
      else if (event === 'sources') {
        try { answer.sources = JSON.parse(data) } catch (_) {}
      } else if (event === 'approval') {
        try {
          answer.approval = JSON.parse(data)
          pendingApproval.value = answer
          answer.status = '等待你确认操作'
        } catch (_) { throw new Error('Agent 审批数据解析失败') }
      } else if (event === 'error') throw new Error(data)
      else if (event === 'done') {
        answer.status = ''
        answer.approval = null
        pendingApproval.value = null
      }
    }
  }
}

async function reviewAgent(answer, decision) {
  if (asking.value || !answer?.approval || !currentConversationId.value) return
  asking.value = true
  answer.status = decision === 'approve' ? '正在执行已批准操作…' : '正在拒绝操作并恢复 Agent…'
  try {
    const response = await fetch('/api/chat/resume', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token()}` },
      body: JSON.stringify({
        kbId: currentKb.value.id,
        conversationId: currentConversationId.value,
        decision
      })
    })
    answer.approval = null
    pendingApproval.value = null
    await consumeChatStream(response, answer)
  } catch (e) { answer.content += `\n\n[错误] ${e.message}` }
  finally {
    answer.status = answer.approval ? '等待你确认操作' : ''
    asking.value = false
    await Promise.all([loadWorkspace(), refreshConversationList(currentConversationId.value)])
  }
}

async function ask() {
  if (!question.value.trim() || asking.value || !currentKb.value) return
  if (!currentConversationId.value) {
    const created = await createConversation()
    if (!created) return
  }
  const text = question.value.trim()
  messages.value.push({ role: 'user', content: text })
  const answer = { role: 'assistant', content: '', sources: [], status: '正在连接…', approval: null }
  messages.value.push(answer)
  asking.value = true
  question.value = ''
  try {
    const response = await fetch('/api/chat/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token()}` },
      body: JSON.stringify({ kbId: currentKb.value.id, conversationId: currentConversationId.value, question: text })
    })
    await consumeChatStream(response, answer)
  } catch (e) { answer.content += `\n\n[错误] ${e.message}` }
  finally {
    answer.status = answer.approval ? '等待你确认操作' : ''
    asking.value = false
    await Promise.all([loadWorkspace(), refreshConversationList(currentConversationId.value)])
  }
}

onMounted(() => {
  if (logged.value) loadKbs()
  poller = setInterval(() => {
    if (logged.value && currentKb.value && documents.value.some(item => ['PENDING', 'PROCESSING'].includes(item.status))) loadWorkspace()
  }, 2500)
})
onBeforeUnmount(() => clearInterval(poller))
</script>

<template>
  <div v-if="!logged" class="entry-page">
    <aside class="entry-cover" aria-hidden="true">
      <div class="entry-brand"><i>DP</i><b>DocPilot</b></div>
      <div class="entry-art"><span></span><span></span><span></span><i></i></div>
      <div class="entry-index">PRIVATE WORKSPACE&nbsp;&nbsp;/&nbsp;&nbsp;01</div>
    </aside>
    <main class="entry-panel">
      <form class="entry-form" @submit.prevent="login">
        <div class="entry-heading"><span>DocPilot</span><h1>登录</h1><p>使用组织账号继续</p></div>
        <label>用户名<input v-model="loginForm.username" autocomplete="username"></label>
        <label>密码<input v-model="loginForm.password" type="password" autocomplete="current-password"></label>
        <button>进入工作区</button>
        <div v-if="error" class="toast bad">{{ error }}</div>
      </form>
      <span class="entry-copyright">© 2026 DocPilot</span>
    </main>
  </div>

  <div v-else class="shell">
    <aside>
      <div class="logo"><i>DP</i> DocPilot</div>
      <div class="aside-head"><span>知识库空间</span><button v-if="canCreateKb" title="创建知识库" @click="createOpen=true">＋</button></div>
      <div class="kb-list">
        <button v-for="kb in kbs" :key="kb.id" class="kb" :class="{active:currentKb?.id===kb.id}" @click="selectKb(kb)">
          <i>{{ kb.name.slice(0, 1) }}</i><span><b>{{ kb.name }}</b><small>{{ kb.documentCount }} 篇文档 · {{ permissionLabel(kb.permission) }}</small></span>
        </button>
      </div>
      <div class="security-note"><i>✓</i><div><b>权限边界已启用</b><span>检索前校验知识库 ACL</span></div></div>
      <div class="aside-user"><span><b>{{ user?.displayName }}</b><small>{{ roleLabel(user?.role) }}</small></span><button @click="logout">退出</button></div>
    </aside>

    <main v-if="currentKb">
      <header>
        <div><div class="breadcrumb">工作区 / {{ currentKb.departmentName || '独立空间' }}</div><h1>{{ currentKb.name }}</h1><p>负责人 {{ currentKb.ownerName }} · {{ currentKb.description }}</p></div>
        <div class="header-actions"><span class="private-badge">{{ permissionLabel(currentKb.permission) }}</span><label v-if="canManageKb" class="upload"><input type="file" accept=".pdf,.doc,.docx,.txt,.md,.ppt,.pptx,.xls,.xlsx" @change="upload"><span>{{ uploading?'上传中…':'上传文档' }}</span></label></div>
      </header>
      <nav class="view-tabs"><button :class="{active:activeView==='overview'}" @click="activeView='overview'">空间概览</button><button :class="{active:activeView==='chat'}" @click="activeView='chat'">检索问答</button><button :class="{active:activeView==='documents'}" @click="activeView='documents'">文档资料</button><button :class="{active:activeView==='members'}" @click="activeView='members'">成员权限</button><button v-if="canManageKb" :class="{active:activeView==='settings'}" @click="openModelSettings">模型与索引</button></nav>
      <div v-if="notice" class="toast ok">{{ notice }}</div><div v-if="error" class="toast bad">{{ error }}</div>

      <template v-if="activeView==='overview'">
        <section class="workspace-summary"><div><h2>工作区概览</h2><p>当前空间的文档、解析任务与问答记录。</p></div><div class="readiness"><span>文档就绪率</span><b>{{ overview.readyRate || 0 }}%</b></div></section>
        <section class="metrics"><article><div><b>{{ overview.documentCount || 0 }}</b><span>入库文档</span></div></article><article><div><b>{{ overview.chunkCount || 0 }}</b><span>可检索片段</span></div></article><article><div><b>{{ overview.questionCount || 0 }}</b><span>累计问答</span></div></article><article><div><b>{{ overview.avgDurationMs || 0 }} ms</b><span>平均处理耗时</span></div></article></section>
        <section class="model-strip"><div><small>回答模式</small><b>{{ capabilities.answerMode }}</b><span>{{ capabilities.aiModel }}</span></div><div><small>检索模式</small><b>{{ capabilities.retrievalMode }}</b><span>{{ capabilities.embeddingModel }}</span></div><div><small>解析任务</small><b>{{ capabilities.queueMode==='rocketmq'?'RocketMQ':'本地任务队列' }}</b><span>状态条件更新保证幂等</span></div></section>
        <section v-if="canManageKb" class="index-maintenance"><div><i :class="{ready:indexStatus.complete}"></i><span><b>{{ indexStatus.enabled ? '向量索引维护' : '当前使用关键词检索' }}</b><small v-if="indexStatus.enabled">当前模型已覆盖 {{ indexStatus.indexedChunks }}/{{ indexStatus.totalChunks }} 个文档片段；原文件无需删除或重新上传。</small><small v-else>启用向量检索后，可以直接为已有文档生成向量，不需要重新上传。</small></span></div><button v-if="indexStatus.enabled" :disabled="reindexing||!indexStatus.totalChunks" @click="rebuildIndex">{{ reindexing?'正在重建…':'重建现有文档索引' }}</button><button v-else @click="openModelSettings">配置向量模型</button></section>
        <section class="overview-grid">
          <div class="panel"><div class="panel-title"><div><b>解析状态</b></div><span>{{ overview.processingCount || 0 }} 个处理中</span></div><div class="status-cards"><article v-for="item in overview.statusDistribution || []" :key="item.status"><i :class="item.status.toLowerCase()"></i><div><b>{{ statusLabel(item.status) }}</b><span>{{ item.count }} 篇文档</span></div></article><div v-if="!overview.statusDistribution?.length" class="empty">尚未上传文档</div></div><div class="format-row"><span v-for="item in overview.formatDistribution || []" :key="item.format"><b>{{ item.format }}</b>{{ item.count }}</span></div><div class="access-summary"><div><span>当前权限</span><b>{{ permissionLabel(currentKb.permission) }}</b></div><div><span>可访问成员</span><b>{{ members.length }} 位</b></div></div></div>
          <div class="panel audit-panel">
            <div class="panel-title"><div><b>最近对话</b><small>点击可继续查看完整历史</small></div><span>{{ overview.recentConversations?.length || 0 }} 个</span></div>
            <div v-if="!overview.recentConversations?.length" class="empty">新建对话并完成问答后将在此记录</div>
            <article v-for="item in overview.recentConversations || []" :key="item.id" class="audit-link" @click="openConversationFromOverview(item)">
              <div class="audit-icon">Q</div><div><b>{{ item.title }}</b><span>{{ item.messageCount }} 条消息 · {{ item.preview || '暂无回答' }}</span></div><time>{{ time(item.updatedAt) }}</time>
            </article>
          </div>
        </section>
      </template>

      <template v-else-if="activeView==='chat'">
        <section class="chat-layout">
          <aside class="conversation-sidebar panel">
            <div class="conversation-sidebar-head"><b>对话</b><button :disabled="conversationBusy" @click="createConversation">＋ 新建</button></div>
            <div class="conversation-list">
              <div v-if="!conversations.length" class="conversation-empty"><span>还没有对话</span><button @click="createConversation">创建第一个对话</button></div>
              <article v-for="item in conversations" :key="item.id" :class="{active:item.id===currentConversationId}">
                <button class="conversation-select" :disabled="asking" @click="selectConversation(item)"><b>{{ item.title }}</b><span>{{ item.messageCount }} 条消息 · {{ time(item.updatedAt) }}</span></button>
                <div class="conversation-actions"><button title="重命名" @click="renameConversation(item)">改</button><button title="删除" @click="deleteConversation(item)">删</button></div>
              </article>
            </div>
          </aside>
          <div class="chat-main panel">
            <div class="panel-title"><div><b>{{ currentConversation?.title || '新对话' }}</b><small>{{ currentConversation ? '历史消息会自动保存' : '选择或创建一个对话后开始提问' }}</small></div><span class="online"><i></i>已连接</span></div>
            <div class="conversation">
              <div v-if="conversationBusy" class="conversation-loading">正在读取历史消息…</div>
              <div v-else-if="!messages.length" class="welcome"><div>✦</div><h2>从 {{ readyCount }} 篇就绪文档中提问</h2><p>每个对话独立保存问题、回答和引用来源。</p><div><button @click="question='请概括知识库中的核心内容'">概括核心内容</button><button @click="question='有哪些需要特别注意的规定？'">查找注意事项</button><button @click="question='请列出关键流程和时间要求'">整理关键流程</button></div></div>
              <div v-for="(message,index) in messages" :key="message.id || index" class="message" :class="message.role"><div class="avatar">{{ message.role==='user'?'我':'DP' }}</div><div class="bubble"><p>{{ message.content }}<span v-if="message.status" class="typing">{{ message.status }}</span></p><div v-if="message.approval" class="agent-approval"><b>Agent 请求执行以下写操作</b><div v-for="(action,actionIndex) in approvalActions(message.approval)" :key="actionIndex" class="approval-action"><strong>{{ action.name }}</strong><code>{{ JSON.stringify(action.arguments) }}</code></div><div class="approval-buttons"><button :disabled="asking" @click="reviewAgent(message,'reject')">拒绝</button><button :disabled="asking" class="approve" @click="reviewAgent(message,'approve')">批准并继续</button></div></div><details v-if="message.sources?.length"><summary>{{ message.sources.length }} 条引用来源</summary><div v-for="(source,sourceIndex) in message.sources" :key="sourceIndex" class="source"><div><b>[{{ sourceIndex+1 }}] {{ source.documentName }}</b><em>相关度 {{ Math.round(source.score*100) }}%</em></div><span>{{ source.content.slice(0,220) }}{{source.content.length>220?'…':''}}</span></div></details></div></div>
            </div>
            <form class="ask" @submit.prevent="ask"><textarea v-model="question" rows="2" placeholder="基于当前知识库提问，Ctrl + Enter 发送" @keydown.ctrl.enter="ask"></textarea><button :disabled="asking||conversationBusy||!readyCount">{{ asking?'生成中':'发送' }}</button></form>
          </div>
          <aside class="chat-guide panel"><div class="panel-title"><div><b>当前连接</b></div></div><div class="connection-list"><div><span>回答</span><b>{{ capabilities.answerMode }}</b></div><div><span>检索</span><b>{{ capabilities.retrievalMode }}</b></div><div><span>模型</span><b>{{ capabilities.aiModel }}</b></div><div><span>向量</span><b>{{ capabilities.embeddingModel }}</b></div></div><div class="mode-card" :class="{enhanced:capabilities.agentEnabled||capabilities.aiMode==='openai'}"><b>{{ capabilities.agentEnabled?'Agent 工具编排已启用':(capabilities.aiMode==='openai'?'模型服务可用':'本地模式') }}</b><span>单用户 12 次/分钟 · 最多 3 路推理</span></div></aside>
        </section>
      </template>

      <template v-else-if="activeView==='documents'">
        <section class="document-heading"><div><h2>文档管理</h2><p>查看当前空间的入库文件与解析状态。</p></div><div><b>{{ readyCount }}/{{ documents.length }}</b><span>文档已就绪</span></div></section>
        <section class="document-panel panel"><div class="document-tools" :class="{readonly:!canManageKb}"><input v-model="documentKeyword" placeholder="搜索文档名称"><select v-model="documentStatus"><option value="ALL">全部状态</option><option value="SUCCESS">已就绪</option><option value="PROCESSING">解析中</option><option value="PENDING">等待解析</option><option value="FAILED">解析失败</option></select><label v-if="canManageKb" class="upload compact"><input type="file" accept=".pdf,.doc,.docx,.txt,.md,.ppt,.pptx,.xls,.xlsx" @change="upload"><span>上传新文档</span></label></div><div class="document-table"><div class="document-row table-head"><span>文档</span><span>大小与时间</span><span>解析状态</span><span>操作</span></div><div v-if="!filteredDocuments.length" class="empty">当前筛选条件下没有文档</div><article v-for="item in filteredDocuments" :key="item.id" class="document-row"><div class="document-name"><i>{{ formatIcon(item.originalName) }}</i><span><b>{{ item.originalName }}</b><small>{{ item.contentType || 'application/octet-stream' }}</small></span></div><div><b>{{ size(item.sizeBytes) }}</b><small>{{ time(item.createdAt) }}</small></div><div><strong :class="item.status.toLowerCase()">{{ statusLabel(item.status) }}</strong><small v-if="item.errorMessage">{{ item.errorMessage }}</small></div><div class="row-actions"><button @click="openDocument(item)">详情</button><button v-if="canManageKb&&item.status==='FAILED'" class="retry" @click="retryDocument(item)">重试</button><button v-if="canManageKb" class="delete" @click="deleteDocument(item)">删除</button></div></article></div></section>
      </template>

      <template v-else-if="activeView==='members'">
        <section class="member-heading"><div><h2>成员与权限</h2><p>管理当前知识库的访问成员与权限级别。</p></div><div><b>{{ members.length }}</b><span>当前成员</span></div></section>
        <section class="member-layout"><div class="panel member-list"><div class="member-row member-table-head"><span>成员</span><span>账号</span><span>知识库权限</span><span>操作</span></div><article v-for="member in members" :key="member.userId" class="member-row"><div class="member-person"><i>{{ member.displayName.slice(0,1) }}</i><b>{{ member.displayName }}</b></div><span>{{ member.username }}</span><strong :class="member.permission.toLowerCase()">{{ permissionLabel(member.permission) }}</strong><button v-if="canManageKb&&!member.fixed" @click="revokeMember(member)">移除</button><em v-else>—</em></article></div><aside class="panel permission-panel"><template v-if="canManageKb"><small>成员授权</small><h3>添加或调整成员</h3><p>输入系统中已有的用户名；重复添加会更新该用户的知识库权限。</p><form @submit.prevent="grantMember"><label>用户名<input v-model="memberForm.username" required placeholder="例如 student"></label><label>权限级别<select v-model="memberForm.permission"><option value="READ">仅使用：检索、问答、查看资料</option><option value="MANAGE">可管理：上传资料和维护成员</option></select></label><button class="primary">保存成员权限</button></form></template><template v-else><small>只读权限</small><h3>你拥有“仅使用”权限</h3><p>可以检索问答、浏览文档和查看成员列表，但不能上传资料或调整其他人的权限。</p></template><div class="permission-guide"><article><b>仅使用</b><span>查看资料、检索问答、核验引用</span></article><article><b>可管理</b><span>包含仅使用权限，并可上传文档、重试任务和维护成员</span></article><article><b>负责人</b><span>知识库最高权限，不可被普通管理成员移除</span></article></div></aside></section>
      </template>

      <template v-else>
        <section class="settings-heading"><div><h2>模型与索引</h2><p>查看当前问答链路，并维护已有文档的向量索引。</p></div><span :class="['runtime-state',{online:capabilities.aiMode==='openai'}]">{{ capabilities.aiMode==='openai'?'模型服务已启用':'本地模式' }}</span></section>
        <section class="settings-grid">
          <div class="panel model-settings">
            <div class="panel-title"><div><b>模型配置</b><small>{{ isAdmin?'保存后立即生效，无需重启后端':'仅平台管理员可以修改全局模型配置' }}</small></div></div>
            <form v-if="isAdmin" @submit.prevent="saveModelSettings">
              <div class="setting-section"><div class="setting-copy"><b>回答生成</b><span>可在本地抽取式回答和 Ollama/OpenAI 兼容模型之间切换。</span></div><label>运行模式<select v-model="modelForm.aiMode"><option value="local">本地抽取式回答</option><option value="openai">大模型归纳回答</option></select></label><label>模型名称<input v-model="modelForm.aiModel" list="ollama-models" placeholder="qwen3.5:2b"></label><label class="wide">服务地址<input v-model="modelForm.aiBaseUrl" placeholder="http://host.docker.internal:11434/v1"></label></div>
              <div class="setting-section"><div class="setting-copy"><b>文档检索</b><span>启用向量检索后，与关键词得分进行混合召回。</span></div><label>运行模式<select v-model="modelForm.embeddingMode"><option value="local">仅关键词检索</option><option value="openai">向量 + 关键词混合检索</option></select></label><label>Embedding 模型<input v-model="modelForm.embeddingModel" list="ollama-models" placeholder="qwen3-embedding:0.6b"></label><label class="wide">服务地址<input v-model="modelForm.embeddingBaseUrl" placeholder="http://host.docker.internal:11434/v1"></label></div>
              <datalist id="ollama-models"><option v-for="model in availableModels" :key="model.name" :value="model.name">{{ model.parameterSize || model.family }}</option></datalist>
              <div class="settings-actions"><button class="secondary" type="button" :disabled="modelBusy" @click="testModelSettings">检测表单配置</button><button class="primary" :disabled="modelBusy">{{ modelBusy?'处理中…':'保存并应用' }}</button></div>
            </form>
            <div v-else class="readonly-settings"><article><span>回答模式</span><b>{{ capabilities.answerMode }}</b><small>{{ capabilities.aiModel }}</small></article><article><span>检索模式</span><b>{{ capabilities.retrievalMode }}</b><small>{{ capabilities.embeddingModel }}</small></article><p>如需切换全局模型，请使用平台管理员账号进入此页面。</p></div>
          </div>
          <aside class="panel index-panel"><div class="panel-title"><div><b>当前知识库索引</b><small>{{ currentKb.name }}</small></div></div><div class="index-count"><b>{{ indexStatus.indexedChunks }}</b><span>/ {{ indexStatus.totalChunks }} 个片段</span></div><div class="index-progress"><i :style="{width:indexStatus.totalChunks?`${Math.round(indexStatus.indexedChunks/indexStatus.totalChunks*100)}%`:'0%'}"></i></div><dl><div><dt>向量模型</dt><dd>{{ indexStatus.model }}</dd></div><div><dt>索引状态</dt><dd>{{ indexStatus.complete?'已完成':indexStatus.enabled?'需要重建':'未启用' }}</dd></div></dl><button v-if="indexStatus.enabled" :disabled="reindexing||!indexStatus.totalChunks" @click="rebuildIndex">{{ reindexing?'正在处理已有文档…':'重建现有文档索引' }}</button><p>重建只读取已经解析的文档片段并生成向量，不会删除原文件，也不需要重新上传。</p></aside>
        </section>
      </template>
    </main>

    <aside v-if="detailOpen" class="detail-drawer"><div class="drawer-head"><div><small>文档信息</small><h2>解析详情</h2></div><button @click="detailOpen=false">×</button></div><template v-if="documentDetail"><div class="detail-file"><i>{{ formatIcon(documentDetail.originalName) }}</i><div><b>{{ documentDetail.originalName }}</b><span>{{ size(documentDetail.sizeBytes) }} · v{{ documentDetail.version }}</span></div><strong :class="documentDetail.status.toLowerCase()">{{ statusLabel(documentDetail.status) }}</strong></div><dl><div><dt>片段数量</dt><dd>{{ documentDetail.chunkCount }}</dd></div><div><dt>入库时间</dt><dd>{{ time(documentDetail.createdAt) }}</dd></div><div><dt>最后更新</dt><dd>{{ time(documentDetail.updatedAt) }}</dd></div></dl><div class="chunk-title"><small>文档内容</small><b>切片预览</b></div><div v-if="!documentDetail.chunks?.length" class="empty">文档尚未生成可检索片段</div><article v-for="chunk in documentDetail.chunks || []" :key="chunk.chunkNo" class="chunk"><span>#{{ chunk.chunkNo+1 }}</span><p>{{ chunk.content }}</p></article></template></aside><div v-if="detailOpen" class="mask" @click="detailOpen=false"></div>

    <div v-if="createOpen" class="modal-wrap"><form class="modal" @submit.prevent="createKnowledgeBase"><div class="drawer-head"><div><small>新建空间</small><h2>创建知识库</h2></div><button type="button" @click="createOpen=false">×</button></div><label>知识库名称<input v-model="kbForm.name" maxlength="120" required placeholder="例如：项目规范与实验记录"></label><label>空间说明<textarea v-model="kbForm.description" maxlength="500" rows="4" placeholder="说明资料范围和主要用途"></textarea></label><button class="primary">确认创建</button></form></div>
  </div>
</template>
