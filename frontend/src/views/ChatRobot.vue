<template>
  <div class="morandi-body theme-transition h-screen w-full flex flex-col relative overflow-hidden antialiased">
    <!-- 通知提示 -->
    <transition name="notification">
      <div
        v-if="notification.show"
        class="fixed top-4 right-4 z-50 px-5 py-3 rounded-xl shadow-lg animate-fade-up morandi-notification"
        :class="[`morandi-notification--${notification.type}`]"
      >
        {{ notification.message }}
      </div>
    </transition>

    <!-- 顶部导航栏 -->
    <header class="flex items-center justify-between px-6 py-4 border-b shrink-0 morandi-surface">
      <div class="flex items-center gap-3">
        <h1 class="serif text-xl font-semibold tracking-wide text-morandi">云谕助手</h1>
        <span class="text-xs px-2 py-0.5 rounded-full morandi-badge bg-morandi-tag-blue text-white">对话测试</span>
      </div>
      <div class="flex items-center gap-3">
        <ThemeToggle :modelValue="themeMode" @update:modelValue="setTheme" />
        <button @click="goBack" class="morandi-btn morandi-btn-ghost text-sm">
          <ArrowLeft class="w-4 h-4 inline mr-1" />
          返回
        </button>
      </div>
    </header>

    <!-- 主双栏区域 -->
    <div 
      class="flex flex-1 min-h-0 overflow-hidden"
      :class="{ 'blur-sm pointer-events-none': showCreateKnowledgeForm }"
    >
      <!-- 左侧设置面板 -->
      <aside class="w-72 shrink-0 border-r flex flex-col overflow-y-auto transparent-scrollbar morandi-surface">
        <!-- 助手信息 -->
        <div class="p-5 border-b morandi-divider">
          <div class="text-center">
            <h2 class="serif text-lg font-medium mb-1 text-morandi">
              {{ currentAssistant?.name || '默认机器人' }}
            </h2>
            <p class="text-xs leading-relaxed text-morandi-secondary">
              {{ currentAssistant?.description || '这是一个可以用于简单测试对话的智能助手' }}
            </p>
          </div>
        </div>

        <!-- 人设编辑 -->
        <div class="flex-1 flex flex-col p-5">
          <div class="flex items-center justify-between mb-3">
            <h3 class="text-sm font-medium text-morandi-secondary">人设设置</h3>
            <button
              @click="savePersonality"
              :disabled="!personalityChanged"
              class="morandi-btn morandi-btn-sm"
              :class="personalityChanged ? 'morandi-btn-primary' : 'morandi-btn-ghost'"
            >
              {{ saving ? '保存中...' : '保存人设' }}
            </button>
          </div>
          <textarea
            v-model="personalityText"
            @input="onPersonalityChange"
            class="flex-1 w-full resize-none morandi-input rounded-lg p-3 text-sm leading-relaxed"
            placeholder="请输入机器人的人设描述，例如：你是一个专业的客服助手，擅长解答用户问题..."
          />
          <div class="text-xs mt-1.5 text-right text-morandi-muted">
            {{ personalityText.length }}/500 字符
          </div>
        </div>

        <!-- 知识库选择 -->
        <div class="p-5 border-t morandi-divider">
          <h3 class="text-sm font-medium mb-3 text-morandi-secondary">知识库</h3>

          <!-- 单选知识库 -->
          <div v-if="currentKnowledgeBase" class="morandi-card rounded-lg p-3 mb-3">
            <div class="flex items-center justify-between">
              <div class="flex items-center min-w-0 flex-1">
                <Database class="w-4 h-4 mr-2 shrink-0 text-morandi-accent" />
                <span class="text-sm truncate font-medium text-morandi">
                  {{ currentKnowledgeBase.name }}
                </span>
              </div>
              <span class="text-xs ml-2 shrink-0 px-2 py-0.5 rounded-full morandi-badge morandi-success-bg text-white">
                已选
              </span>
            </div>
            <div class="text-xs mt-1 ml-6 text-morandi-muted">
              {{ currentKnowledgeBase.documentCount || 0 }} 个文档 · {{ currentKnowledgeBase.chunkCount || 0 }} 切片
            </div>
          </div>

          <!-- 多选知识库 -->
          <div v-else-if="selectedKnowledgeBases.length > 0" class="morandi-card rounded-lg p-3 mb-3">
            <div class="flex items-center justify-between mb-2">
              <div class="flex items-center">
                <Database class="w-4 h-4 mr-2 text-morandi-accent" />
                <span class="text-sm font-medium text-morandi">
                  已选择 {{ selectedKnowledgeBases.length }} 个
                </span>
              </div>
              <button @click="clearMultiSelection" class="text-xs px-2 py-0.5 rounded morandi-error-bg text-morandi-error">
                清空
              </button>
            </div>
            <div class="space-y-1.5 max-h-24 overflow-y-auto transparent-scrollbar">
              <div
                v-for="kb in selectedKnowledgeBases"
                :key="kb.id"
                class="flex items-center justify-between px-2 py-1.5 rounded morandi-input-bg"
              >
                <span class="text-xs truncate text-morandi">{{ kb.name }}</span>
                <button @click.stop="removeFromSelection(kb)" class="shrink-0 ml-2 text-morandi-error">
                  <X class="w-3 h-3" />
                </button>
              </div>
            </div>
          </div>

          <!-- 空状态 -->
          <div v-else class="rounded-lg p-4 text-center border border-dashed morandi-input-bg morandi-border">
            <FolderOpen class="w-6 h-6 mx-auto mb-1.5 text-morandi-faint" />
            <p class="text-xs text-morandi-muted">暂未选择知识库</p>
          </div>

          <button @click="openKnowledgeModal" class="morandi-btn morandi-btn-primary w-full mt-3 text-sm">
            管理知识库
          </button>
        </div>
      </aside>

      <!-- 右侧聊天区域 -->
      <main class="flex-1 flex flex-col min-w-0">
        <!-- 聊天头部 -->
        <div class="flex justify-between items-center px-6 py-3 border-b shrink-0 morandi-divider morandi-surface-raised">
          <h3 class="serif text-base font-medium text-morandi">
            与 {{ currentAssistant?.name || '机器人' }} 对话
          </h3>
          <div class="flex items-center gap-3">
            <div v-if="voiceCallActive" class="flex items-center gap-2">
              <span class="relative flex h-2.5 w-2.5">
                <span class="animate-ping absolute inline-flex h-full w-full rounded-full opacity-75 bg-morandi-error"></span>
                <span class="relative inline-flex rounded-full h-2.5 w-2.5 bg-morandi-error"></span>
              </span>
              <span class="text-xs font-medium text-morandi-error">语音通话中</span>
            </div>
            <button @click="resetChat" class="morandi-btn morandi-btn-ghost morandi-btn-sm">
              重置对话
            </button>
          </div>
        </div>

        <!-- 语音识别文本 -->
        <div
          v-if="asrText && voiceCallActive"
          class="mx-6 mt-3 px-4 py-2 rounded-lg border text-sm morandi-input-bg morandi-border"
        >
          <span class="font-medium text-morandi-secondary">语音识别：</span>{{ asrText }}
        </div>

        <!-- 消息列表 -->
        <div class="flex-1 min-h-0">
          <ChatMessages
            :messages="messages"
            :auto-scroll="true"
            @scroll-state-change="handleScrollStateChange"
            class="h-full"
          />
        </div>

        <!-- 输入区域 -->
        <div class="p-5 border-t shrink-0 morandi-divider morandi-surface">
          <!-- 语音通话面板 -->
          <div v-if="voiceCallActive" class="flex flex-col items-center gap-4 py-2">
            <div class="flex items-center gap-4">
              <div class="relative">
                <Mic class="w-7 h-7 animate-pulse text-morandi-error" />
                <div class="absolute -inset-2 rounded-full border-2 animate-ping border-morandi-error opacity-40"></div>
              </div>
              <div class="flex items-center gap-1">
                <div
                  v-for="i in 5"
                  :key="i"
                  class="w-1.5 rounded-full transition-all duration-150 bg-morandi-primary"
                  :style="{ height: `${Math.max(4, audioLevel * 40 * (0.5 + Math.random() * 0.5))}px`, opacity: audioLevel > (i - 1) * 0.2 ? 1 : 0.3 }"
                />
              </div>
            </div>
            <button @click="endVoiceCall" class="morandi-btn morandi-btn-danger px-8 py-2 text-sm">
              <PhoneOff class="w-4 h-4 inline mr-1.5" />
              挂断
            </button>
          </div>

          <!-- 文字输入面板 -->
          <div v-else class="flex items-center gap-3">
            <input
              v-model="inputText"
              type="text"
              class="flex-1 morandi-input rounded-lg px-4 py-2.5 text-sm"
              placeholder="请输入您想问的问题"
              @keyup.enter="sendMessage"
              :disabled="isTyping"
            />
            <button
              @click="startVoiceCall"
              :disabled="!currentAssistant?.id"
              class="morandi-btn morandi-btn-ghost px-3 py-2.5"
              :class="{ 'opacity-40 cursor-not-allowed': !currentAssistant?.id }"
            >
              <Mic class="w-5 h-5" />
            </button>
            <button
              @click="sendMessage"
              :disabled="!inputText.trim() || isTyping"
              class="morandi-btn morandi-btn-primary"
              :class="{ 'opacity-40 cursor-not-allowed': !inputText.trim() || isTyping }"
            >
              发送
            </button>
          </div>
        </div>
      </main>
    </div>

    <!-- 知识库管理弹窗 -->
    <div
      v-if="showKnowledgeModal"
      class="fixed inset-0 z-40 flex items-center justify-center morandi-overlay"
      @click.self="closeKnowledgeModal"
    >
      <div
        class="animate-modal-in morandi-card-elevated max-h-[85vh] rounded-2xl shadow-lg flex overflow-hidden"
        :class="showFileManager ? 'w-[1200px]' : 'w-[800px]'"
      >
        <!-- 左侧知识库列表 -->
        <div
          class="transition-all duration-400 ease-in-out flex flex-col"
          :class="showFileManager ? 'w-1/2' : 'w-full'"
        >
          <div class="px-6 py-5 border-b morandi-border">
            <div class="flex items-center justify-between mb-4">
              <h2 class="serif text-lg font-semibold text-morandi">知识库管理</h2>
              <div class="flex items-center gap-3">
                <div class="flex rounded-md p-0.5 morandi-input-bg">
                  <button
                    @click="knowledgeBaseLayout = 'list'"
                    class="px-2.5 py-1 rounded text-sm transition-all"
                    :class="knowledgeBaseLayout === 'list' ? 'morandi-btn morandi-btn-primary morandi-btn-sm' : 'text-morandi-muted'"
                  >
                    <List class="w-4 h-4" />
                  </button>
                  <button
                    @click="knowledgeBaseLayout = 'grid'"
                    class="px-2.5 py-1 rounded text-sm transition-all"
                    :class="knowledgeBaseLayout === 'grid' ? 'morandi-btn morandi-btn-primary morandi-btn-sm' : 'text-morandi-muted'"
                  >
                    <LayoutGrid class="w-4 h-4" />
                  </button>
                </div>
                <button @click="closeKnowledgeModal" class="p-1 rounded hover:morandi-input-bg transition-colors text-morandi-muted">
                  <X class="w-5 h-5" />
                </button>
              </div>
            </div>
            <button @click="openCreateKnowledgeForm" class="morandi-btn morandi-btn-primary text-sm">
              <Plus class="w-4 h-4 inline mr-1.5" /> 新建知识库
            </button>
          </div>

          <div class="flex-1 min-h-0 overflow-y-auto morandi-scroll p-6">
            <div
              class="transition-all duration-300 gap-3"
              :class="knowledgeBaseLayout === 'grid' ? 'grid grid-cols-2' : 'flex flex-col'"
            >
              <div
                v-for="kb in knowledgeBases"
                :key="kb.id"
                @click="selectKnowledgeBase(kb)"
                class="morandi-card rounded-xl p-4 cursor-pointer transition-all duration-200 hover:shadow-md"
                :class="{ 'ring-1 morandi-primary-border morandi-input-bg': isKbSelected(kb.id) }"
              >
                <!-- 列表视图 -->
                <template v-if="knowledgeBaseLayout === 'list'">
                  <div class="mr-3 flex-shrink-0">
                    <div class="w-5 h-5 rounded border-2 flex items-center justify-center transition-all morandi-border" :class="{ 'morandi-primary-bg morandi-primary-border': isKbSelected(kb.id) }">
                      <Check v-if="isKbSelected(kb.id)" class="w-3 h-3 text-white" />
                    </div>
                  </div>
                  <div class="flex items-center flex-1 min-w-0">
                    <Database class="w-5 h-5 mr-2.5 flex-shrink-0 text-morandi-accent" />
                    <div class="flex-1 min-w-0">
                      <h3 class="font-medium text-sm truncate text-morandi">{{ kb.name }}</h3>
                      <p class="text-xs truncate text-morandi-muted">{{ kb.description || '暂无描述' }}</p>
                    </div>
                  </div>
                  <div class="text-center mx-3 flex-shrink-0">
                    <div class="font-bold text-sm text-morandi">{{ kb.documentCount || 0 }}</div>
                    <div class="text-xs text-morandi-faint">文档</div>
                  </div>
                  <div class="flex gap-1 flex-shrink-0">
                    <button
                      @click.stop="selectKnowledgeBaseForFileManager(kb)"
                      class="p-1.5 rounded transition-colors hover:morandi-input-bg text-morandi-primary"
                    >
                      <FolderOpen class="w-4 h-4" />
                    </button>
                    <button
                      @click.stop="deleteKnowledgeBase(kb)"
                      class="p-1.5 rounded transition-colors hover:morandi-input-bg text-morandi-error"
                    >
                      <Trash2 class="w-4 h-4" />
                    </button>
                  </div>
                </template>

                <!-- 网格视图 -->
                <template v-else>
                  <div class="flex items-start justify-between mb-2">
                    <div class="flex items-center">
                      <div class="mr-2.5 flex-shrink-0">
                        <div class="w-5 h-5 rounded border-2 flex items-center justify-center transition-all morandi-border" :class="{ 'morandi-primary-bg morandi-primary-border': isKbSelected(kb.id) }">
                          <Check v-if="isKbSelected(kb.id)" class="w-3 h-3 text-white" />
                        </div>
                      </div>
                      <Database class="w-6 h-6 mr-2 text-morandi-accent" />
                      <div>
                        <h3 class="font-medium text-sm text-morandi">{{ kb.name }}</h3>
                        <p class="text-xs mt-0.5 text-morandi-muted">{{ kb.description || '暂无描述' }}</p>
                      </div>
                    </div>
                    <div class="flex gap-1.5">
                      <button
                        @click.stop="selectKnowledgeBaseForFileManager(kb)"
                        class="p-1 rounded transition-colors hover:morandi-input-bg text-morandi-primary"
                      >
                        <FolderOpen class="w-4 h-4" />
                      </button>
                      <button
                        @click.stop="deleteKnowledgeBase(kb)"
                        class="p-1 rounded transition-colors hover:morandi-input-bg text-morandi-error"
                      >
                        <Trash2 class="w-4 h-4" />
                      </button>
                    </div>
                  </div>
                  <div class="flex justify-between text-xs pt-2 border-t morandi-divider">
                    <div class="text-center">
                      <div class="font-bold text-morandi">{{ kb.documentCount || 0 }}</div>
                      <div class="text-morandi-faint">文档</div>
                    </div>
                    <div class="text-center">
                      <div class="font-bold text-morandi">{{ kb.chunkCount || 0 }}</div>
                      <div class="text-morandi-faint">切片</div>
                    </div>
                  </div>
                </template>
              </div>
            </div>
          </div>

          <!-- 弹窗底部操作 -->
          <div class="px-6 py-4 border-t morandi-border">
            <div class="mb-3">
              <div class="flex items-center justify-between mb-2">
                <span class="text-xs font-medium text-morandi-secondary">
                  已选择 {{ selectedKnowledgeBases.length }} 个知识库
                </span>
                <button
                  v-if="selectedKnowledgeBases.length > 0"
                  @click="clearAllSelections"
                  class="text-xs transition-colors text-morandi-error"
                >
                  清空选择
                </button>
              </div>
              <div v-if="selectedKnowledgeBases.length > 0" class="flex flex-wrap gap-1.5">
                <div
                  v-for="kb in selectedKnowledgeBases"
                  :key="kb.id"
                  class="px-2.5 py-1 rounded-md text-xs flex items-center gap-1.5 morandi-input-bg morandi-border text-morandi"
                >
                  <span>{{ kb.name }}</span>
                  <button @click="removeFromSelection(kb)" class="text-morandi-muted">
                    <X class="w-3 h-3" />
                  </button>
                </div>
              </div>
              <div v-else class="text-xs text-morandi-faint">未选择任何知识库</div>
            </div>
            <div class="flex justify-end gap-2.5">
              <button @click="closeKnowledgeModal" class="morandi-btn morandi-btn-ghost text-sm">取消</button>
              <button @click="confirmSelection" class="morandi-btn morandi-btn-primary text-sm">确认选择</button>
            </div>
          </div>
        </div>

        <!-- 右侧文件管理面板 -->
        <div
          v-if="showFileManager"
          class="w-1/2 flex flex-col animate-slide-in-right border-l morandi-surface morandi-border-l"
        >
          <div class="px-5 py-4 border-b morandi-border">
            <div class="flex items-center justify-between mb-3">
              <div class="flex items-center">
                <button
                  @click="closeFileManager"
                  class="p-1 rounded mr-2.5 transition-colors hover:morandi-input-bg text-morandi-muted"
                >
                  <ChevronLeft class="w-5 h-5" />
                </button>
                <h3 class="serif text-base font-medium text-morandi">文件管理</h3>
              </div>
              <button
                @click="triggerFileUpload"
                :disabled="isUploading"
                class="morandi-btn text-sm"
                :class="{ 'morandi-btn-ghost opacity-45': isUploading }"
              >
                <Upload class="w-4 h-4 inline mr-1" />
                {{ isUploading ? '上传中...' : '上传文件' }}
              </button>
            </div>

            <!-- 上传进度 -->
            <div v-if="isUploading && uploadProgress > 0" class="mb-3">
              <div class="flex justify-between text-xs mb-1.5 text-morandi-secondary">
                <span>上传进度</span>
                <span>{{ Math.round(uploadProgress) }}%</span>
              </div>
              <div class="w-full rounded-full h-1.5 morandi-input-bg">
                <div class="h-1.5 rounded-full transition-all duration-300 morandi-accent-bg" :style="{ width: uploadProgress + '%' }"></div>
              </div>
            </div>

            <div v-if="currentFileManagerKB" class="rounded-md p-2.5 morandi-input-bg">
              <div class="flex items-center">
                <Database class="w-4 h-4 mr-2 text-morandi-accent" />
                <span class="text-sm font-medium text-morandi">{{ currentFileManagerKB.name }}</span>
              </div>
            </div>
          </div>

          <div class="flex-1 overflow-y-auto morandi-scroll p-5">
            <div v-if="currentFiles.length === 0" class="text-center py-12">
              <FileText class="w-12 h-12 mx-auto mb-3 text-morandi-faint" />
              <p class="text-sm text-morandi-muted">暂无文件</p>
              <p class="text-xs mt-1 text-morandi-faint">点击上传按钮添加文档</p>
            </div>
            <div v-else class="space-y-2.5">
              <div
                v-for="file in currentFiles"
                :key="file.id"
                class="morandi-card rounded-lg p-3.5 transition-all duration-200 hover:shadow-sm"
              >
                <div class="flex items-center justify-between">
                  <div class="flex items-center flex-1 min-w-0">
                    <FileText class="w-6 h-6 mr-2.5 flex-shrink-0 text-morandi-primary" />
                    <div class="flex-1 min-w-0">
                      <h4 class="text-sm font-medium truncate text-morandi">{{ file.name }}</h4>
                      <div class="flex items-center text-xs mt-0.5 text-morandi-muted">
                        <span>{{ formatFileSize(file.size) }}</span>
                        <span class="mx-1.5">&middot;</span>
                        <span>{{ file.run || '未知' }}</span>
                      </div>
                    </div>
                  </div>
                  <button
                    @click="deleteFile(file)"
                    class="ml-3 p-1.5 rounded transition-colors hover:morandi-input-bg shrink-0 text-morandi-error"
                  >
                    <Trash2 class="w-4 h-4" />
                  </button>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 创建知识库弹窗 -->
    <div
      v-if="showCreateKnowledgeForm"
      class="fixed inset-0 z-50 flex items-center justify-center morandi-overlay"
      @click.self="showCreateKnowledgeForm = false"
    >
      <div class="animate-modal-in morandi-card-elevated w-[420px] p-7 rounded-2xl shadow-lg">
        <h3 class="serif text-lg font-semibold mb-5 text-morandi">创建知识库</h3>
        <div class="space-y-4">
          <div>
            <label class="block text-sm font-medium mb-1.5 text-morandi-secondary">
              <span class="text-morandi-error">*</span> 知识库名称
            </label>
            <input
              v-model="newKnowledgeBase.name"
              type="text"
              placeholder="请输入知识库名称"
              maxlength="20"
              class="w-full px-3.5 py-2.5 rounded-lg morandi-input text-sm"
            />
            <div class="text-xs mt-1 text-right text-morandi-faint">
              {{ newKnowledgeBase.name.length }}/20
            </div>
          </div>
          <div>
            <label class="block text-sm font-medium mb-1.5 text-morandi-secondary">知识库描述</label>
            <textarea
              v-model="newKnowledgeBase.description"
              placeholder="请输入知识库描述"
              maxlength="200"
              rows="3"
              class="w-full px-3.5 py-2.5 rounded-lg morandi-input text-sm resize-none"
            />
            <div class="text-xs mt-1 text-right text-morandi-faint">
              {{ newKnowledgeBase.description.length }}/200
            </div>
          </div>
        </div>
        <div class="flex justify-end gap-2.5 mt-6">
          <button @click="showCreateKnowledgeForm = false" class="morandi-btn morandi-btn-ghost text-sm">取消</button>
          <button
            @click="createKnowledgeBase"
            :disabled="!newKnowledgeBase.name.trim()"
            class="morandi-btn morandi-btn-primary text-sm"
            :class="{ 'opacity-40 cursor-not-allowed': !newKnowledgeBase.name.trim() }"
          >
            创建
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import {
  Database, FolderOpen, Check, X, Plus, List, LayoutGrid,
  Trash2, ChevronLeft, Upload, FileText, Mic, PhoneOff, ArrowLeft
} from 'lucide-vue-next'
import ChatMessages from '../components/ChatMessages.vue'
import ThemeToggle from '../components/ThemeToggle.vue'
import { useTheme } from '../composables/useTheme'
import { useWebSocket } from '../utils/websocket'
import { useWebRTC } from '../composables/useWebRTC'
import { fetchAssistant, fetchKnowledgeConfig } from '../api/assistant'
import type { Assistant, DisplayMessage, KnowledgeBase, RAGFlowConfig, AsrDeltaData } from '../types'

