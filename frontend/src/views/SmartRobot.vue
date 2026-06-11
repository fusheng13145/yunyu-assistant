<template>
  <div class="morandi-body theme-transition h-screen w-full flex overflow-hidden antialiased">
    <!-- 通知提示 -->
    <transition name="notification">
      <div v-if="notification.show" :style="{
        position: 'fixed', top: '1rem', right: '1rem', zIndex: 50,
        padding: '0.75rem 1.5rem', borderRadius: 'var(--radius-md)',
        boxShadow: '0 4px 16px var(--morandi-shadow-color), 0 1px 3px var(--morandi-shadow-color)',
        border: '1px solid var(--morandi-border-strong)',
        color: 'var(--morandi-text-on-primary)', fontWeight: 500,
        background: notification.type === 'success' ? 'var(--morandi-success)' :
          notification.type === 'error' ? 'var(--morandi-error)' :
            notification.type === 'warning' ? 'var(--morandi-warning)' : 'var(--morandi-primary)'
      }">
        {{ notification.message }}
      </div>
    </transition>

    <!-- 左侧边栏 -->
    <aside class="w-72 flex-shrink-0 flex flex-col bg-morandi-warm border-r border-morandi">
      <!-- 品牌标题区 -->
      <div class="px-6 py-5 border-b border-morandi">
        <h1 class="serif text-xl font-semibold" style="color: var(--morandi-text)">云谕助手</h1>
        <p class="text-xs mt-0.5" style="color: var(--morandi-text-muted)">智能对话工作台</p>
      </div>

      <!-- 新建助手按钮 -->
      <div class="px-4 py-4">
        <button @click="openModal"
          class="morandi-btn morandi-btn-primary w-full flex items-center justify-center gap-2 py-2.5">
          <Plus class="w-4 h-4" />
          <span>新建助手</span>
        </button>
      </div>

      <!-- 助手列表 -->
      <div class="flex-1 min-h-0 overflow-y-auto morandi-scroll transparent-scrollbar px-3 pb-4">
        <div v-if="assistants.length === 0" class="text-center py-12 px-4">
          <Bot class="w-10 h-10 mx-auto mb-3" style="color: var(--morandi-text-faint)" />
          <p class="text-sm" style="color: var(--morandi-text-muted)">暂无助手</p>
          <p class="text-xs mt-1" style="color: var(--morandi-text-faint)">点击上方按钮创建</p>
        </div>

        <div v-for="(bot, index) in assistants" :key="bot.id"
          @click="selectAssistant(bot)"
          class="group relative flex items-center gap-3 px-3 py-3 mb-1 rounded-[var(--radius-md)] cursor-pointer transition-all duration-200"
          :class="selectedAssistant?.id === bot.id ? 'bg-morandi-bg-subtle' : 'hover:bg-morandi-surface'"
          :style="selectedAssistant?.id === bot.id ? { borderLeft: '3px solid var(--morandi-primary)', paddingLeft: 'calc(0.75rem - 3px)' } : {}">
          <!-- 头像 -->
          <div
            class="w-9 h-9 rounded-full flex items-center justify-center flex-shrink-0"
            :style="{ background: ['var(--morandi-tag-blue)', 'var(--morandi-tag-purple)', 'var(--morandi-tag-gold)'][index % 3] }">
            <Bot class="w-4 h-4" style="color: white" />
          </div>
          <!-- 名称和消息数 -->
          <div class="flex-1 min-w-0">
            <div class="text-sm font-medium truncate" style="color: var(--morandi-text)">{{ bot.name || '小助手' }}</div>
            <div class="flex items-center gap-2 mt-0.5">
              <span class="text-xs" style="color: var(--morandi-text-muted)">{{ getAssistantType(bot) }}</span>
              <span v-if="getConversationCount(bot.id) > 0"
                class="text-xs px-1.5 py-0.5 rounded-full"
                style="background: var(--morandi-bg-subtle); color: var(--morandi-text-secondary)">
                {{ getConversationCount(bot.id) }}
              </span>
            </div>
          </div>
          <!-- hover 操作按钮 -->
          <div class="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity duration-200 flex-shrink-0">
            <button
              class="p-1 rounded-[var(--radius-sm)] transition-colors"
              style="color: var(--morandi-text-muted)"
              @mouseenter="(e: Event) => (e.target as HTMLElement).style.color = 'var(--morandi-text-secondary)'"
              @mouseleave="(e: Event) => (e.target as HTMLElement).style.color = 'var(--morandi-text-muted)'"
              @click.stop="openSettingsModal(bot)">
              <Settings class="w-3.5 h-3.5" />
            </button>
            <button
              class="p-1 rounded-[var(--radius-sm)] transition-colors"
              style="color: var(--morandi-text-muted)"
              @mouseenter="(e: Event) => (e.target as HTMLElement).style.color = 'var(--morandi-error)'"
              @mouseleave="(e: Event) => (e.target as HTMLElement).style.color = 'var(--morandi-text-muted)'"
              @click.stop="confirmDelete(bot)">
              <Trash2 class="w-3.5 h-3.5" />
            </button>
          </div>
        </div>
      </div>

      <!-- 底部用户区 -->
      <div class="px-4 py-3 border-t border-morandi">
        <div class="flex items-center justify-between">
          <div class="flex items-center gap-2">
            <div class="w-7 h-7 rounded-full flex items-center justify-center text-xs font-medium text-white"
              style="background: var(--morandi-primary)">
              {{ userInitial }}
            </div>
            <span class="text-sm" style="color: var(--morandi-text)">{{ userName }}</span>
          </div>
          <button @click="handleLogout"
            class="morandi-btn morandi-btn-ghost text-xs px-2 py-1 flex items-center gap-1">
            <LogOut class="w-3.5 h-3.5" />
            <span>登出</span>
          </button>
        </div>
      </div>
    </aside>

    <!-- 右侧主区域 -->
    <main class="flex-1 flex flex-col min-w-0 bg-morandi-base">
      <!-- 顶部工具栏 -->
      <header class="h-14 flex items-center justify-between px-6 border-b border-morandi bg-morandi-subtle flex-shrink-0">
        <div class="flex items-center gap-3">
          <h2 v-if="selectedAssistant" class="serif text-lg font-medium" style="color: var(--morandi-text)">
            {{ selectedAssistant.name }}
          </h2>
          <span v-else class="text-sm" style="color: var(--morandi-text-muted)">请选择一个助手开始对话</span>
        </div>
        <div class="flex items-center gap-2 relative">
          <button v-if="selectedAssistant" @click="openSettingsModal(selectedAssistant)"
            class="morandi-btn morandi-btn-ghost px-3 py-1.5 text-sm flex items-center gap-1.5">
            <Settings class="w-4 h-4" />
            <span>设置</span>
          </button>
          <button v-if="selectedAssistant" @click="resetChat"
            class="morandi-btn morandi-btn-ghost px-3 py-1.5 text-sm flex items-center gap-1.5">
            <RotateCcw class="w-4 h-4" />
            <span>刷新</span>
          </button>
          <!-- 操作按钮组 -->
          <div class="flex items-center gap-1.5">
            <!-- 消息搜索 -->
            <button v-if="messages.length > 0 && !isSearchActive"
              @click="openSearch()"
              class="morandi-btn morandi-btn-ghost morandi-btn-sm"
              title="搜索消息">
              <Search class="w-3.5 h-3.5" />
            </button>
            <!-- 导出对话 -->
            <button v-if="messages.length > 0"
              @click="handleExport"
              class="morandi-btn morandi-btn-ghost morandi-btn-sm"
              title="导出对话">
              <Download class="w-3.5 h-3.5" />
            </button>
            <!-- 快捷命令 -->
            <button
              @click="showQuickCommands = !showQuickCommands"
              class="morandi-btn morandi-btn-ghost morandi-btn-sm"
              title="快捷命令">
              <Zap class="w-3.5 h-3.5" />
            </button>
          </div>
          <!-- 快捷命令面板 -->
          <div v-if="showQuickCommands" class="absolute top-full right-6 mt-1">
            <div class="morandi-card-elevated p-2 rounded-xl shadow-lg z-50 animate-modal-in min-w-[160px]">
              <div class="text-xs font-medium px-2 py-1 text-morandi-muted">快捷命令</div>
              <button v-for="cmd in quickCommands" :key="cmd.id"
                @click="sendQuickCommand(cmd)"
                class="w-full text-left px-2 py-1.5 rounded-md text-sm hover:bg-morandi-primary-light transition-colors"
                style="color: var(--morandi-text)">
                {{ cmd.label }}
              </button>
            </div>
          </div>
          <ThemeToggle :model-value="themeMode" @update:model-value="setTheme" />
        </div>
      </header>

      <!-- 聊天消息区 -->
      <div class="flex-1 min-h-0 relative flex flex-col">
        <!-- 搜索框（激活时显示） -->
        <div v-if="isSearchActive" class="px-4 py-2 border-b border-morandi bg-morandi-subtle flex items-center gap-2 flex-shrink-0">
          <input v-model="searchQuery"
            placeholder="搜索消息..."
            class="morandi-input flex-1 text-sm py-1.5"
            @input="searchMessages(searchQuery)" />
          <button @click="clearSearch()" class="morandi-btn morandi-btn-ghost morandi-btn-sm">
            <X class="w-3.5 h-3.5" />
          </button>
        </div>

        <div v-if="!selectedAssistant" class="absolute inset-0 flex items-center justify-center">
          <div class="text-center animate-fade-up">
            <div class="w-20 h-20 rounded-full flex items-center justify-center mx-auto mb-5 morandi-card-elevated">
              <MessageCircle class="w-10 h-10" style="color: var(--morandi-text-muted)" />
            </div>
            <h3 class="serif text-xl mb-2" style="color: var(--morandi-text-secondary)">欢迎使用云谕助手</h3>
            <p class="text-sm" style="color: var(--morandi-text-muted)">请从左侧选择一个助手开始对话</p>
          </div>
        </div>

        <template v-if="selectedAssistant">
          <div v-if="asrText && voiceCallActive"
            class="mx-6 mt-3 px-4 py-2 rounded-[var(--radius-md)] border text-sm"
            style="background: var(--morandi-bg-subtle); border-color: var(--morandi-primary); color: var(--morandi-primary)">
            <span class="font-medium">语音识别：</span>{{ asrText }}
          </div>

          <ChatMessages :messages="messages" :auto-scroll="true"
            @scroll-state-change="handleScrollStateChange" class="h-full transparent-scrollbar" />
        </template>
      </div>

      <!-- 底部输入区 -->
      <div v-if="selectedAssistant" class="border-t border-morandi p-4 bg-morandi-base flex-shrink-0">
        <div v-if="voiceCallActive" class="flex flex-col items-center gap-4 py-2">
          <div class="flex items-center gap-4">
            <div class="relative">
              <Mic class="w-8 h-8 animate-pulse" style="color: var(--morandi-error)" />
              <div class="absolute -inset-2 rounded-full border-2 animate-ping" style="border-color: var(--morandi-error); opacity: 0.3"></div>
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
            class="morandi-btn morandi-btn-danger px-8 py-2.5 flex items-center gap-2">
            <PhoneOff class="w-4 h-4" />
            <span>挂断</span>
          </button>
        </div>

        <div v-else class="flex items-center gap-3">
          <input v-model="inputText" type="text"
            class="flex-1 morandi-input h-12 px-4 rounded-[var(--radius-lg)]"
            placeholder="请输入您想问的问题..." @keyup.enter="sendMessage" :disabled="isTyping" />
          <button @click="startVoiceCall"
            :class="[
              'morandi-btn morandi-btn-ghost w-12 h-12 rounded-full flex items-center justify-center',
              !selectedAssistant?.id ? 'opacity-40 cursor-not-allowed' : ''
            ]"
            :disabled="!selectedAssistant?.id">
            <Mic class="w-5 h-5" />
          </button>
          <button @click="sendMessage" :disabled="!inputText.trim() || isTyping"
            :class="[
              'morandi-btn morandi-btn-primary w-12 h-12 rounded-full flex items-center justify-center',
              (!inputText.trim() || isTyping) ? 'opacity-40 cursor-not-allowed' : ''
            ]">
            <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <line x1="22" y1="2" x2="11" y2="13"></line>
              <polygon points="22 2 15 22 11 13 2 9 22 2"></polygon>
            </svg>
          </button>
        </div>
      </div>
    </main>

    <!-- 新增助手 / 删除确认 弹窗 -->
    <div v-if="showModal || showDeleteModal"
      class="fixed inset-0 z-50 flex items-center justify-center"
      style="background: var(--morandi-overlay)"
      @click.self="showModal ? closeModal() : cancelDelete()">
      <div class="animate-modal-in morandi-card-elevated rounded-[var(--radius-xl)] w-[420px] p-6">
        <!-- 新增助手 -->
        <div v-if="showModal">
          <h3 class="serif text-lg font-semibold mb-6" style="color: var(--morandi-text)">新增助手</h3>
          <div class="space-y-4">
            <div>
              <label class="block text-sm font-medium mb-1.5" style="color: var(--morandi-text-secondary)">助手名称</label>
              <input v-model="formData.name" type="text" placeholder="请输入助手名称"
                class="w-full morandi-input px-4 py-2.5 rounded-[var(--radius-md)]" />
            </div>
            <div>
              <label class="block text-sm font-medium mb-1.5" style="color: var(--morandi-text-secondary)">助手描述</label>
              <textarea v-model="formData.description" placeholder="请输入助手描述（可选）"
                class="w-full morandi-input px-4 py-2.5 rounded-[var(--radius-md)] resize-none" rows="3" />
            </div>
            <div>
              <label class="block text-sm font-medium mb-1.5" style="color: var(--morandi-text-secondary)">系统提示词</label>
              <textarea v-model="formData.personality" placeholder="定义助手的人设和性格..."
                class="w-full morandi-input px-4 py-2.5 rounded-[var(--radius-md)] resize-none" rows="4" />
            </div>
          </div>
          <div class="flex justify-end gap-3 mt-6">
            <button @click="closeModal" class="morandi-btn morandi-btn-ghost">取消</button>
            <button @click="addAssistant()" class="morandi-btn morandi-btn-primary">确定创建</button>
          </div>
        </div>

        <!-- 删除确认 -->
        <div v-if="showDeleteModal">
          <h3 class="serif text-lg font-semibold mb-2" style="color: var(--morandi-error)">确认删除</h3>
          <p class="text-sm mb-6" style="color: var(--morandi-text-secondary)">你确定要删除这个助手吗？此操作无法撤销。</p>
          <div class="flex justify-end gap-3">
            <button @click="cancelDelete" class="morandi-btn morandi-btn-ghost">取消</button>
            <button @click="doDelete" class="morandi-btn morandi-btn-danger">确认删除</button>
          </div>
        </div>
      </div>
    </div>

    <!-- 设置弹窗 -->
    <div v-if="showSettings"
      class="fixed inset-0 z-40 flex items-center justify-center"
      style="background: var(--morandi-overlay)"
      @click.self="closeSettingsModal">
      <div class="animate-modal-in morandi-card-elevated rounded-[var(--radius-xl)] w-[900px] max-h-[85vh] flex flex-col overflow-hidden">
        <!-- 设置头部 -->
        <div class="flex items-center justify-between px-6 py-4 border-b border-morandi flex-shrink-0">
          <h2 class="serif text-xl font-semibold" style="color: var(--morandi-text)">助手设置</h2>
          <button @click="closeSettingsModal" class="p-1.5 rounded-[var(--radius-sm)] transition-colors morandi-btn morandi-btn-ghost">
            <X class="w-5 h-5" />
          </button>
        </div>

        <!-- 设置内容 -->
        <div class="flex-1 min-h-0 overflow-y-auto morandi-scroll transparent-scrollbar p-6 space-y-5">
          <!-- 助手信息卡片 -->
          <div class="morandi-card rounded-[var(--radius-lg)] p-5">
            <div class="flex items-center gap-3">
              <div
                class="w-10 h-10 rounded-full flex items-center justify-center flex-shrink-0"
                style="background: var(--morandi-tag-blue)">
                <Bot class="w-5 h-5" style="color: white" />
              </div>
              <div>
                <div class="font-semibold" style="color: var(--morandi-text)">{{ settingsAssistant?.name }}</div>
                <div class="text-sm mt-0.5" style="color: var(--morandi-text-muted)">{{ settingsAssistant?.description }}</div>
              </div>
            </div>
          </div>

          <!-- 人设设置 -->
          <div class="morandi-card rounded-[var(--radius-lg)] p-5">
            <div class="flex items-center justify-between mb-4">
              <h3 class="font-semibold" style="color: var(--morandi-text)">人设设置</h3>
              <button @click="savePersonality" :disabled="!personalityChanged"
                :class="['morandi-btn morandi-btn-primary text-sm px-4 py-1.5', personalityChanged ? '' : 'opacity-40 cursor-not-allowed']">
                {{ saving ? '保存中...' : '保存人设' }}
              </button>
            </div>
            <textarea v-model="personalityText" @input="onPersonalityChange"
              class="w-full h-40 resize-none morandi-input rounded-[var(--radius-md)] p-4 text-sm leading-relaxed"
              placeholder="请输入机器人的人设描述，例如：你是一个专业的客服助手，擅长解答用户问题..."></textarea>
            <div class="text-xs mt-2 text-right" style="color: var(--morandi-text-muted)">
              {{ personalityText.length }}/500 字符
            </div>
          </div>

          <!-- 知识库选择 -->
          <div class="morandi-card rounded-[var(--radius-lg)] p-5">
            <div class="flex items-center justify-between mb-4">
              <h3 class="font-semibold" style="color: var(--morandi-text)">知识库配置</h3>
              <button @click="openKnowledgeModal"
                class="morandi-btn morandi-btn-primary text-sm px-4 py-1.5 flex items-center gap-1.5">
                <Database class="w-4 h-4" />
                <span>管理知识库</span>
              </button>
            </div>

            <div v-if="currentKnowledgeBase"
              class="morandi-card px-4 py-3 rounded-[var(--radius-md)]">
              <div class="flex items-center justify-between">
                <div class="flex items-center">
                  <Database class="w-5 h-5 mr-3" style="color: var(--morandi-primary)" />
                  <div>
                    <div class="font-medium text-sm" style="color: var(--morandi-text)">{{ currentKnowledgeBase.name }}</div>
                    <div class="text-xs mt-0.5" style="color: var(--morandi-text-muted)">
                      {{ currentKnowledgeBase.documentCount || 0 }} 个文档 · {{ currentKnowledgeBase.chunkCount || 0 }} 切片
                    </div>
                  </div>
                </div>
                <span class="text-xs px-2 py-0.5 rounded-full" style="background: var(--morandi-success-bg); color: var(--morandi-success)">已选择</span>
              </div>
            </div>

            <div v-else-if="selectedKnowledgeBases.length > 0"
              class="morandi-card px-4 py-3 rounded-[var(--radius-md)]">
              <div class="flex items-center justify-between mb-2">
                <div class="flex items-center">
                  <Database class="w-5 h-5 mr-3" style="color: var(--morandi-accent)" />
                  <div>
                    <div class="font-medium text-sm" style="color: var(--morandi-text)">已选择 {{ selectedKnowledgeBases.length }} 个知识库</div>
                    <div class="text-xs" style="color: var(--morandi-text-muted)">多知识库联合检索模式</div>
                  </div>
                </div>
                <button @click="clearMultiSelection"
                  class="text-xs px-2 py-1 rounded-[var(--radius-sm)] transition-colors"
                  style="color: var(--morandi-error); background: var(--morandi-error-bg)"
                  @mouseenter="(e: Event) => { const el = e.target as HTMLElement; el.style.background = 'var(--morandi-error)'; el.style.color = 'white'; }"
                  @mouseleave="(e: Event) => { const el = e.target as HTMLElement; el.style.background = 'var(--morandi-error-bg)'; el.style.color = 'var(--morandi-error)'; }">
                  清空
                </button>
              </div>
              <div class="space-y-1 max-h-24 overflow-y-auto transparent-scrollbar">
                <div v-for="kb in selectedKnowledgeBases" :key="kb.id"
                  class="flex items-center justify-between px-3 py-1.5 rounded-[var(--radius-sm)]"
                  style="background: var(--morandi-input-bg)">
                  <div class="flex items-center">
                    <Check class="w-3 h-3 mr-2" style="color: var(--morandi-primary)" />
                    <span class="text-sm" style="color: var(--morandi-text-secondary)">{{ kb.name }}</span>
                  </div>
                  <button @click.stop="removeFromSelection(kb)" class="transition-colors p-0.5"
                    style="color: var(--morandi-text-muted)"
                    @mouseenter="(e: Event) => (e.target as HTMLElement).style.color = 'var(--morandi-error)'"
                    @mouseleave="(e: Event) => (e.target as HTMLElement).style.color = 'var(--morandi-text-muted)'">
                    <X class="w-3 h-3" />
                  </button>
                </div>
              </div>
            </div>

            <div v-else
              class="px-4 py-8 rounded-[var(--radius-md)] text-center"
              style="background: var(--morandi-input-bg); border: 1px dashed var(--morandi-border)">
              <FolderOpen class="w-8 h-8 mx-auto mb-2" style="color: var(--morandi-text-faint)" />
              <p class="text-sm" style="color: var(--morandi-text-muted)">点击「管理知识库」选择或创建知识库</p>
            </div>
          </div>
        </div>

        <!-- 设置底部 -->
        <div class="px-6 py-4 border-t border-morandi flex-shrink-0">
          <div class="flex justify-end">
            <button @click="closeSettingsModal" class="morandi-btn morandi-btn-ghost">关闭</button>
          </div>
        </div>
      </div>
    </div>

    <!-- 知识库管理弹窗 -->
    <div v-if="showKnowledgeModal"
      class="fixed inset-0 z-50 flex items-center justify-center"
      style="background: var(--morandi-overlay)"
      @click.self="closeKnowledgeModal">
      <div :class="[
        'animate-modal-in morandi-card-elevated rounded-[var(--radius-xl)] flex overflow-hidden transition-all duration-500',
        showFileManager ? 'w-[1400px]' : 'w-[900px]'
      ]">
        <!-- 知识库列表面板 -->
        <div :class="[
          'transition-all duration-500 ease-in-out flex flex-col',
          showFileManager ? 'w-1/2' : 'w-full'
        ]">
          <!-- 知识库头部 -->
          <div class="px-6 py-5 border-b border-morandi flex-shrink-0">
            <div class="flex items-center justify-between mb-5">
              <h2 class="serif text-xl font-semibold" style="color: var(--morandi-text)">知识库管理</h2>
              <div class="flex items-center gap-3">
                <div class="rounded-[var(--radius-sm)] p-0.5 flex" style="background: var(--morandi-input-bg)">
                  <button @click="knowledgeBaseLayout = 'list'" :class="[
                    'px-2.5 py-1 rounded-[var(--radius-sm)] text-sm transition-all duration-200',
                    knowledgeBaseLayout === 'list' ? 'morandi-btn morandi-btn-primary' : ''
                  ]"
                    :style="knowledgeBaseLayout !== 'list' ? 'color: var(--morandi-text-muted)' : undefined">
                    <List class="w-4 h-4" />
                  </button>
                  <button @click="knowledgeBaseLayout = 'grid'" :class="[
                    'px-2.5 py-1 rounded-[var(--radius-sm)] text-sm transition-all duration-200',
                    knowledgeBaseLayout === 'grid' ? 'morandi-btn morandi-btn-primary' : ''
                  ]"
                    :style="knowledgeBaseLayout !== 'grid' ? 'color: var(--morandi-text-muted)' : undefined">
                    <LayoutGrid class="w-4 h-4" />
                  </button>
                </div>
                <button @click="closeKnowledgeModal" class="p-1.5 rounded-[var(--radius-sm)] morandi-btn morandi-btn-ghost">
                  <X class="w-5 h-5" />
                </button>
              </div>
            </div>

            <button @click="openCreateKnowledgeForm"
              class="morandi-btn morandi-btn-primary text-sm px-4 py-2 inline-flex items-center gap-1.5">
              <Plus class="w-4 h-4" />
              <span>新建知识库</span>
            </button>
          </div>

          <!-- 知识库列表 -->
          <div class="flex-1 min-h-0 overflow-y-auto morandi-scroll transparent-scrollbar p-6">
            <div :class="[
              'transition-all duration-300',
              knowledgeBaseLayout === 'grid' ? 'grid grid-cols-2 gap-4' : 'grid grid-cols-1 gap-3'
            ]">
              <div v-for="kb in knowledgeBases" :key="kb.id" @click="selectKnowledgeBase(kb)" :class="[
                'morandi-card rounded-[var(--radius-lg)] p-4 cursor-pointer transition-all duration-200',
                knowledgeBaseLayout === 'list' ? 'flex items-center' : 'block',
                selectedKnowledgeBases.some(selected => selected.id === kb.id) ? 'ring-2 ring-[var(--morandi-primary)] bg-morandi-bg-subtle' : ''
              ]">
                <template v-if="knowledgeBaseLayout === 'list'">
                  <div class="flex items-center w-full">
                    <div class="mr-3 flex-shrink-0">
                      <div :class="[
                        'w-5 h-5 rounded border-2 flex items-center justify-center transition-all duration-200',
                      ]"
                        :style="selectedKnowledgeBases.some(selected => selected.id === kb.id)
                          ? 'background: var(--morandi-primary); border-color: var(--morandi-primary)'
                          : 'border-color: var(--morandi-border)'">
                        <Check v-if="selectedKnowledgeBases.some(selected => selected.id === kb.id)"
                          class="w-3 h-3" style="color: white" />
                      </div>
                    </div>
                    <div class="flex items-center flex-1 min-w-0">
                      <Database class="w-5 h-5 mr-3 flex-shrink-0" style="color: var(--morandi-tag-gold)" />
                      <div class="flex-1 min-w-0">
                        <h3 class="font-semibold text-sm truncate" style="color: var(--morandi-text)">{{ kb.name }}</h3>
                        <p class="text-xs truncate mt-0.5" style="color: var(--morandi-text-muted)">{{ kb.description || '暂无描述' }}</p>
                      </div>
                    </div>
                    <div class="flex items-center gap-3 mx-3 flex-shrink-0">
                      <div class="text-center">
                        <div class="font-bold text-sm" style="color: var(--morandi-primary)">{{ kb.documentCount || 0 }}</div>
                        <div class="text-xs" style="color: var(--morandi-text-faint)">文档</div>
                      </div>
                    </div>
                    <div class="flex gap-1 flex-shrink-0">
                      <button @click.stop="selectKnowledgeBaseForFileManager(kb)"
                        class="p-1.5 rounded-[var(--radius-sm)] transition-colors"
                        style="color: var(--morandi-text-muted)"
                        @mouseenter="(e: Event) => (e.currentTarget as HTMLElement).style.background = 'var(--morandi-surface)'"
                        @mouseleave="(e: Event) => (e.currentTarget as HTMLElement).style.background = 'transparent'">
                        <FolderOpen class="w-4 h-4" />
                      </button>
                      <button @click.stop="deleteKnowledgeBase(kb)"
                        class="p-1.5 rounded-[var(--radius-sm)] transition-colors"
                        style="color: var(--morandi-text-muted)"
                        @mouseenter="(e: Event) => (e.currentTarget as HTMLElement).style.color = 'var(--morandi-error)'"
                        @mouseleave="(e: Event) => (e.currentTarget as HTMLElement).style.color = 'var(--morandi-text-muted)'">
                        <Trash2 class="w-4 h-4" />
                      </button>
                    </div>
                  </div>
                </template>

                <template v-else>
                  <div class="flex items-start justify-between mb-3">
                    <div class="flex items-center">
                      <div class="mr-3 flex-shrink-0">
                        <div :class="[
                          'w-5 h-5 rounded border-2 flex items-center justify-center transition-all duration-200',
                        ]"
                          :style="selectedKnowledgeBases.some(selected => selected.id === kb.id)
                            ? 'background: var(--morandi-primary); border-color: var(--morandi-primary)'
                            : 'border-color: var(--morandi-border)'">
                          <Check v-if="selectedKnowledgeBases.some(selected => selected.id === kb.id)"
                            class="w-3 h-3" style="color: white" />
                        </div>
                      </div>
                      <Database class="w-7 h-7 mr-3" style="color: var(--morandi-tag-gold)" />
                      <div>
                        <h3 class="font-semibold" style="color: var(--morandi-text)">{{ kb.name }}</h3>
                        <p class="text-sm mt-0.5" style="color: var(--morandi-text-secondary)">{{ kb.description || '暂无描述' }}</p>
                      </div>
                    </div>
                    <div class="flex gap-1.5">
                      <button @click.stop="selectKnowledgeBaseForFileManager(kb)"
                        class="p-1.5 rounded-[var(--radius-sm)] transition-colors"
                        style="color: var(--morandi-text-muted)"
                        @mouseenter="(e: Event) => (e.currentTarget as HTMLElement).style.background = 'var(--morandi-surface)'"
                        @mouseleave="(e: Event) => (e.currentTarget as HTMLElement).style.background = 'transparent'">
                        <FolderOpen class="w-4 h-4" />
                      </button>
                      <button @click.stop="deleteKnowledgeBase(kb)"
                        class="p-1.5 rounded-[var(--radius-sm)] transition-colors"
                        style="color: var(--morandi-text-muted)"
                        @mouseenter="(e: Event) => (e.currentTarget as HTMLElement).style.color = 'var(--morandi-error)'"
                        @mouseleave="(e: Event) => (e.currentTarget as HTMLElement).style.color = 'var(--morandi-text-muted)'">
                        <Trash2 class="w-4 h-4" />
                      </button>
                    </div>
                  </div>
                  <div class="flex justify-between text-sm pt-3" style="border-top: 1px solid var(--morandi-divider)">
                    <div class="text-center">
                      <div class="font-bold" style="color: var(--morandi-primary)">{{ kb.documentCount || 0 }}</div>
                      <div class="text-xs" style="color: var(--morandi-text-faint)">文档数</div>
                    </div>
                    <div class="text-center">
                      <div class="font-bold" style="color: var(--morandi-accent)">{{ kb.chunkCount || 0 }}</div>
                      <div class="text-xs" style="color: var(--morandi-text-faint)">切片数</div>
                    </div>
                  </div>
                </template>
              </div>
            </div>
          </div>

          <!-- 知识库底部操作 -->
          <div class="px-6 py-4 border-t border-morandi flex-shrink-0">
            <div class="mb-4">
              <div class="flex items-center justify-between mb-2">
                <span class="text-sm" style="color: var(--morandi-text-secondary)">
                  已选择 {{ selectedKnowledgeBases.length }} 个知识库
                </span>
                <button v-if="selectedKnowledgeBases.length > 0" @click="clearAllSelections"
                  class="text-sm transition-colors morandi-btn morandi-btn-ghost text-xs px-2 py-0.5"
                  style="color: var(--morandi-error)">
                  清空选择
                </button>
              </div>
              <div v-if="selectedKnowledgeBases.length > 0" class="flex flex-wrap gap-2">
                <div v-for="kb in selectedKnowledgeBases" :key="kb.id"
                  class="px-3 py-1 rounded-full text-sm flex items-center gap-1.5"
                  style="background: var(--morandi-bg-subtle); color: var(--morandi-primary)">
                  <span>{{ kb.name }}</span>
                  <button @click="removeFromSelection(kb)" class="transition-colors p-0.5"
                    style="color: var(--morandi-primary)"
                    @mouseenter="(e: Event) => (e.target as HTMLElement).style.opacity = '0.6'"
                    @mouseleave="(e: Event) => (e.target as HTMLElement).style.opacity = '1'">
                    <X class="w-3 h-3" />
                  </button>
                </div>
              </div>
              <div v-else class="text-sm" style="color: var(--morandi-text-faint)">
                未选择任何知识库
              </div>
            </div>
            <div class="flex justify-end gap-3">
              <button @click="closeKnowledgeModal" class="morandi-btn morandi-btn-ghost">取消</button>
              <button @click="confirmSelection" class="morandi-btn morandi-btn-primary">确认选择</button>
            </div>
          </div>
        </div>

        <!-- 文件管理面板 -->
        <div v-if="showFileManager"
          class="w-1/2 flex flex-col animate-slide-in-right border-l border-morandi"
          style="background: var(--morandi-surface)">
          <div class="px-6 py-4 border-b border-morandi flex-shrink-0">
            <div class="flex items-center justify-between mb-4">
              <div class="flex items-center">
                <button @click="closeFileManager" class="p-1.5 rounded-[var(--radius-sm)] mr-3 morandi-btn morandi-btn-ghost">
                  <ChevronLeft class="w-5 h-5" />
                </button>
                <h3 class="serif text-lg font-semibold" style="color: var(--morandi-text)">文件管理</h3>
              </div>
              <div class="flex gap-2">
                <button @click="triggerFileUpload" :disabled="isUploading"
                  :class="['morandi-btn morandi-btn-primary text-sm px-3 py-1.5 flex items-center gap-1.5', isUploading ? 'opacity-60' : '']">
                  <Upload class="w-4 h-4" />
                  <span>{{ isUploading ? '上传中...' : '上传文件' }}</span>
                </button>
              </div>
            </div>

            <div v-if="isUploading && uploadProgress > 0" class="mb-4">
              <div class="flex justify-between text-sm mb-2" style="color: var(--morandi-text-secondary)">
                <span>上传进度</span>
                <span>{{ Math.round(uploadProgress) }}%</span>
              </div>
              <div class="w-full rounded-full h-1.5" style="background: var(--morandi-input-bg)">
                <div class="h-1.5 rounded-full transition-all duration-300"
                  :style="{ width: uploadProgress + '%', background: 'var(--morandi-primary)' }"></div>
              </div>
            </div>

            <div v-if="currentFileManagerKB" class="rounded-[var(--radius-md)] px-3 py-2.5" style="background: var(--morandi-input-bg)">
              <div class="flex items-center">
                <Database class="w-4 h-4 mr-2" style="color: var(--morandi-tag-gold)" />
                <span class="font-medium text-sm" style="color: var(--morandi-text)">{{ currentFileManagerKB.name }}</span>
              </div>
            </div>
          </div>

          <div class="flex-1 overflow-y-auto morandi-scroll transparent-scrollbar p-6">
            <div v-if="currentFiles.length === 0" class="text-center py-12">
              <FileText class="w-14 h-14 mx-auto mb-3" style="color: var(--morandi-text-faint)" />
              <p class="text-sm font-medium" style="color: var(--morandi-text-muted)">暂无文件</p>
              <p class="text-xs mt-1" style="color: var(--morandi-text-faint)">点击上传按钮添加文档</p>
            </div>
            <div v-else class="space-y-2.5">
              <div v-for="file in currentFiles" :key="file.id"
                class="morandi-card rounded-[var(--radius-md)] p-4 transition-all duration-200 hover:bg-morandi-surface-raised">
                <div class="flex items-center justify-between">
                  <div class="flex items-center flex-1 min-w-0">
                    <FileText class="w-7 h-7 mr-3 flex-shrink-0" style="color: var(--morandi-primary)" />
                    <div class="flex-1 min-w-0">
                      <h4 class="font-medium text-sm truncate" style="color: var(--morandi-text)">{{ file.name }}</h4>
                      <div class="flex items-center text-xs mt-1" style="color: var(--morandi-text-faint)">
                        <span>{{ formatFileSize(file.size) }}</span>
                        <span class="mx-1.5">&middot;</span>
                        <span>{{ file.run || '未知' }}</span>
                      </div>
                    </div>
                  </div>
                  <div class="flex items-center ml-3 flex-shrink-0">
                    <button @click="deleteFile(file)" class="p-1.5 rounded-[var(--radius-sm)] transition-colors"
                      style="color: var(--morandi-text-muted)"
                      @mouseenter="(e: Event) => (e.target as HTMLElement).style.color = 'var(--morandi-error)'"
                      @mouseleave="(e: Event) => (e.target as HTMLElement).style.color = 'var(--morandi-text-muted)'">
                      <Trash2 class="w-4 h-4" />
                    </button>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 创建知识库弹窗 -->
    <div v-if="showCreateKnowledgeForm"
      class="fixed inset-0 z-[60] flex items-center justify-center"
      style="background: var(--morandi-overlay)"
      @click.self="showCreateKnowledgeForm = false">
      <div class="animate-modal-in morandi-card-elevated rounded-[var(--radius-xl)] w-[440px] p-6">
        <h3 class="serif text-lg font-semibold mb-5" style="color: var(--morandi-text)">创建知识库</h3>
        <div class="space-y-4">
          <div>
            <label class="block text-sm font-medium mb-1.5" style="color: var(--morandi-text-secondary)">
              <span style="color: var(--morandi-error)">*</span> 知识库名称
            </label>
            <input v-model="newKnowledgeBase.name" type="text" placeholder="请输入知识库名称" maxlength="20"
              class="w-full morandi-input px-4 py-2.5 rounded-[var(--radius-md)]" />
            <div class="text-xs mt-1 text-right" style="color: var(--morandi-text-faint)">{{ newKnowledgeBase.name.length }}/20</div>
          </div>
          <div>
            <label class="block text-sm font-medium mb-1.5" style="color: var(--morandi-text-secondary)">知识库描述</label>
            <textarea v-model="newKnowledgeBase.description" placeholder="请输入知识库描述" maxlength="200" rows="3"
              class="w-full morandi-input px-4 py-2.5 rounded-[var(--radius-md)] resize-none" />
            <div class="text-xs mt-1 text-right" style="color: var(--morandi-text-faint)">{{ newKnowledgeBase.description.length }}/200</div>
          </div>
        </div>
        <div class="flex justify-end gap-3 mt-6">
          <button @click="showCreateKnowledgeForm = false" class="morandi-btn morandi-btn-ghost">取消</button>
          <button @click="createKnowledgeBase" :disabled="!newKnowledgeBase.name.trim()"
            :class="['morandi-btn morandi-btn-primary', newKnowledgeBase.name.trim() ? '' : 'opacity-40 cursor-not-allowed']">
            创建
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, computed } from 'vue'
import { useRouter } from 'vue-router'
import {
  Bot, LogOut, Settings, RotateCcw, MessageCircle,
  Database, FolderOpen, Check, X, Plus, List, LayoutGrid,
  Trash2, ChevronLeft, Upload, FileText, Mic, PhoneOff,
  Search, Download, Zap
} from 'lucide-vue-next'
import ChatMessages from '../components/ChatMessages.vue'
import ThemeToggle from '../components/ThemeToggle.vue'
import { useTheme } from '../composables/useTheme'
import { useMessageSearch } from '../composables/useMessageSearch'
import { useQuickCommands } from '../composables/useQuickCommands'
import { exportChatToMarkdown, exportChatToJson } from '../utils/exportChat'
import { useWebSocket } from '../utils/websocket'
import { useWebRTC } from '../composables/useWebRTC'
import { fetchAssistants, createAssistant, updateAssistant, deleteAssistant, fetchAssistant, fetchKnowledgeConfig } from '../api/assistant'
import type { Assistant, DisplayMessage, KnowledgeBase, RAGFlowConfig, AsrDeltaData } from '../types'

