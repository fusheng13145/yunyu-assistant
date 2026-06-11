<template>
  <div class="morandi-body theme-transition h-screen w-full flex flex-col relative overflow-hidden antialiased" style="background: var(--morandi-bg-base)">
    <!-- 通知 -->
    <transition name="notification">
      <div v-if="notification.show"
        class="fixed top-4 right-4 z-50 px-5 py-3 rounded-xl shadow-lg animate-fade-up"
        :style="{
          background: notification.type === 'success' ? 'var(--morandi-success-bg)' :
            notification.type === 'error' ? 'var(--morandi-error-bg)' :
              notification.type === 'warning' ? 'var(--morandi-warning-bg)' : 'var(--morandi-primary)',
          color: (notification.type === 'success' || notification.type === 'error' || notification.type === 'warning') ? '#fff' : 'var(--morandi-text-on-primary)',
          border: `1px solid ${notification.type === 'success' ? 'rgba(34,139,94,0.3)' : notification.type === 'error' ? 'rgba(180,80,80,0.3)' : notification.type === 'warning' ? 'rgba(180,140,60,0.3)' : 'var(--morandi-primary)'}`
        }">
        {{ notification.message }}
      </div>
    </transition>

    <!-- 顶部工具栏 -->
    <header class="flex items-center justify-between px-6 py-4 border-b shrink-0" style="border-color: var(--morandi-border); background: var(--morandi-surface)">
      <div class="flex items-center gap-3">
        <h1 class="serif text-xl font-semibold tracking-wide" style="color: var(--morandi-text)">云谕助手</h1>
        <span class="text-xs px-2 py-0.5 rounded-full morandi-badge" style="background: var(--morandi-tag-blue); color: #fff">对话测试</span>
      </div>
      <div class="flex items-center gap-3">
        <ThemeToggle :modelValue="themeMode" @update:modelValue="(v: any) => setTheme(v)" />
        <button @click="goBack" class="morandi-btn morandi-btn-ghost text-sm">
          <ArrowLeft class="w-4 h-4 inline mr-1" />
          返回
        </button>
      </div>
    </header>

    <!-- 主内容区：双栏布局 -->
    <div class="flex flex-1 min-h-0 overflow-hidden" :class="{ 'blur-sm pointer-events-none': showCreateKnowledgeForm }">
      <!-- 左侧：助手设置面板 (w-72) -->
      <aside class="w-72 shrink-0 border-r flex flex-col overflow-y-auto transparent-scrollbar" style="border-color: var(--morandi-border); background: var(--morandi-surface)">
        <!-- 助手信息卡片 -->
        <div class="p-5 border-b" style="border-color: var(--morandi-divider)">
          <div class="text-center">
            <h2 class="serif text-lg font-medium mb-1" style="color: var(--morandi-text)">{{ currentAssistant?.name || '默认机器人' }}</h2>
            <p class="text-xs leading-relaxed" style="color: var(--morandi-text-secondary)">{{ currentAssistant?.description || '这是一个可以用于简单测试对话的智能助手' }}</p>
          </div>
        </div>

        <!-- 人设编辑区 -->
        <div class="flex-1 flex flex-col p-5">
          <div class="flex items-center justify-between mb-3">
            <h3 class="text-sm font-medium" style="color: var(--morandi-text-secondary)">人设设置</h3>
            <button @click="savePersonality" :disabled="!personalityChanged"
              :class="['morandi-btn', personalityChanged ? 'morandi-btn-primary' : 'morandi-btn-ghost', 'morandi-btn-sm']">
              {{ saving ? '保存中...' : '保存人设' }}
            </button>
          </div>
          <textarea v-model="personalityText" @input="onPersonalityChange"
            class="flex-1 w-full resize-none morandi-input rounded-lg p-3 text-sm leading-relaxed"
            placeholder="请输入机器人的人设描述，例如：你是一个专业的客服助手，擅长解答用户问题..."></textarea>
          <div class="text-xs mt-1.5 text-right" style="color: var(--morandi-text-muted)">
            {{ personalityText.length }}/500 字符
          </div>
        </div>

        <!-- 知识库选择区 -->
        <div class="p-5 border-t" style="border-color: var(--morandi-divider)">
          <h3 class="text-sm font-medium mb-3" style="color: var(--morandi-text-secondary)">知识库</h3>

          <div v-if="currentKnowledgeBase"
            class="morandi-card rounded-lg p-3 mb-3">
            <div class="flex items-center justify-between">
              <div class="flex items-center min-w-0 flex-1">
                <Database class="w-4 h-4 mr-2 shrink-0" style="color: var(--morandi-accent)" />
                <span class="text-sm truncate font-medium" style="color: var(--morandi-text)">{{ currentKnowledgeBase.name }}</span>
              </div>
              <span class="text-xs ml-2 shrink-0 px-2 py-0.5 rounded-full" style="background: var(--morandi-success-bg); color: #fff">已选</span>
            </div>
            <div class="text-xs mt-1 ml-6" style="color: var(--morandi-text-muted)">
              {{ currentKnowledgeBase.documentCount || 0 }} 个文档 · {{ currentKnowledgeBase.chunkCount || 0 }} 切片
            </div>
          </div>

          <div v-else-if="selectedKnowledgeBases.length > 0"
            class="morandi-card rounded-lg p-3 mb-3">
            <div class="flex items-center justify-between mb-2">
              <div class="flex items-center">
                <Database class="w-4 h-4 mr-2" style="color: var(--morandi-accent)" />
                <span class="text-sm font-medium" style="color: var(--morandi-text)">已选择 {{ selectedKnowledgeBases.length }} 个</span>
              </div>
              <button @click="clearMultiSelection" class="text-xs px-2 py-0.5 rounded" style="color: var(--morandi-error); background: var(--morandi-error-bg)">
                清空
              </button>
            </div>
            <div class="space-y-1.5 max-h-24 overflow-y-auto transparent-scrollbar">
              <div v-for="kb in selectedKnowledgeBases" :key="kb.id"
                class="flex items-center justify-between px-2 py-1.5 rounded" style="background: var(--morandi-input-bg)">
                <span class="text-xs truncate" style="color: var(--morandi-text)">{{ kb.name }}</span>
                <button @click.stop="removeFromSelection(kb)" class="shrink-0 ml-2" style="color: var(--morandi-error)">
                  <X class="w-3 h-3" />
                </button>
              </div>
            </div>
          </div>

          <div v-else
            class="rounded-lg p-4 text-center" style="background: var(--morandi-input-bg); border: 1px dashed var(--morandi-border)">
            <FolderOpen class="w-6 h-6 mx-auto mb-1.5" style="color: var(--morandi-text-faint)" />
            <p class="text-xs" style="color: var(--morandi-text-muted)">暂未选择知识库</p>
          </div>

          <button @click="openKnowledgeModal" class="morandi-btn morandi-btn-primary w-full mt-3 text-sm">
            管理知识库
          </button>
        </div>
      </aside>

      <!-- 右侧：聊天区域 -->
      <main class="flex-1 flex flex-col min-w-0">
        <!-- 聊天头部 -->
        <div class="flex justify-between items-center px-6 py-3 border-b shrink-0" style="border-color: var(--morandi-divider); background: var(--morandi-surface-raised)">
          <h3 class="serif text-base font-medium" style="color: var(--morandi-text)">与 {{ currentAssistant?.name || '机器人' }} 对话</h3>
          <div class="flex items-center gap-3">
            <div v-if="voiceCallActive" class="flex items-center gap-2">
              <span class="relative flex h-2.5 w-2.5">
                <span class="animate-ping absolute inline-flex h-full w-full rounded-full opacity-75" style="background: var(--morandi-error)"></span>
                <span class="relative inline-flex rounded-full h-2.5 w-2.5" style="background: var(--morandi-error)"></span>
              </span>
              <span class="text-xs font-medium" style="color: var(--morandi-error)">语音通话中</span>
            </div>
            <button @click="resetChat" class="morandi-btn morandi-btn-ghost morandi-btn-sm">
              重置对话
            </button>
          </div>
        </div>

        <!-- ASR 语音识别文字 -->
        <div v-if="asrText && voiceCallActive"
          class="mx-6 mt-3 px-4 py-2 rounded-lg border text-sm"
          style="background: var(--morandi-input-bg); border-color: var(--morandi-border); color: var(--morandi-secondary)">
          <span class="font-medium" style="color: var(--morandi-text-secondary)">语音识别：</span>{{ asrText }}
        </div>

        <!-- 消息列表 -->
        <div class="flex-1 min-h-0">
          <ChatMessages :messages="messages" :auto-scroll="true"
            @scroll-state-change="handleScrollStateChange" class="h-full" />
        </div>

        <!-- 输入区域 -->
        <div class="p-5 border-t shrink-0" style="border-color: var(--morandi-divider); background: var(--morandi-surface)">
          <!-- 语音通话中 UI -->
          <div v-if="voiceCallActive" class="flex flex-col items-center gap-4 py-2">
            <div class="flex items-center gap-4">
              <div class="relative">
                <Mic class="w-7 h-7 animate-pulse" style="color: var(--morandi-error)" />
                <div class="absolute -inset-2 rounded-full border-2 animate-ping" style="border-color: var(--morandi-error); opacity: 0.4"></div>
              </div>
              <div class="flex items-center gap-1">
                <div v-for="i in 5" :key="i" class="w-1.5 rounded-full transition-all duration-150"
                  :style="{
                    height: `${Math.max(4, audioLevel * 40 * (0.5 + Math.random() * 0.5))}px`,
                    opacity: audioLevel > (i - 1) * 0.2 ? 1 : 0.3,
                    background: 'var(--morandi-primary)'
                  }"></div>
              </div>
            </div>
            <button @click="endVoiceCall"
              class="morandi-btn morandi-btn-danger px-8 py-2 text-sm">
              <PhoneOff class="w-4 h-4 inline mr-1.5" />
              挂断
            </button>
          </div>

          <!-- 文字输入 UI -->
          <div v-else class="flex items-center gap-3">
            <input v-model="inputText" type="text"
              class="flex-1 morandi-input rounded-lg px-4 py-2.5 text-sm"
              placeholder="请输入您想问的问题" @keyup.enter="sendMessage" :disabled="isTyping" />
            <button @click="startVoiceCall" :disabled="!currentAssistant?.id"
              class="morandi-btn morandi-btn-ghost px-3 py-2.5"
              :class="{ 'opacity-40 cursor-not-allowed': !currentAssistant?.id }">
              <Mic class="w-5 h-5" />
            </button>
            <button @click="sendMessage" :disabled="!inputText.trim() || isTyping"
              :class="['morandi-btn', 'morandi-btn-primary', (!inputText.trim() || isTyping) ? 'opacity-40 cursor-not-allowed' : '']">
              发送
            </button>
          </div>
        </div>
      </main>
    </div>

    <!-- ========== 知识库管理弹窗 ========== -->
    <div v-if="showKnowledgeModal"
      class="fixed inset-0 z-40 flex items-center justify-center"
      style="background: var(--morandi-overlay)"
      @click.self="closeKnowledgeModal">
      <div :class="[
        'animate-modal-in morandi-card-elevated max-h-[85vh] rounded-2xl shadow-lg flex overflow-hidden',
        showFileManager ? 'w-[1200px]' : 'w-[800px]'
      ]">
        <!-- 左侧：知识库列表 -->
        <div :class="[
          'transition-all duration-400 ease-in-out flex flex-col',
          showFileManager ? 'w-1/2' : 'w-full'
        ]">
          <!-- 弹窗头部 -->
          <div class="px-6 py-5 border-b" style="border-color: var(--morandi-border)">
            <div class="flex items-center justify-between mb-4">
              <h2 class="serif text-lg font-semibold" style="color: var(--morandi-text)">知识库管理</h2>
              <div class="flex items-center gap-3">
                <div class="flex rounded-md p-0.5" style="background: var(--morandi-input-bg)">
                  <button @click="knowledgeBaseLayout = 'list'"
                    :class="['px-2.5 py-1 rounded text-sm transition-all', knowledgeBaseLayout === 'list' ? 'morandi-btn morandi-btn-primary morandi-btn-sm' : '']"
                    :style="knowledgeBaseLayout !== 'list' ? { color: 'var(--morandi-text-muted)' } : {}">
                    <List class="w-4 h-4" />
                  </button>
                  <button @click="knowledgeBaseLayout = 'grid'"
                    :class="['px-2.5 py-1 rounded text-sm transition-all', knowledgeBaseLayout === 'grid' ? 'morandi-btn morandi-btn-primary morandi-btn-sm' : '']"
                    :style="knowledgeBaseLayout !== 'grid' ? { color: 'var(--morandi-text-muted)' } : {}">
                    <LayoutGrid class="w-4 h-4" />
                  </button>
                </div>
                <button @click="closeKnowledgeModal" class="p-1 rounded hover:bg-morandi-input-bg transition-colors" style="color: var(--morandi-text-muted)">
                  <X class="w-5 h-5" />
                </button>
              </div>
            </div>
            <button @click="openCreateKnowledgeForm" class="morandi-btn morandi-btn-primary text-sm">
              <Plus class="w-4 h-4 inline mr-1.5" /> 新建知识库
            </button>
          </div>

          <!-- 知识库列表内容 -->
          <div class="flex-1 min-h-0 overflow-y-auto morandi-scroll p-6">
            <div :class="[
              'transition-all duration-300',
              knowledgeBaseLayout === 'grid' ? 'grid grid-cols-2 gap-3' : 'flex flex-col gap-3'
            ]">
              <div v-for="kb in knowledgeBases" :key="kb.id" @click="selectKnowledgeBase(kb)"
                :class="[
                  'morandi-card rounded-xl p-4 cursor-pointer transition-all duration-200 hover:shadow-md',
                  knowledgeBaseLayout === 'list' ? 'flex items-center' : '',
                  selectedKnowledgeBases.some(selected => selected.id === kb.id) ? 'ring-1' : ''
                ]"
                :style="selectedKnowledgeBases.some(selected => selected.id === kb.id)
                  ? { borderColor: 'var(--morandi-primary)', background: 'var(--morandi-input-bg)' }
                  : {}">

                <!-- 列表模式 -->
                <template v-if="knowledgeBaseLayout === 'list'">
                  <div class="mr-3 flex-shrink-0">
                    <div :class="[
                      'w-5 h-5 rounded border-2 flex items-center justify-center transition-all'
                    ]"
                      :style="selectedKnowledgeBases.some(selected => selected.id === kb.id)
                        ? { background: 'var(--morandi-primary)', borderColor: 'var(--morandi-primary)' }
                        : { borderColor: 'var(--morandi-border)' }">
                      <Check v-if="selectedKnowledgeBases.some(selected => selected.id === kb.id)" class="w-3 h-3" style="color: #fff" />
                    </div>
                  </div>
                  <div class="flex items-center flex-1 min-w-0">
                    <Database class="w-5 h-5 mr-2.5 flex-shrink-0" style="color: var(--morandi-accent)" />
                    <div class="flex-1 min-w-0">
                      <h3 class="font-medium text-sm truncate" style="color: var(--morandi-text)">{{ kb.name }}</h3>
                      <p class="text-xs truncate" style="color: var(--morandi-text-muted)">{{ kb.description || '暂无描述' }}</p>
                    </div>
                  </div>
                  <div class="text-center mx-3 flex-shrink-0">
                    <div class="font-bold text-sm" style="color: var(--morandi-text)">{{ kb.documentCount || 0 }}</div>
                    <div class="text-xs" style="color: var(--morandi-text-faint)">文档</div>
                  </div>
                  <div class="flex gap-1 flex-shrink-0">
                    <button @click.stop="selectKnowledgeBaseForFileManager(kb)" class="p-1.5 rounded transition-colors hover:bg-morandi-input-bg" style="color: var(--morandi-primary)">
                      <FolderOpen class="w-4 h-4" />
                    </button>
                    <button @click.stop="deleteKnowledgeBase(kb)" class="p-1.5 rounded transition-colors hover:bg-morandi-input-bg" style="color: var(--morandi-error)">
                      <Trash2 class="w-4 h-4" />
                    </button>
                  </div>
                </template>

                <!-- 网格模式 -->
                <template v-else>
                  <div class="flex items-start justify-between mb-2">
                    <div class="flex items-center">
                      <div class="mr-2.5 flex-shrink-0">
                        <div :class="['w-5 h-5 rounded border-2 flex items-center justify-center transition-all']"
                          :style="selectedKnowledgeBases.some(selected => selected.id === kb.id)
                            ? { background: 'var(--morandi-primary)', borderColor: 'var(--morandi-primary)' }
                            : { borderColor: 'var(--morandi-border)' }">
                          <Check v-if="selectedKnowledgeBases.some(selected => selected.id === kb.id)" class="w-3 h-3" style="color: #fff" />
                        </div>
                      </div>
                      <Database class="w-6 h-6 mr-2" style="color: var(--morandi-accent)" />
                      <div>
                        <h3 class="font-medium text-sm" style="color: var(--morandi-text)">{{ kb.name }}</h3>
                        <p class="text-xs mt-0.5" style="color: var(--morandi-text-muted)">{{ kb.description || '暂无描述' }}</p>
                      </div>
                    </div>
                    <div class="flex gap-1.5">
                      <button @click.stop="selectKnowledgeBaseForFileManager(kb)" class="p-1 rounded transition-colors hover:bg-morandi-input-bg" style="color: var(--morandi-primary)">
                        <FolderOpen class="w-4 h-4" />
                      </button>
                      <button @click.stop="deleteKnowledgeBase(kb)" class="p-1 rounded transition-colors hover:bg-morandi-input-bg" style="color: var(--morandi-error)">
                        <Trash2 class="w-4 h-4" />
                      </button>
                    </div>
                  </div>
                  <div class="flex justify-between text-xs pt-2 border-t" style="border-color: var(--morandi-divider)">
                    <div class="text-center">
                      <div class="font-bold" style="color: var(--morandi-text)">{{ kb.documentCount || 0 }}</div>
                      <div style="color: var(--morandi-text-faint)">文档</div>
                    </div>
                    <div class="text-center">
                      <div class="font-bold" style="color: var(--morandi-text)">{{ kb.chunkCount || 0 }}</div>
                      <div style="color: var(--morandi-text-faint)">切片</div>
                    </div>
                  </div>
                </template>
              </div>
            </div>
          </div>

          <!-- 弹窗底部操作 -->
          <div class="px-6 py-4 border-t" style="border-color: var(--morandi-border)">
            <div class="mb-3">
              <div class="flex items-center justify-between mb-2">
                <span class="text-xs font-medium" style="color: var(--morandi-text-secondary)">
                  已选择 {{ selectedKnowledgeBases.length }} 个知识库
                </span>
                <button v-if="selectedKnowledgeBases.length > 0" @click="clearAllSelections" class="text-xs transition-colors" style="color: var(--morandi-error)">
                  清空选择
                </button>
              </div>
              <div v-if="selectedKnowledgeBases.length > 0" class="flex flex-wrap gap-1.5">
                <div v-for="kb in selectedKnowledgeBases" :key="kb.id"
                  class="px-2.5 py-1 rounded-md text-xs flex items-center gap-1.5"
                  style="background: var(--morandi-input-bg); color: var(--morandi-text); border: 1px solid var(--morandi-border)">
                  <span>{{ kb.name }}</span>
                  <button @click="removeFromSelection(kb)" style="color: var(--morandi-text-muted)">
                    <X class="w-3 h-3" />
                  </button>
                </div>
              </div>
              <div v-else class="text-xs" style="color: var(--morandi-text-faint)">
                未选择任何知识库
              </div>
            </div>
            <div class="flex justify-end gap-2.5">
              <button @click="closeKnowledgeModal" class="morandi-btn morandi-btn-ghost text-sm">取消</button>
              <button @click="confirmSelection" class="morandi-btn morandi-btn-primary text-sm">确认选择</button>
            </div>
          </div>
        </div>

        <!-- 右侧：文件管理面板 -->
        <div v-if="showFileManager"
          class="w-1/2 flex flex-col animate-slide-in-right border-l"
          style="background: var(--morandi-surface); border-left-color: var(--morandi-border)">
          <div class="px-5 py-4 border-b" style="border-color: var(--morandi-border)">
            <div class="flex items-center justify-between mb-3">
              <div class="flex items-center">
                <button @click="closeFileManager" class="p-1 rounded mr-2.5 transition-colors hover:bg-morandi-input-bg" style="color: var(--morandi-text-muted)">
                  <ChevronLeft class="w-5 h-5" />
                </button>
                <h3 class="serif text-base font-medium" style="color: var(--morandi-text)">文件管理</h3>
              </div>
              <button @click="triggerFileUpload" :disabled="isUploading"
                :class="['morandi-btn', isUploading ? 'morandi-btn-ghost' : '', 'text-sm']"
                :style="{ opacity: isUploading ? 0.45 : 1 }">
                <Upload class="w-4 h-4 inline mr-1" />
                {{ isUploading ? '上传中...' : '上传文件' }}
              </button>
            </div>

            <div v-if="isUploading && uploadProgress > 0" class="mb-3">
              <div class="flex justify-between text-xs mb-1.5" style="color: var(--morandi-text-secondary)">
                <span>上传进度</span>
                <span>{{ Math.round(uploadProgress) }}%</span>
              </div>
              <div class="w-full rounded-full h-1.5" style="background: var(--morandi-input-bg)">
                <div class="h-1.5 rounded-full transition-all duration-300"
                  :style="{ width: uploadProgress + '%', background: 'var(--morandi-accent)' }"></div>
              </div>
            </div>

            <div v-if="currentFileManagerKB" class="rounded-md p-2.5" style="background: var(--morandi-input-bg)">
              <div class="flex items-center">
                <Database class="w-4 h-4 mr-2" style="color: var(--morandi-accent)" />
                <span class="text-sm font-medium" style="color: var(--morandi-text)">{{ currentFileManagerKB.name }}</span>
              </div>
            </div>
          </div>

          <div class="flex-1 overflow-y-auto morandi-scroll p-5">
            <div v-if="currentFiles.length === 0" class="text-center py-12">
              <FileText class="w-12 h-12 mx-auto mb-3" style="color: var(--morandi-text-faint)" />
              <p class="text-sm" style="color: var(--morandi-text-muted)">暂无文件</p>
              <p class="text-xs mt-1" style="color: var(--morandi-text-faint)">点击上传按钮添加文档</p>
            </div>
            <div v-else class="space-y-2.5">
              <div v-for="file in currentFiles" :key="file.id"
                class="morandi-card rounded-lg p-3.5 transition-all duration-200 hover:shadow-sm">
                <div class="flex items-center justify-between">
                  <div class="flex items-center flex-1 min-w-0">
                    <FileText class="w-6 h-6 mr-2.5 flex-shrink-0" style="color: var(--morandi-primary)" />
                    <div class="flex-1 min-w-0">
                      <h4 class="text-sm font-medium truncate" style="color: var(--morandi-text)">{{ file.name }}</h4>
                      <div class="flex items-center text-xs mt-0.5" style="color: var(--morandi-text-muted)">
                        <span>{{ formatFileSize(file.size) }}</span>
                        <span class="mx-1.5">&middot;</span>
                        <span>{{ file.run || '未知' }}</span>
                      </div>
                    </div>
                  </div>
                  <button @click="deleteFile(file)" class="ml-3 p-1.5 rounded transition-colors hover:bg-morandi-input-bg shrink-0" style="color: var(--morandi-error)">
                    <Trash2 class="w-4 h-4" />
                  </button>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- ========== 创建知识库弹窗 ========== -->
    <div v-if="showCreateKnowledgeForm"
      class="fixed inset-0 z-50 flex items-center justify-center"
      style="background: var(--morandi-overlay)"
      @click.self="showCreateKnowledgeForm = false">
      <div class="animate-modal-in morandi-card-elevated w-[420px] p-7 rounded-2xl shadow-lg">
        <h3 class="serif text-lg font-semibold mb-5" style="color: var(--morandi-text)">创建知识库</h3>
        <div class="space-y-4">
          <div>
            <label class="block text-sm font-medium mb-1.5" style="color: var(--morandi-text-secondary)">
              <span style="color: var(--morandi-error)">*</span> 知识库名称
            </label>
            <input v-model="newKnowledgeBase.name" type="text" placeholder="请输入知识库名称" maxlength="20"
              class="w-full px-3.5 py-2.5 rounded-lg morandi-input text-sm" />
            <div class="text-xs mt-1 text-right" style="color: var(--morandi-text-faint)">{{ newKnowledgeBase.name.length }}/20</div>
          </div>
          <div>
            <label class="block text-sm font-medium mb-1.5" style="color: var(--morandi-text-secondary)">知识库描述</label>
            <textarea v-model="newKnowledgeBase.description" placeholder="请输入知识库描述" maxlength="200" rows="3"
              class="w-full px-3.5 py-2.5 rounded-lg morandi-input text-sm resize-none" />
            <div class="text-xs mt-1 text-right" style="color: var(--morandi-text-faint)">{{ newKnowledgeBase.description.length }}/200</div>
          </div>
        </div>
        <div class="flex justify-end gap-2.5 mt-6">
          <button @click="showCreateKnowledgeForm = false" class="morandi-btn morandi-btn-ghost text-sm">取消</button>
          <button @click="createKnowledgeBase" :disabled="!newKnowledgeBase.name.trim()"
            :class="['morandi-btn', 'morandi-btn-primary', 'text-sm', !newKnowledgeBase.name.trim() ? 'opacity-40 cursor-not-allowed' : '']">
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