// ==================== 全局依赖 & 公共状态 ====================
const { themeMode, setTheme } = useTheme()
const router = useRouter()
const route = useRoute()
const webrtc = useWebRTC()

// WebSocket 实例
let ws: ReturnType<typeof useWebSocket> | null = null
let voiceWs: ReturnType<typeof useWebSocket> | null = null

// 助手信息 & RAG 配置
const currentAssistant = ref<Assistant | null>(null)
const ragflowConfig = ref<RAGFlowConfig>({ endpoint: '', apiKey: '' })

// 流式输出标记
const isFirstOfStream = ref(true)

// 人设相关
const personalityText = ref('')
const originalPersonality = ref('')
const saving = ref(false)
const personalityChanged = computed(() => {
  return personalityText.value !== originalPersonality.value && personalityText.value.trim() !== ''
})

// 聊天相关
const inputText = ref('')
const isTyping = ref(false)
const messages = ref<DisplayMessage[]>([])

// 全局通知
const notification = ref({
  show: false,
  message: '',
  type: 'info' as 'success' | 'error' | 'warning' | 'info',
})

// 知识库状态
const knowledgeBases = ref<KnowledgeBase[]>([])
const showKnowledgeModal = ref(false)
const showCreateKnowledgeForm = ref(false)
const showFileManager = ref(false)
const currentFileManagerKB = ref<KnowledgeBase | null>(null)
const currentFiles = ref<any[]>([])
const knowledgeBaseLayout = ref<'list' | 'grid'>('grid')
const selectedKnowledgeBases = ref<KnowledgeBase[]>([])
const currentKnowledgeBase = ref<KnowledgeBase | null>(null)
const isUploading = ref(false)
const uploadProgress = ref(0)