const { themeMode, setTheme } = useTheme()
const router = useRouter()
const assistants = ref<Assistant[]>([])
const selectedAssistant = ref<Assistant | null>(null)

// 用户信息（从 localStorage 读取，避免模板中直接调用）
const userName = typeof localStorage !== 'undefined'
  ? (localStorage.getItem('username') || '用户')
  : '用户'
const userInitial = userName.charAt(0).toUpperCase()

const showModal = ref(false)
const showDeleteModal = ref(false)
const deleteTarget = ref<Assistant | null>(null)

const formData = ref({
  name: '',
  description: '',
  personality: '',
})

const ragflowConfig = ref<RAGFlowConfig>({ endpoint: '', apiKey: '' })

let ws: ReturnType<typeof useWebSocket> | null = null
let voiceWs: ReturnType<typeof useWebSocket> | null = null
const isFirstOfStream = ref(true)

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

// 消息搜索
const { searchQuery, isSearchActive, searchResults, searchMessages, highlightKeyword, clearSearch, openSearch } = useMessageSearch()

// 快捷命令
const { quickCommands } = useQuickCommands()
const showQuickCommands = ref(false)

// 发送快捷命令
const sendQuickCommand = (cmd: { id: string; label: string; prompt: string }) => {
  inputText.value = cmd.prompt
  showQuickCommands.value = false
  sendMessage()
}