const { themeMode, setTheme } = useTheme()

const router = useRouter()
const route = useRoute()

const currentAssistant = ref<Assistant | null>(null)
const ragflowConfig = ref<RAGFlowConfig>({ endpoint: '', apiKey: '' })

let ws: ReturnType<typeof useWebSocket> | null = null
let voiceWs: ReturnType<typeof useWebSocket> | null = null
const isFirstOfStream = ref(true)

const personalityText = ref('')
const originalPersonality = ref('')
const saving = ref(false)

const personalityChanged = computed(() => {
  return personalityText.value !== originalPersonality.value && personalityText.value.trim() !== ''
})

const onPersonalityChange = () => {
  if (personalityText.value.length > 500) {
    personalityText.value = personalityText.value.substring(0, 500)
  }
}

const savePersonality = async () => {
  if (!personalityChanged.value || !currentAssistant.value) return

  saving.value = true
  try {
    if (ws) {
      ws.send({
        type: 'prompt',
        content: personalityText.value,
      })
    }
    showNotification('人设保存成功！', 'success')
    originalPersonality.value = personalityText.value
  } catch (error) {
    console.error('保存人设失败:', error)
    showNotification(`保存失败：${(error as Error).message}`, 'error')
  } finally {
    saving.value = false
  }
}