// 新建知识库表单
const newKnowledgeBase = ref({
  name: '',
  description: '',
})

// 语音通话
const audioLevel = webrtc.audioLevel
const voiceCallActive = ref(false)
const asrText = ref('')

// ==================== 公共工具方法 ====================
/** 消息通知 */
const showNotification = (message: string, type: 'success' | 'error' | 'warning' | 'info' = 'info') => {
  notification.value = { show: true, message, type }
  setTimeout(() => {
    notification.value.show = false
  }, 3000)
}

/** 格式化文件大小 */
const formatFileSize = (bytes: number): string => {
  if (bytes === 0) return '0 Bytes'
  const k = 1024
  const sizes = ['Bytes', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return `${parseFloat((bytes / Math.pow(k, i)).toFixed(2))} ${sizes[i]}`
}

/** 判断知识库是否被选中 */
const isKbSelected = (id: string) => {
  return selectedKnowledgeBases.value.some(item => item.id === id)
}

/** 人设输入长度截断 */
const onPersonalityChange = () => {
  if (personalityText.value.length > 500) {
    personalityText.value = personalityText.value.substring(0, 500)
  }
}

// ==================== 人设操作 ====================
const savePersonality = async () => {
  if (!personalityChanged.value || !currentAssistant.value || !ws) return

  saving.value = true
  try {
    ws.send({ type: 'prompt', content: personalityText.value })
    showNotification('人设保存成功！', 'success')
    originalPersonality.value = personalityText.value
  } catch (error) {
    console.error('保存人设失败:', error)
    showNotification(`保存失败：${(error as Error).message}`, 'error')
  } finally {
    saving.value = false
  }
}

// ==================== 聊天操作 ====================
const resetChat = () => {
  const name = currentAssistant.value?.name || '智能助手'
  messages.value = [{ role: 'assistant', text: `您好，我是${name}，专门解答相关问题。` }]
  ws?.send({ type: 'resetMessage' })
}

const sendMessage = () => {
  const text = inputText.value.trim()
  if (!text || isTyping.value || !ws) return

  messages.value.push({ role: 'user', text })
  ws.send({ type: 'chat', content: text })
  inputText.value = ''
  isTyping.value = true
}

const handleScrollStateChange = () => {}

// ==================== 页面返回 ====================
const goBack = () => {
  resetChat()
  ws?.send({ type: 'close' })
  ws?.close()
  voiceWs?.send({ type: 'hangup' })
  voiceWs?.close()
  webrtc.hangup()
  voiceCallActive.value = false
  router.back()
}

// ==================== 文本聊天 WebSocket 连接 ====================
const connectWebSocket = () => {
  const assistantId = currentAssistant.value?.id
  if (!assistantId) return

  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  const host = window.location.host
  const wsUrl = `${protocol}//${host}/ws/${assistantId}`

  ws = useWebSocket(wsUrl, {
    onOpen: () => {
      setTimeout(() => {
        if (currentKnowledgeBase.value) {
          ws?.send({ type: 'selectedKbIds', ids: [currentKnowledgeBase.value.id] })
        } else if (selectedKnowledgeBases.value.length > 0) {
          const ids = selectedKnowledgeBases.value.map(kb => kb.id)
          ws?.send({ type: 'selectedKbIds', ids })
        }
      }, 100)
    },
    onMessage: (event) => {
      try {
        const data = JSON.parse(event.data)
        if (data.type === 'assistant_message') {
          const answer = data.data
          if (answer.streamEnd) {
            isTyping.value = false
            isFirstOfStream.value = true
          } else {
            if (isFirstOfStream.value) {
              messages.value.push({ role: 'assistant', text: answer.segment, isStreaming: true })
              isFirstOfStream.value = false
            } else {
              const lastMsg = messages.value.at(-1)
              if (lastMsg && lastMsg.role === 'assistant') {
                lastMsg.text += answer.segment
              }
            }
          }
        } else if (data.type === 'query_end') {
          const queryData = data.data
          isTyping.value = false
          isFirstOfStream.value = true
          const lastMsg = messages.value.at(-1)
          if (lastMsg && lastMsg.role === 'assistant' && lastMsg.isStreaming) {
            lastMsg.text = queryData.message || lastMsg.text
            lastMsg.isStreaming = false
            lastMsg.costTime = queryData.costTime
            lastMsg.knowledgebase = queryData.knowledgebase
          }
        }
      } catch (e) {
        console.error('消息解析失败', e)
        isTyping.value = false
      }
    },
    onClose: () => console.log('WebSocket已关闭'),
    onError: (err) => console.error('WebSocket错误', err),
  })
}

// ==================== 语音通话 WebRTC + WebSocket ====================
const startVoiceCall = async () => {
  if (!currentAssistant.value?.id) return

  try {
    asrText.value = ''
    const offerSDP = await webrtc.createOffer()
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const host = window.location.host
    const wsUrl = `${protocol}//${host}/ws-voice/${currentAssistant.value.id}`

    voiceWs = useWebSocket(wsUrl, {
      onOpen: () => {
        voiceWs?.send({ type: 'offer', sdp: offerSDP })
      },
      onMessage: async (event) => {
        try {
          const data = JSON.parse(event.data)
          if (data.type === 'webrtc_answer') {
            await webrtc.handleAnswer(data.data)
            voiceCallActive.value = true
            voiceWs?.send({ type: 'webrtc_connected' })
            showNotification('语音通话已连接', 'success')
          } else if (data.type === 'asr_delta') {
            const asrData = data.data as AsrDeltaData
            asrText.value = asrData.text
          } else if (data.type === 'assistant_message') {
            const answer = data.data
            if (answer.streamEnd) {
              isTyping.value = false
              isFirstOfStream.value = true
            } else {
              if (isFirstOfStream.value) {
                messages.value.push({ role: 'assistant', text: answer.segment, isStreaming: true })
                isFirstOfStream.value = false
              } else {
                const lastMsg = messages.value.at(-1)
                if (lastMsg && lastMsg.role === 'assistant') {
                  lastMsg.text += answer.segment
                }
              }
            }
          } else if (data.type === 'query_end') {
            const queryData = data.data
            isTyping.value = false
            isFirstOfStream.value = true
            const lastMsg = messages.value.at(-1)
            if (lastMsg && lastMsg.role === 'assistant' && lastMsg.isStreaming) {
              lastMsg.text = queryData.message || lastMsg.text
              lastMsg.isStreaming = false
              lastMsg.costTime = queryData.costTime
              lastMsg.knowledgebase = queryData.knowledgebase
            }
            asrText.value = ''
          } else if (data.type === 'hangup') {
            endVoiceCall()
            showNotification('对方已挂断', 'info')
          }
        } catch (e) {
          console.error('语音消息解析失败', e)
        }
      },
      onClose: () => {
        if (voiceCallActive.value) endVoiceCall()
      },
      onError: (err) => {
        console.error('语音WebSocket错误', err)
        endVoiceCall()
        showNotification('语音连接失败', 'error')
      },
    })
  } catch (error) {
    console.error('启动语音通话失败:', error)
    showNotification('启动语音通话失败，请检查麦克风权限', 'error')
    webrtc.hangup()
  }
}

const endVoiceCall = () => {
  voiceWs?.send({ type: 'hangup' })
  voiceWs?.close()
  voiceWs = null
  webrtc.hangup()
  voiceCallActive.value = false
  asrText.value = ''
}

// ==================== 知识库弹窗控制 ====================
const openKnowledgeModal = () => {
  showKnowledgeModal.value = true
  if (selectedKnowledgeBases.value.length === 0 && currentKnowledgeBase.value) {
    selectedKnowledgeBases.value = [currentKnowledgeBase.value]
  }
}

const closeKnowledgeModal = () => {
  showKnowledgeModal.value = false
  showCreateKnowledgeForm.value = false
  showFileManager.value = false
  currentFileManagerKB.value = null
  currentFiles.value = []
}

const selectKnowledgeBase = (kb: KnowledgeBase) => {
  const idx = selectedKnowledgeBases.value.findIndex(item => item.id === kb.id)
  if (idx > -1) {
    selectedKnowledgeBases.value.splice(idx, 1)
  } else {
    selectedKnowledgeBases.value.push(kb)
  }
}

const selectKnowledgeBaseForFileManager = (kb: KnowledgeBase) => {
  currentFileManagerKB.value = kb
  showFileManager.value = true
  loadKnowledgeBaseFiles(kb.id)
}

const closeFileManager = () => {
  showFileManager.value = false
  currentFileManagerKB.value = null
  currentFiles.value = []
}

const confirmSelection = () => {
  const list = selectedKnowledgeBases.value
  if (list.length === 1) {
    currentKnowledgeBase.value = list[0]
    selectedKnowledgeBases.value = []
    showNotification(`已选择知识库：${currentKnowledgeBase.value.name}`, 'success')
    ws?.send({ type: 'selectedKbIds', ids: [currentKnowledgeBase.value.id] })
  } else if (list.length > 1) {
    const names = list.map(kb => kb.name).join('、')
    showNotification(`已选择 ${list.length} 个知识库：${names}`, 'success')
    currentKnowledgeBase.value = null
    const ids = list.map(kb => kb.id)
    ws?.send({ type: 'selectedKbIds', ids })
  } else {
    currentKnowledgeBase.value = null
    showNotification('已清空知识库选择', 'info')
  }

  saveKnowledgeBaseSelection()
  closeKnowledgeModal()
}

const clearAllSelections = () => {
  selectedKnowledgeBases.value = []
  showNotification('已清空所有选择', 'info')
}

const clearMultiSelection = () => {
  selectedKnowledgeBases.value = []
  currentKnowledgeBase.value = null
  showNotification('已清空知识库选择', 'info')
  const id = currentAssistant.value?.id
  if (id) localStorage.removeItem(`knowledgeBaseSelection_${id}`)
}

const removeFromSelection = (kb: KnowledgeBase) => {
  const idx = selectedKnowledgeBases.value.findIndex(item => item.id === kb.id)
  if (idx > -1) {
    selectedKnowledgeBases.value.splice(idx, 1)
    showNotification(`已移除：${kb.name}`, 'info')
  }
}

// ==================== 知识库 CRUD ====================
const openCreateKnowledgeForm = () => {
  showKnowledgeModal.value = false
  showCreateKnowledgeForm.value = true
}

const createKnowledgeBase = async () => {
  const name = newKnowledgeBase.value.name.trim()
  if (!name) {
    showNotification('请输入知识库名称', 'warning')
    return
  }

  try {
    const res = await fetch(`${ragflowConfig.value.endpoint}/api/v1/datasets`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': ragflowConfig.value.apiKey,
      },
      body: JSON.stringify({
        name,
        description: newKnowledgeBase.value.description.trim(),
        embedding_model: 'text-embedding-v3@Tongyi-Qianwen',
        chunk_method: 'naive',
        parser_config: {
          layout_recognize: 'true',
          delimiter: '\n',
          html4excel: false,
          filename_embd_weight: 0.1,
          raptor: { use_raptor: false },
          graphrag: { use_graphrag: false },
        },
      }),
    })

    if (!res.ok) throw new Error(`创建数据集失败: ${res.status}`)
    const result = await res.json()

    if (result.code === 0) {
      showNotification('知识库创建成功', 'success')
      await loadKnowledgeBases()
      newKnowledgeBase.value = { name: '', description: '' }
      showCreateKnowledgeForm.value = false
    } else {
      throw new Error(result.message || '创建知识库失败')
    }
  } catch (error) {
    console.error('创建知识库失败:', error)
    showNotification(`创建知识库失败: ${(error as Error).message}`, 'error')
  }
}