// 导出对话
const handleExport = () => {
  const format = confirm('导出为 Markdown 格式？\n\n取消则导出为 JSON 格式')
  if (format) {
    exportChatToMarkdown(messages.value, selectedAssistant.value?.name || '对话')
  } else {
    exportChatToJson(messages.value, selectedAssistant.value?.name || '对话')
  }
}

const webrtc = useWebRTC()
const audioLevel = webrtc.audioLevel
const voiceCallActive = ref(false)
const asrText = ref('')

const showSettings = ref(false)
const settingsAssistant = ref<Assistant | null>(null)
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
  if (!personalityChanged.value) return

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

const getAssistantType = (bot: Assistant): string => {
  const name = (bot.name || '').toLowerCase()
  const description = (bot.description || '').toLowerCase()

  if (name.includes('客服') || description.includes('客服')) return '客服助手'
  if (name.includes('翻译') || description.includes('翻译')) return '翻译助手'
  if (name.includes('编程') || name.includes('代码') || description.includes('编程') || description.includes('代码')) return '编程助手'
  if (name.includes('写作') || description.includes('写作')) return '写作助手'
  if (name.includes('教学') || name.includes('教育') || description.includes('教学') || description.includes('教育')) return '教学助手'

  return '通用助手'
}

const getConversationCount = (_id: string): number => {
  // 聊天记录已迁移至 records 表，不再存储在 assistant 对象上
  // 如需显示数量，可调用 record count 接口
  return 0
}