const inputText = ref('')
const isTyping = ref(false)
const messages = ref<DisplayMessage[]>([])

const notification = ref({
  show: false,
  message: '',
  type: 'info' as 'success' | 'error' | 'warning' | 'info',
})

const showNotification = (message: string, type: 'success' | 'error' | 'warning' | 'info' = 'info') => {
  notification.value = { show: true, message, type }
  setTimeout(() => {
    notification.value.show = false
  }, 3000)
}

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

const newKnowledgeBase = ref({
  name: '',
  description: '',
})

const webrtc = useWebRTC()
const audioLevel = webrtc.audioLevel
const voiceCallActive = ref(false)
const asrText = ref('')

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
                const lastMsg = messages.value[messages.value.length - 1]
                if (lastMsg && lastMsg.role === 'assistant') {
                  lastMsg.text += answer.segment
                }
              }
            }
          } else if (data.type === 'query_end') {
            const queryData = data.data
            isTyping.value = false
            isFirstOfStream.value = true
            const lastMsg = messages.value[messages.value.length - 1]
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
        if (voiceCallActive.value) {
          endVoiceCall()
        }
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
  if (voiceWs) {
    voiceWs.send({ type: 'hangup' })
    voiceWs.close()
    voiceWs = null
  }
  webrtc.hangup()
  voiceCallActive.value = false
  asrText.value = ''
}