const deleteKnowledgeBase = async (kb: KnowledgeBase) => {
  if (!confirm(`确定要删除知识库"${kb.name}"吗？此操作不可撤销。`)) return

  try {
    const res = await fetch(`${ragflowConfig.value.endpoint}/api/v1/datasets`, {
      method: 'DELETE',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': ragflowConfig.value.apiKey,
      },
      body: JSON.stringify({ ids: [kb.id] }),
    })

    if (!res.ok) throw new Error(`删除数据集失败: ${res.status}`)
    const result = await res.json()

    if (result.code === 0) {
      showNotification('知识库删除成功', 'success')
      await loadKnowledgeBases()
      if (currentKnowledgeBase.value?.id === kb.id) {
        currentKnowledgeBase.value = null
      }
      selectedKnowledgeBases.value = selectedKnowledgeBases.value.filter(item => item.id !== kb.id)
    }
  } catch (error) {
    console.error('删除知识库失败:', error)
    showNotification(`删除知识库失败: ${(error as Error).message}`, 'error')
  }
}

// 加载知识库下的文档列表
const loadKnowledgeBaseFiles = async (knowledgeBaseId: string) => {
  if (!knowledgeBaseId || !ragflowConfig.value.endpoint) {
    currentFiles.value = []
    return
  }

  try {
    const res = await fetch(
      `${ragflowConfig.value.endpoint}/api/v1/datasets/${knowledgeBaseId}/documents?page=1&page_size=100`,
      {
        method: 'GET',
        headers: { 'Authorization': ragflowConfig.value.apiKey },
      }
    )

    if (!res.ok) throw new Error(`获取文档列表失败: ${res.status}`)
    const result = await res.json()

    if (result.code === 0 && result.data?.docs && Array.isArray(result.data.docs)) {
      currentFiles.value = result.data.docs.map((doc: any) => ({
        id: doc.id,
        name: doc.name,
        size: doc.size || 0,
        type: doc.type || doc.name?.split('.').pop()?.toUpperCase() || 'Unknown',
        run: doc.run,
        chunkCount: doc.chunk_count || 0,
        progress: doc.progress || 0,
      }))
    } else {
      currentFiles.value = []
    }
  } catch (error) {
    console.error('获取文档列表失败:', error)
    currentFiles.value = []
  }
}