const loadAssistants = async () => {
  try {
    assistants.value = await fetchAssistants()
  } catch (error) {
    console.error('获取助手列表失败:', error)
  }
}

const selectAssistant = (bot: Assistant) => {
  if (selectedAssistant.value?.id === bot.id) return

  if (ws) {
    ws.send({ type: 'close' })
    ws.close()
    ws = null
  }
  if (voiceWs) {
    voiceWs.send({ type: 'hangup' })
    voiceWs.close()
    voiceWs = null
  }
  webrtc.hangup()
  voiceCallActive.value = false

  selectedAssistant.value = bot
  personalityText.value = bot.personality || '这是一个默认的智能助手，擅长解答用户问题，提供准确、有用的信息。'
  originalPersonality.value = personalityText.value

  restoreKnowledgeBaseSelection()
  connectWebSocket()
  resetChat()
}

const openModal = () => {
  formData.value = { name: '', description: '', personality: '' }
  showModal.value = true
}

const closeModal = () => {
  showModal.value = false
  formData.value = { name: '', description: '', personality: '' }
}

const addAssistant = async () => {
  if (!formData.value.name.trim()) {
    alert('请输入助手名称')
    return
  }

  try {
    await createAssistant({
      name: formData.value.name,
      description: formData.value.description,
      personality: formData.value.personality,
    })
    await loadAssistants()
    closeModal()
  } catch (error) {
    console.error('添加助手失败:', error)
    alert((error as Error).message)
  }
}