const connectWebSocket = () => {
  if (!currentAssistant.value?.id) return

  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  const host = window.location.host
  const wsUrl = `${protocol}//${host}/ws/${currentAssistant.value.id}`

  ws = useWebSocket(wsUrl, {
    onOpen: () => {
      setTimeout(() => {
        if (currentKnowledgeBase.value) {
          ws?.send({ type: 'selectedKbIds', ids: [currentKnowledgeBase.value!.id] })
        } else if (selectedKnowledgeBases.value.length > 0) {
          ws?.send({ type: 'selectedKbIds', ids: selectedKnowledgeBases.value.map(kb => kb.id) })
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
              const lastMsg = messages.value[messages.value.length - 1]
              if (lastMsg && lastMsg.role === 'assistant') {
                lastMsg.text += answer.segment
              }
            }
          }
        } else if (data.type === 'query_end') {
          const queryData = data.data
          isTyping.value = false
          isFirstOfStream.value = true
          const lastMsg = messages.value[messages.value.length - 1]
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

const resetChat = () => {
  messages.value = [
    { role: 'assistant', text: `您好，我是${currentAssistant.value?.name || '智能助手'}，专门解答相关问题。` },
  ]
  if (ws) {
    ws.send({ type: 'resetMessage' })
  }
}

const sendMessage = () => {
  if (!inputText.value.trim() || isTyping.value) return
  const userMessage = inputText.value
  messages.value.push({ role: 'user', text: userMessage })
  ws?.send({ type: 'chat', content: userMessage })
  inputText.value = ''
  isTyping.value = true
}

const goBack = () => {
  resetChat()
  if (ws) {
    ws.send({ type: 'close' })
    ws.close()
  }
  if (voiceWs) {
    voiceWs.send({ type: 'hangup' })
    voiceWs.close()
  }
  webrtc.hangup()
  voiceCallActive.value = false
  router.back()
}

const handleScrollStateChange = (_state: { userScrolledManually: boolean; isAtBottom: boolean }) => {
}

const loadRAGFlowConfig = async () => {
  try {
    ragflowConfig.value = await fetchKnowledgeConfig()
  } catch (error) {
    console.error('加载RAGFlow配置失败:', error)
  }
}

const loadAssistantInfo = async () => {
  try {
    if (history.state && history.state.assistant) {
      currentAssistant.value = history.state.assistant as Assistant
      personalityText.value = currentAssistant.value!.personality ||
        '这是一个默认的智能助手，擅长解答用户问题，提供准确、有用的信息。'
      originalPersonality.value = personalityText.value
      return
    }

    const assistantId = route.params.assistantId as string
    if (assistantId) {
      currentAssistant.value = await fetchAssistant(assistantId)
      personalityText.value = currentAssistant.value.personality ||
        '这是一个默认的智能助手，擅长解答用户问题，提供准确、有用的信息。'
      originalPersonality.value = personalityText.value
    }
  } catch (error) {
    console.error('加载助手信息失败:', error)
    showNotification('加载助手信息失败', 'error')
  }
}

const loadKnowledgeBases = async () => {
  if (!ragflowConfig.value.endpoint || !ragflowConfig.value.apiKey) return

  try {
    const response = await fetch(`${ragflowConfig.value.endpoint}/api/v1/datasets?page=1&page_size=10`, {
      method: 'GET',
      headers: { 'Authorization': ragflowConfig.value.apiKey },
    })

    if (!response.ok) throw new Error(`获取数据集列表失败: ${response.status}`)

    const result = await response.json()
    if (result.code === 0) {
      knowledgeBases.value = result.data.map((dataset: any) => ({
        id: dataset.id,
        name: dataset.name,
        description: dataset.description,
        documentCount: dataset.document_count || 0,
        chunkCount: dataset.chunk_count || 0,
        tokenCount: dataset.token_count || 0,
      }))
    }
  } catch (error) {
    console.error('获取知识库列表失败:', error)
    showNotification(`获取知识库列表失败: ${(error as Error).message}`, 'error')
  }
}

const restoreKnowledgeBaseSelection = () => {
  if (!currentAssistant.value?.id) return
  try {
    const saved = localStorage.getItem(`knowledgeBaseSelection_${currentAssistant.value.id}`)
    if (saved) {
      const selectionData = JSON.parse(saved)
      const isExpired = Date.now() - selectionData.timestamp > 24 * 60 * 60 * 1000
      if (!isExpired) {
        currentKnowledgeBase.value = selectionData.currentKnowledgeBase
        selectedKnowledgeBases.value = selectionData.selectedKnowledgeBases || []
      } else {
        localStorage.removeItem(`knowledgeBaseSelection_${currentAssistant.value.id}`)
      }
    }
  } catch (error) {
    console.error('恢复知识库选择状态失败:', error)
  }
}

const saveKnowledgeBaseSelection = () => {
  if (!currentAssistant.value?.id) return
  const selectionData = {
    currentKnowledgeBase: currentKnowledgeBase.value,
    selectedKnowledgeBases: selectedKnowledgeBases.value,
    timestamp: Date.now(),
  }
  localStorage.setItem(`knowledgeBaseSelection_${currentAssistant.value.id}`, JSON.stringify(selectionData))
}

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
  const index = selectedKnowledgeBases.value.findIndex(selected => selected.id === kb.id)
  if (index > -1) {
    selectedKnowledgeBases.value.splice(index, 1)
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

const loadKnowledgeBaseFiles = async (knowledgeBaseId: string) => {
  if (!knowledgeBaseId || !ragflowConfig.value.endpoint) {
    currentFiles.value = []
    return
  }

  try {
    const response = await fetch(
      `${ragflowConfig.value.endpoint}/api/v1/datasets/${knowledgeBaseId}/documents?page=1&page_size=100`,
      {
        method: 'GET',
        headers: { 'Authorization': ragflowConfig.value.apiKey },
      }
    )

    if (!response.ok) throw new Error(`获取文档列表失败: ${response.status}`)

    const result = await response.json()
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

const confirmSelection = () => {
  if (selectedKnowledgeBases.value.length > 0) {
    if (selectedKnowledgeBases.value.length === 1) {
      currentKnowledgeBase.value = selectedKnowledgeBases.value[0]
      selectedKnowledgeBases.value = []
      showNotification(`已选择知识库：${currentKnowledgeBase.value.name}`, 'success')
      if (ws) {
        ws.send({ type: 'selectedKbIds', ids: [currentKnowledgeBase.value.id] })
      }
    } else {
      const names = selectedKnowledgeBases.value.map(kb => kb.name).join('、')
      showNotification(`已选择 ${selectedKnowledgeBases.value.length} 个知识库：${names}`, 'success')
      currentKnowledgeBase.value = null
      if (ws) {
        ws.send({ type: 'selectedKbIds', ids: selectedKnowledgeBases.value.map(kb => kb.id) })
      }
    }
  } else {
    currentKnowledgeBase.value = null
    selectedKnowledgeBases.value = []
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
  if (currentAssistant.value?.id) {
    localStorage.removeItem(`knowledgeBaseSelection_${currentAssistant.value.id}`)
  }
}

const removeFromSelection = (kb: KnowledgeBase) => {
  const index = selectedKnowledgeBases.value.findIndex(selected => selected.id === kb.id)
  if (index > -1) {
    selectedKnowledgeBases.value.splice(index, 1)
    showNotification(`已移除：${kb.name}`, 'info')
  }
}

const openCreateKnowledgeForm = () => {
  showKnowledgeModal.value = false
  showCreateKnowledgeForm.value = true
}

const createKnowledgeBase = async () => {
  if (!newKnowledgeBase.value.name.trim()) {
    showNotification('请输入知识库名称', 'warning')
    return
  }

  try {
    const response = await fetch(`${ragflowConfig.value.endpoint}/api/v1/datasets`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': ragflowConfig.value.apiKey,
      },
      body: JSON.stringify({
        name: newKnowledgeBase.value.name.trim(),
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

    if (!response.ok) throw new Error(`创建数据集失败: ${response.status}`)

    const result = await response.json()
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
    const response = await fetch(`${ragflowConfig.value.endpoint}/api/v1/datasets`, {
      method: 'DELETE',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': ragflowConfig.value.apiKey,
      },
      body: JSON.stringify({ ids: [kb.id] }),
    })

    if (!response.ok) throw new Error(`删除数据集失败: ${response.status}`)

    const result = await response.json()
    if (result.code === 0) {
      showNotification('知识库删除成功', 'success')
      await loadKnowledgeBases()
      if (currentKnowledgeBase.value?.id === kb.id) {
        currentKnowledgeBase.value = null
      }
      selectedKnowledgeBases.value = selectedKnowledgeBases.value.filter(selected => selected.id !== kb.id)
    }
  } catch (error) {
    console.error('删除知识库失败:', error)
    showNotification(`删除知识库失败: ${(error as Error).message}`, 'error')
  }
}

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

const processFilesForFileManager = async (files: File[]) => {
  if (!files.length || !currentFileManagerKB.value) return

  isUploading.value = true
  uploadProgress.value = 0

  try {
    const uploadedDocs: string[] = []
    const totalFiles = files.length

    for (let i = 0; i < files.length; i++) {
      const file = files[i]

      if (file.size > 50 * 1024 * 1024) {
        showNotification(`文件 ${file.name} 超过50MB限制`, 'error')
        continue
      }

      try {
        const formData = new FormData()
        formData.append('file', file)

        const response = await fetch(
          `${ragflowConfig.value.endpoint}/api/v1/datasets/${currentFileManagerKB.value.id}/documents`,
          {
            method: 'POST',
            headers: { 'Authorization': ragflowConfig.value.apiKey },
            body: formData,
          }
        )

        if (!response.ok) throw new Error(`上传失败: ${response.status}`)

        const result = await response.json()
        if (result.code === 0) {
          for (const item of result.data) {
            uploadedDocs.push(item.id)
          }
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

const parseDocuments = async (datasetId: string, documentIds: string[]) => {
  const validDocumentIds = documentIds.filter(id => id && typeof id === 'string')
  if (validDocumentIds.length === 0) return

  const response = await fetch(`${ragflowConfig.value.endpoint}/api/v1/datasets/${datasetId}/chunks`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': ragflowConfig.value.apiKey,
    },
    body: JSON.stringify({ document_ids: validDocumentIds }),
  })

  if (!response.ok) throw new Error(`解析文档失败: ${response.status}`)

  const result = await response.json()
  if (result.code === 0) {
    showNotification('文档解析已开始，请稍后查看解析进度', 'success')
  } else {
    throw new Error(result.message || '解析文档失败')
  }
}

const deleteFile = async (file: any) => {
  if (!currentFileManagerKB.value) return
  if (!confirm(`确定要删除文件"${file.name}"吗？此操作不可撤销。`)) return

  try {
    const response = await fetch(
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

    if (!response.ok) throw new Error(`删除文档失败: ${response.status}`)

    const result = await response.json()
    if (result.code === 0) {
      showNotification('文件删除成功', 'success')
      await loadKnowledgeBaseFiles(currentFileManagerKB.value.id)
    }
  } catch (error) {
    console.error('删除文件失败:', error)
    showNotification(`删除文件失败: ${(error as Error).message}`, 'error')
  }
}

const formatFileSize = (bytes: number): string => {
  if (bytes === 0) return '0 Bytes'
  const k = 1024
  const sizes = ['Bytes', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i]
}

onMounted(async () => {
  await loadRAGFlowConfig()
  await loadAssistantInfo()
  await loadKnowledgeBases()
  restoreKnowledgeBaseSelection()

  if (currentAssistant.value?.id) {
    connectWebSocket()
    resetChat()
  } else {
    showNotification('加载助手信息失败', 'error')
  }
})

onBeforeUnmount(() => {
  if (ws) {
    ws.close()
  }
  if (voiceWs) {
    voiceWs.send({ type: 'hangup' })
    voiceWs.close()
  }
  webrtc.hangup()
})
</script>

<style scoped>
@keyframes slide-in-right {
  from {
    opacity: 0;
    transform: translateX(100%);
  }
  to {
    opacity: 1;
    transform: translateX(0);
  }
}

.animate-slide-in-right {
  animation: slide-in-right 0.4s ease-out;
}

.notification-enter-active,
.notification-leave-active {
  transition: all 0.25s ease;
}

.notification-enter-from {
  opacity: 0;
  transform: translateY(-8px);
}

.notification-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}
</style>