// 触发文件上传
const triggerFileUpload = () => {
  if (!currentFileManagerKB.value) return
  const input = document.createElement('input')
  input.type = 'file'
  input.multiple = true
  input.accept = '.txt,.pdf,.doc,.docx,.md,.mdx,.csv,.xlsx,.xls'
  input.onchange = (event) => {
    const files = Array.from((event.target as HTMLInputElement).files || [])
    processFilesForFileManager(files)
  }
  input.click()
}

// 批量处理上传文件
const processFilesForFileManager = async (files: File[]) => {
  if (!files.length || !currentFileManagerKB.value) return

  isUploading.value = true
  uploadProgress.value = 0
  const uploadedDocs: string[] = []
  const totalFiles = files.length

  try {
    for (let i = 0; i < files.length; i++) {
      const file = files[i]
      if (file.size > 50 * 1024 * 1024) {
        showNotification(`文件 ${file.name} 超过50MB限制`, 'error')
        continue
      }

      try {
        const formData = new FormData()
        formData.append('file', file)
        const res = await fetch(
          `${ragflowConfig.value.endpoint}/api/v1/datasets/${currentFileManagerKB.value.id}/documents`,
          {
            method: 'POST',
            headers: { 'Authorization': ragflowConfig.value.apiKey },
            body: formData,
          }
        )

        if (!res.ok) throw new Error(`上传失败: ${res.status}`)
        const result = await res.json()

        if (result.code === 0) {
          result.data.forEach((item: { id: string }) => uploadedDocs.push(item.id))
          showNotification(`文件 ${file.name} 上传成功`, 'success')
        } else {
          throw new Error(result.message || '上传失败')
        }
        uploadProgress.value = Math.round(((i + 1) / totalFiles) * 100)
      } catch (error) {
        console.error(`上传文件 ${file.name} 失败:`, error)
        showNotification(`上传文件 ${file.name} 失败: ${(error as Error).message}`, 'error')
      }
    }

    if (uploadedDocs.length > 0) {
      showNotification(`成功上传 ${uploadedDocs.length} 个文件`, 'success')
      await loadKnowledgeBaseFiles(currentFileManagerKB.value.id)
      try {
        await parseDocuments(currentFileManagerKB.value.id, uploadedDocs)
      } catch (parseError) {
        console.warn('文档解析失败，但文件已成功上传:', parseError)
        showNotification('文件上传成功，但自动解析失败，请手动解析', 'warning')
      }
    }
  } catch (error) {
    console.error('批量上传失败:', error)
    showNotification(`批量上传失败: ${(error as Error).message}`, 'error')
  } finally {
    isUploading.value = false
    uploadProgress.value = 0
  }
}