const confirmDelete = (bot: Assistant) => {
  deleteTarget.value = bot
  showDeleteModal.value = true
}

const cancelDelete = () => {
  showDeleteModal.value = false
  deleteTarget.value = null
}

const doDelete = async () => {
  if (!deleteTarget.value) return

  try {
    await deleteAssistant(deleteTarget.value.id)
    if (selectedAssistant.value?.id === deleteTarget.value.id) {
      if (ws) {
        ws.send({ type: 'close' })
        ws.close()
        ws = null
      }
      selectedAssistant.value = null
      messages.value = []
    }
    await loadAssistants()
    cancelDelete()
  } catch (error) {
    console.error('删除助手失败:', error)
    alert((error as Error).message)
  }
}

const openSettingsModal = (bot: Assistant) => {
  settingsAssistant.value = bot
  personalityText.value = bot.personality || '这是一个默认的智能助手，擅长解答用户问题，提供准确、有用的信息。'
  originalPersonality.value = personalityText.value
  showSettings.value = true
}

const closeSettingsModal = () => {
  showSettings.value = false
  settingsAssistant.value = null
}

const startVoiceCall = async () => {
  if (!selectedAssistant.value?.id) return

  try {
    asrText.value = ''
    const offerSDP = await webrtc.createOffer()

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const host = window.location.host
    const wsUrl = `${protocol}//${host}/ws-voice/${selectedAssistant.value.id}`

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
            showNotification('语音通话已连接！', 'success')
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
  if (!selectedAssistant.value?.id) return

  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  const host = window.location.host
  const wsUrl = `${protocol}//${host}/ws/${selectedAssistant.value.id}`

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
    { role: 'assistant', text: `您好，我是${selectedAssistant.value?.name || '智能助手'}，专门解答相关问题。` },
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

const handleScrollStateChange = (state: { userScrolledManually: boolean; isAtBottom: boolean }) => {
  if (state.isAtBottom && !state.userScrolledManually) {
    // 用户已滚动到底部，新消息到达时将自动滚动
  }
}

const handleLogout = () => {
  if (ws) {
    ws.send({ type: 'close' })
    ws.close()
    ws = null
  }
  if (voiceWs) {
    voiceWs.send({ type: 'hangup' })
    voiceWs.close()
    voiceWs = null
  }
  webrtc.hangup()
  localStorage.removeItem('token')
  localStorage.removeItem('userId')
  localStorage.removeItem('username')
  router.push('/login')
}

const loadRAGFlowConfig = async () => {
  try {
    ragflowConfig.value = await fetchKnowledgeConfig()
  } catch (error) {
    console.error('加载RAGFlow配置失败:', error)
  }
}

const loadKnowledgeBases = async () => {
  if (!ragflowConfig.value.endpoint || !ragflowConfig.value.apiKey) return

  try {
    const response = await fetch(`${ragflowConfig.value.endpoint}/api/v1/datasets?page=1&page_size=10`, {
      method: 'GET',
      headers: { 'Authorization': ragflowConfig.value.apiKey },
    })

    if (!response.ok) throw new Error(`获取数据集列表失败：${response.status}`)

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
    console.error('获取知识库列表失败：', error)
    showNotification(`获取知识库列表失败：${(error as Error).message}`, 'error')
  }
}

const restoreKnowledgeBaseSelection = () => {
  if (!selectedAssistant.value?.id) return
  try {
    const saved = localStorage.getItem(`knowledgeBaseSelection_${selectedAssistant.value.id}`)
    if (saved) {
      const selectionData = JSON.parse(saved)
      const isExpired = Date.now() - selectionData.timestamp > 24 * 60 * 60 * 1000
      if (!isExpired) {
        currentKnowledgeBase.value = selectionData.currentKnowledgeBase
        selectedKnowledgeBases.value = selectionData.selectedKnowledgeBases || []
      } else {
        localStorage.removeItem(`knowledgeBaseSelection_${selectedAssistant.value.id}`)
      }
    }
  } catch (error) {
    console.error('恢复知识库选择状态失败：', error)
  }
}

const saveKnowledgeBaseSelection = () => {
  if (!selectedAssistant.value?.id) return
  const selectionData = {
    currentKnowledgeBase: currentKnowledgeBase.value,
    selectedKnowledgeBases: selectedKnowledgeBases.value,
    timestamp: Date.now(),
  }
  localStorage.setItem(`knowledgeBaseSelection_${selectedAssistant.value.id}`, JSON.stringify(selectionData))
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
  if (selectedAssistant.value?.id) {
    localStorage.removeItem(`knowledgeBaseSelection_${selectedAssistant.value.id}`)
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

    if (!response.ok) throw new Error(`创建数据集失败：${response.status}`)

    const result = await response.json()
    if (result.code === 0) {
      showNotification('知识库创建成功！', 'success')
      await loadKnowledgeBases()
      newKnowledgeBase.value = { name: '', description: '' }
      showCreateKnowledgeForm.value = false
      showKnowledgeModal.value = true
    } else {
      throw new Error(result.message || '创建知识库失败')
    }
  } catch (error) {
    console.error('创建知识库失败：', error)
    showNotification(`创建知识库失败：${(error as Error).message}`, 'error')
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

    if (!response.ok) throw new Error(`删除数据集失败：${response.status}`)

    const result = await response.json()
    if (result.code === 0) {
      showNotification('知识库删除成功！', 'success')
      await loadKnowledgeBases()
      if (currentKnowledgeBase.value?.id === kb.id) {
        currentKnowledgeBase.value = null
      }
      selectedKnowledgeBases.value = selectedKnowledgeBases.value.filter(selected => selected.id !== kb.id)
    }
  } catch (error) {
    console.error('删除知识库失败：', error)
    showNotification(`删除知识库失败：${(error as Error).message}`, 'error')
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
        console.warn('文档解析失败，但文件已成功上传', parseError)
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
  await loadAssistants()
  await loadKnowledgeBases()
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
  animation: slide-in-right 0.4s cubic-bezier(0.16, 1, 0.3, 1);
}

.notification-enter-active,
.notification-leave-active {
  transition: all 0.25s cubic-bezier(0.16, 1, 0.3, 1);
}

.notification-enter-from {
  opacity: 0;
  transform: translateX(24px);
}

.notification-leave-to {
  opacity: 0;
  transform: translateX(24px);
}
</style>