// 解析文档切片
const parseDocuments = async (datasetId: string, documentIds: string[]) => {
  const validIds = documentIds.filter(id => id && typeof id === 'string')
  if (validIds.length === 0) return

  const res = await fetch(`${ragflowConfig.value.endpoint}/api/v1/datasets/${datasetId}/chunks`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': ragflowConfig.value.apiKey,
    },
    body: JSON.stringify({ document_ids: validIds }),
  })

  if (!res.ok) throw new Error(`解析文档失败: ${res.status}`)
  const result = await res.json()
  if (result.code === 0) {
    showNotification('文档解析已开始，请稍后查看解析进度', 'success')
  } else {
    throw new Error(result.message || '解析文档失败')
  }
}

// 删除文件
const deleteFile = async (file: any) => {
  if (!currentFileManagerKB.value) return
  if (!confirm(`确定要删除文件"${file.name}"吗？此操作不可撤销。`)) return

  try {
    const res = await fetch(
      `${ragflowConfig.value.endpoint}/api/v1/datasets/${currentFileManagerKB.value.id}/documents`,
      {
        method: 'DELETE',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': ragflowConfig.value.apiKey,
        },
        body: JSON.stringify({ ids: [file.id] }),
      }
    )

    if (!res.ok) throw new Error(`删除文档失败: ${res.status}`)
    const result = await res.json()

    if (result.code === 0) {
      showNotification('文件删除成功', 'success')
      await loadKnowledgeBaseFiles(currentFileManagerKB.value.id)
    }
  } catch (error) {
    console.error('删除文件失败:', error)
    showNotification(`删除文件失败: ${(error as Error).message}`, 'error')
  }
}

// ==================== 本地存储 - 知识库选择状态持久化 ====================
const saveKnowledgeBaseSelection = () => {
  if (!currentAssistant.value?.id) return
  const selectionData = {
    currentKnowledgeBase: currentKnowledgeBase.value,
    selectedKnowledgeBases: selectedKnowledgeBases.value,
    timestamp: Date.now(),
  }
  localStorage.setItem(`knowledgeBaseSelection_${currentAssistant.value.id}`, JSON.stringify(selectionData))
}

// 恢复知识库选择
const restoreKnowledgeBaseSelection = () => {
  if (!currentAssistant.value?.id) return
  try {
    const saved = localStorage.getItem(`knowledgeBaseSelection_${currentAssistant.value.id}`)
    if (!saved) return

    const selectionData = JSON.parse(saved)
    // 恢复单选/多选状态
    if (selectionData.currentKnowledgeBase) {
      currentKnowledgeBase.value = selectionData.currentKnowledgeBase
    }
    if (Array.isArray(selectionData.selectedKnowledgeBases)) {
      selectedKnowledgeBases.value = selectionData.selectedKnowledgeBases
    }
  } catch (e) {
    console.warn('恢复知识库本地存储失败', e)
  }
}

// 加载全量知识库列表
const loadKnowledgeBases = async () => {
  if (!ragflowConfig.value.endpoint || !ragflowConfig.value.apiKey) return
  try {
    const res = await fetch(
      `${ragflowConfig.value.endpoint}/api/v1/datasets?page=1&page_size=1000`,
      {
        method: 'GET',
        headers: { Authorization: ragflowConfig.value.apiKey }
      }
    )
    if (!res.ok) throw new Error('请求知识库列表失败')
    const result = await res.json()
    if (result.code === 0 && Array.isArray(result.data?.datasets)) {
      knowledgeBases.value = result.data.datasets
    }
  } catch (err) {
    console.error('加载知识库列表失败：', err)
    showNotification('加载知识库列表失败', 'error')
  }
}

// 初始化页面数据
const initPageData = async () => {
  const assistantId = route.query.assistantId as string
  if (!assistantId) {
    showNotification('助手ID不存在，返回上一页', 'warning')
    router.back()
    return
  }

  try {
    // 1. 获取助手基础信息
    const assistantRes = await fetchAssistant(assistantId)
    currentAssistant.value = assistantRes
    // 初始化人设
    personalityText.value = assistantRes.personality || ''
    originalPersonality.value = personalityText.value

    // 2. 获取RAG配置
    const ragConfig = await fetchKnowledgeConfig()
    ragflowConfig.value = ragConfig

    // 3. 加载知识库列表
    await loadKnowledgeBases()

    // 4. 恢复本地存储的知识库选择
    restoreKnowledgeBaseSelection()

    // 5. 建立聊天WebSocket连接
    connectWebSocket()

    // 6. 初始化默认欢迎消息
    const name = currentAssistant.value?.name || '智能助手'
    messages.value = [{ role: 'assistant', text: `您好，我是${name}，请问有什么可以帮您？` }]
  } catch (error) {
    console.error('页面初始化失败：', error)
    showNotification('页面数据加载失败', 'error')
  }
}

// ==================== 生命周期 ====================
onMounted(() => {
  initPageData()
})

// 页面销毁/离开前 关闭所有连接、释放资源
onBeforeUnmount(() => {
  // 关闭文本聊天 WS
  if (ws) {
    ws.send({ type: 'close' })
    ws.close()
    ws = null
  }
  // 关闭语音 WS
  if (voiceWs) {
    voiceWs.send({ type: 'hangup' })
    voiceWs.close()
    voiceWs = null
  }
  // 挂断WebRTC语音
  webrtc.hangup()
  voiceCallActive.value = false
})
</script>

<style scoped>
/* ========== 全局莫兰迪主题样式 + 动画 + 滚动条 + 过渡 ========== */
:root {
  --morandi-primary: #8c8178;
  --morandi-accent: #a89c91;
  --morandi-secondary: #99928b;
  --morandi-muted: #b8b0a8;
  --morandi-faint: #d1c9bf;
  --morandi-error: #c77c7c;
  --morandi-success: #89a88e;
  --morandi-tag-blue: #8899aa;

  --morandi-bg: #f7f5f2;
  --morandi-surface: #ffffff;
  --morandi-surface-raised: #faf8f5;
  --morandi-input-bg: #f3f0eb;
  --morandi-overlay-bg: rgba(0, 0, 0, 0.45);

  --morandi-border: #e8e2db;
  --morandi-primary-border: var(--morandi-primary);
  --morandi-error-bg: #f8e9e9;
  --morandi-success-bg: #edf4ee;
  --morandi-accent-bg: var(--morandi-accent);
}

/* 布局基础 */
.h-screen {
  height: 100vh;
}
.w-full {
  width: 100%;
}
.antialiased {
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
}
.theme-transition * {
  transition: background-color 0.3s ease, color 0.3s ease, border-color 0.3s ease;
}

/* 滚动条样式 */
.transparent-scrollbar::-webkit-scrollbar,
.morandi-scroll::-webkit-scrollbar {
  width: 6px;
  height: 6px;
}
.transparent-scrollbar::-webkit-scrollbar-thumb,
.morandi-scroll::-webkit-scrollbar-thumb {
  background-color: var(--morandi-faint);
  border-radius: 3px;
}
.transparent-scrollbar::-webkit-scrollbar-track,
.morandi-scroll::-webkit-scrollbar-track {
  background: transparent;
}

/* 文本、字体 */
.serif {
  font-family: "Georgia", "SimSun", serif;
}
.text-morandi {
  color: var(--morandi-primary);
}
.text-morandi-secondary {
  color: var(--morandi-secondary);
}
.text-morandi-muted {
  color: var(--morandi-muted);
}
.text-morandi-faint {
  color: var(--morandi-faint);
}
.text-morandi-accent {
  color: var(--morandi-accent);
}
.text-morandi-error {
  color: var(--morandi-error);
}

/* 背景 */
.morandi-body {
  background-color: var(--morandi-bg);
}
.morandi-surface {
  background-color: var(--morandi-surface);
}
.morandi-surface-raised {
  background-color: var(--morandi-surface-raised);
}
.morandi-input-bg {
  background-color: var(--morandi-input-bg);
}
.morandi-overlay {
  background-color: var(--morandi-overlay-bg);
}
.morandi-error-bg {
  background-color: var(--morandi-error-bg);
}
.morandi-success-bg {
  background-color: var(--morandi-success-bg);
}
.morandi-accent-bg {
  background-color: var(--morandi-accent-bg);
}

/* 边框 & 分割线 */
.morandi-border {
  border-color: var(--morandi-border);
}
.morandi-divider {
  border-color: var(--morandi-border);
}
.morandi-border-l {
  border-left-color: var(--morandi-border);
}
.morandi-primary-border {
  border-color: var(--morandi-primary-border);
}

/* 卡片 */
.morandi-card {
  background: var(--morandi-surface);
  border: 1px solid var(--morandi-border);
}
.morandi-card-elevated {
  background: var(--morandi-surface);
  box-shadow: 0 8px 30px rgba(0, 0, 0, 0.08);
  border: 1px solid var(--morandi-border);
}

/* 输入框 */
.morandi-input {
  border: 1px solid var(--morandi-border);
  background-color: var(--morandi-input-bg);
  color: var(--morandi-primary);
  outline: none;
}
.morandi-input:focus {
  border-color: var(--morandi-accent);
}

/* 按钮基础 */
.morandi-btn {
  border-radius: 8px;
  border: none;
  cursor: pointer;
  transition: all 0.2s ease;
}
.morandi-btn-sm {
  padding: 4px 10px;
  font-size: 12px;
}
.morandi-btn-primary {
  background-color: var(--morandi-primary);
  color: #fff;
}
.morandi-btn-primary:hover {
  background-color: #7c7269;
}
.morandi-btn-ghost {
  background: transparent;
  color: var(--morandi-secondary);
}
.morandi-btn-ghost:hover {
  background-color: var(--morandi-input-bg);
}
.morandi-btn-danger {
  background-color: var(--morandi-error);
  color: #fff;
}
.morandi-btn-danger:hover {
  background-color: #b56c6c;
}

/* 标签角标 */
.morandi-badge {
  font-size: 11px;
}
.morandi-tag-blue {
  background-color: var(--morandi-tag-blue);
}

/* 通知动画 */
.notification-enter-active {
  animation: fadeUp 0.3s ease;
}
.notification-leave-active {
  animation: fadeDown 0.3s ease;
}

/* 弹窗动画 */
.animate-modal-in {
  animation: modalIn 0.3s ease;
}
.animate-slide-in-right {
  animation: slideRight 0.3s ease;
}
.animate-fade-up {
  animation: fadeUp 0.3s ease;
}

/* 关键帧动画 */
@keyframes fadeUp {
  from {
    opacity: 0;
    transform: translateY(10px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
@keyframes fadeDown {
  from {
    opacity: 1;
    transform: translateY(0);
  }
  to {
    opacity: 0;
    transform: translateY(10px);
  }
}
@keyframes modalIn {
  from {
    opacity: 0;
    transform: scale(0.96);
  }
  to {
    opacity: 1;
    transform: scale(1);
  }
}
@keyframes slideRight {
  from {
    opacity: 0;
    transform: translateX(20px);
  }
  to {
    opacity: 1;
    transform: translateX(0);
  }
}
</style>