<template>
  <div class="morandi-body theme-transition h-screen w-full flex overflow-hidden antialiased">
    <!-- 通知提示 -->
    <transition name="notification">
      <div
        v-if="notification.show"
        class="notification-toast"
        :class="{
          'toast-success': notification.type === 'success',
          'toast-error': notification.type === 'error',
          'toast-warning': notification.type === 'warning',
          'toast-info': notification.type === 'info'
        }"
      >
        {{ notification.message }}
      </div>
    </transition>

    <!-- 左侧边栏 -->
    <aside class="sidebar w-72 flex-shrink-0 flex flex-col bg-morandi-warm border-r border-morandi">
      <!-- 品牌标题区 -->
      <div class="brand-header px-6 py-5 border-b border-morandi">
        <h1 class="serif text-xl font-semibold text-morandi-text">云谕助手</h1>
        <p class="text-xs mt-0.5 text-morandi-text-muted">智能对话工作台</p>
      </div>

      <!-- 新建助手按钮 -->
      <div class="px-4 py-4">
        <button
          @click="openModal"
          class="morandi-btn morandi-btn-primary w-full flex items-center justify-center gap-2 py-2.5"
        >
          <Plus class="w-4 h-4" />
          <span>新建助手</span>
        </button>
      </div>

      <!-- 助手列表 -->
      <div class="flex-1 min-h-0 overflow-y-auto morandi-scroll transparent-scrollbar px-3 pb-4">
        <div v-if="assistants.length === 0" class="empty-state text-center py-12 px-4">
          <Bot class="w-10 h-10 mx-auto mb-3 text-morandi-text-faint" />
          <p class="text-sm text-morandi-text-muted">暂无助手</p>
          <p class="text-xs mt-1 text-morandi-text-faint">点击上方按钮创建</p>
        </div>

        <div
          v-for="(bot, index) in assistants"
          :key="bot.id"
          @click="selectAssistant(bot)"
          class="assistant-item group relative flex items-center gap-3 px-3 py-3 mb-1 rounded-md cursor-pointer transition-all duration-200"
          :class="selectedAssistant?.id === bot.id ? 'bg-morandi-bg-subtle item-active' : 'hover:bg-morandi-surface'"
        >
          <!-- 头像 -->
          <div
            class="avatar w-9 h-9 rounded-full flex items-center justify-center flex-shrink-0"
            :style="{ background: ['var(--morandi-tag-blue)', 'var(--morandi-tag-purple)', 'var(--morandi-tag-gold)'][index % 3] }"
          >
            <Bot class="w-4 h-4 text-white" />
          </div>
          <!-- 名称和类型 -->
          <div class="flex-1 min-w-0">
            <div class="text-sm font-medium truncate text-morandi-text">{{ bot.name || '小助手' }}</div>
            <div class="flex items-center gap-2 mt-0.5">
              <span class="text-xs text-morandi-text-muted">{{ getAssistantType(bot) }}</span>
              <span
                v-if="getConversationCount(bot.id) > 0"
                class="count-badge text-xs px-1.5 py-0.5 rounded-full bg-morandi-bg-subtle text-morandi-text-secondary"
              >
                {{ getConversationCount(bot.id) }}
              </span>
            </div>
          </div>
          <!-- hover 操作按钮 -->
          <div class="item-actions flex items-center gap-1 flex-shrink-0">
            <button
              class="action-btn p-1 rounded-sm transition-colors text-morandi-text-muted hover:text-morandi-text-secondary"
              @click.stop="openSettingsModal(bot)"
            >
              <Settings class="w-3.5 h-3.5" />
            </button>
            <button
              class="action-btn p-1 rounded-sm transition-colors text-morandi-text-muted hover:text-morandi-error"
              @click.stop="confirmDelete(bot)"
            >
              <Trash2 class="w-3.5 h-3.5" />
            </button>
          </div>
        </div>
      </div>

      <!-- 底部用户区 -->
      <div class="user-bar px-4 py-3 border-t border-morandi">
        <div class="flex items-center justify-between">
          <div class="user-info flex items-center gap-2">
            <div class="user-avatar w-7 h-7 rounded-full flex items-center justify-center text-xs font-medium text-white bg-morandi-primary">
              {{ userInitial }}
            </div>
            <span class="text-sm text-morandi-text">{{ userName }}</span>
          </div>
          <button
            @click="handleLogout"
            class="morandi-btn morandi-btn-ghost text-xs px-2 py-1 flex items-center gap-1"
          >
            <LogOut class="w-3.5 h-3.5" />
            <span>登出</span>
          </button>
        </div>
      </div>
    </aside>

    <!-- 右侧主区域 -->
    <main class="main-panel flex-1 flex flex-col min-w-0 bg-morandi-base">
      <!-- 顶部工具栏 -->
      <header class="toolbar h-14 flex items-center justify-between px-6 border-b border-morandi bg-morandi-subtle flex-shrink-0">
        <div class="toolbar-left flex items-center gap-3">
          <h2 v-if="selectedAssistant" class="serif text-lg font-medium text-morandi-text">
            {{ selectedAssistant.name }}
          </h2>
          <span v-else class="text-sm text-morandi-text-muted">请选择一个助手开始对话</span>
        </div>
        <div class="toolbar-right flex items-center gap-2 relative">
          <button
            v-if="selectedAssistant"
            @click="openSettingsModal(selectedAssistant)"
            class="morandi-btn morandi-btn-ghost px-3 py-1.5 text-sm flex items-center gap-1.5"
          >
            <Settings class="w-4 h-4" />
            <span>设置</span>
          </button>
          <button
            v-if="selectedAssistant"
            @click="resetChat"
            class="morandi-btn morandi-btn-ghost px-3 py-1.5 text-sm flex items-center gap-1.5"
          >
            <RotateCcw class="w-4 h-4" />
            <span>刷新</span>
          </button>
          <!-- 操作按钮组 -->
          <div class="func-btns flex items-center gap-1.5">
            <!-- 消息搜索 -->
            <button
              v-if="messages.length > 0 && !isSearchActive"
              @click="openSearch()"
              class="morandi-btn morandi-btn-ghost morandi-btn-sm"
              title="搜索消息"
            >
              <Search class="w-3.5 h-3.5" />
            </button>
            <!-- 导出对话 -->
            <button
              v-if="messages.length > 0"
              @click="handleExport"
              class="morandi-btn morandi-btn-ghost morandi-btn-sm"
              title="导出对话"
            >
              <Download class="w-3.5 h-3.5" />
            </button>
            <!-- 快捷命令 -->
            <button
              @click="showQuickCommands = !showQuickCommands"
              class="morandi-btn morandi-btn-ghost morandi-btn-sm"
              title="快捷命令"
            >
              <Zap class="w-3.5 h-3.5" />
            </button>
          </div>
          <!-- 快捷命令面板 -->
          <div v-if="showQuickCommands" class="cmd-panel absolute top-full right-6 mt-1 animate-modal-in">
            <div class="morandi-card-elevated p-2 rounded-xl shadow-lg z-50 min-w-[160px]">
              <div class="text-xs font-medium px-2 py-1 text-morandi-muted">快捷命令</div>
              <button
                v-for="cmd in quickCommands"
                :key="cmd.label"
                @click="sendQuickCommand(cmd)"
                class="cmd-item w-full text-left px-2 py-1.5 rounded-md text-sm transition-colors text-morandi-text hover:bg-morandi-primary-light"
              >
                {{ cmd.label }}
              </button>
            </div>
          </div>
          <ThemeToggle :model-value="themeMode" @update:model-value="setTheme" />
        </div>
      </header>

      <!-- 聊天消息区 -->
      <div class="chat-container flex-1 min-h-0 relative flex flex-col">
        <!-- 搜索框 -->
        <div v-if="isSearchActive" class="search-bar px-4 py-2 border-b border-morandi bg-morandi-subtle flex items-center gap-2 flex-shrink-0">
          <input
            v-model="searchQuery"
            placeholder="搜索消息..."
            class="morandi-input flex-1 text-sm py-1.5"
            @input="searchMessages(messages)"
          />
          <button @click="clearSearch()" class="morandi-btn morandi-btn-ghost morandi-btn-sm">
            <X class="w-3.5 h-3.5" />
          </button>
        </div>

        <!-- 空状态 -->
        <div v-if="!selectedAssistant" class="empty-home absolute inset-0 flex items-center justify-center">
          <div class="text-center animate-fade-up">
            <div class="welcome-icon w-20 h-20 rounded-full flex items-center justify-center mx-auto mb-5 morandi-card-elevated">
              <MessageCircle class="w-10 h-10 text-morandi-text-muted" />
            </div>
            <h3 class="serif text-xl mb-2 text-morandi-text-secondary">欢迎使用云谕助手</h3>
            <p class="text-sm text-morandi-text-muted">请从左侧选择一个助手开始对话</p>
          </div>
        </div>

        <template v-if="selectedAssistant">
          <!-- 语音识别提示 -->
          <div
            v-if="asrText && voiceCallActive"
            class="asr-tip mx-6 mt-3 px-4 py-2 rounded-md border text-sm bg-morandi-bg-subtle border-morandi-primary text-morandi-primary"
          >
            <span class="font-medium">语音识别：</span>{{ asrText }}
          </div>

          <!-- 聊天消息列表 -->
          <ChatMessages
            :messages="(isSearchActive ? searchResults : messages) as DisplayMessage[]"
            :auto-scroll="true"
            @scroll-state-change="handleScrollStateChange"
            class="h-full transparent-scrollbar"
          />
        </template>
      </div>

      <!-- 底部输入区 -->
      <div v-if="selectedAssistant" class="input-area border-t border-morandi p-4 bg-morandi-base flex-shrink-0">
        <div v-if="voiceCallActive" class="voice-panel flex flex-col items-center gap-4 py-2">
          <div class="voice-main flex items-center gap-4">
            <div class="mic-wrap relative">
              <Mic class="w-8 h-8 animate-pulse text-morandi-error" />
              <div class="mic-ring absolute -inset-2 rounded-full border-2 animate-ping border-morandi-error opacity-30"></div>
            </div>
            <!-- 音量波形 -->
            <div class="audio-bars flex items-center gap-1">
              <div
                v-for="i in 5"
                :key="i"
                class="bar-item w-1.5 rounded-full transition-all duration-150 bg-morandi-primary"
                :style="{
                  height: `${Math.max(4, audioLevel * 40 * (0.5 + Math.random() * 0.5))}px`,
                  opacity: audioLevel > (i - 1) * 0.2 ? 1 : 0.3
                }"
              ></div>
            </div>
          </div>
          <button
            @click="endVoiceCall"
            class="morandi-btn morandi-btn-danger px-8 py-2.5 flex items-center gap-2"
          >
            <PhoneOff class="w-4 h-4" />
            <span>挂断</span>
          </button>
        </div>

        <div v-else class="input-row flex items-center gap-3">
          <input
            v-model="inputText"
            type="text"
            class="flex-1 morandi-input h-12 px-4 rounded-lg"
            placeholder="请输入您想问的问题..."
            @keyup.enter="sendMessage"
            :disabled="isTyping"
          />
          <button
            @click="startVoiceCall"
            class="morandi-btn morandi-btn-ghost w-12 h-12 rounded-full flex items-center justify-center"
            :class="{ 'opacity-40 cursor-not-allowed': !selectedAssistant?.id }"
            :disabled="!selectedAssistant?.id"
          >
            <Mic class="w-5 h-5" />
          </button>
          <button
            @click="sendMessage"
            class="morandi-btn morandi-btn-primary w-12 h-12 rounded-full flex items-center justify-center"
            :class="{ 'opacity-40 cursor-not-allowed': !inputText.trim() || isTyping }"
            :disabled="!inputText.trim() || isTyping"
          >
            <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <line x1="22" y1="2" x2="11" y2="13"></line>
              <polygon points="22 2 15 22 11 13 2 9 22 2"></polygon>
            </svg>
          </button>
        </div>
      </div>
    </main>

    <!-- 新增助手 / 删除确认 弹窗 -->
    <div
      v-if="showModal || showDeleteModal"
      class="modal-mask fixed inset-0 z-50 flex items-center justify-center bg-morandi-overlay"
      @click.self="showModal ? closeModal() : cancelDelete()"
    >
      <div class="modal-card animate-modal-in morandi-card-elevated rounded-xl w-[420px] p-6">
        <!-- 新增助手 -->
        <div v-if="showModal">
          <h3 class="serif text-lg font-semibold mb-6 text-morandi-text">新增助手</h3>
          <div class="form-groups space-y-4">
            <div>
              <label class="block text-sm font-medium mb-1.5 text-morandi-text-secondary">助手名称</label>
              <input
                v-model="formData.name"
                type="text"
                placeholder="请输入助手名称"
                class="w-full morandi-input px-4 py-2.5 rounded-md"
              />
            </div>
            <div>
              <label class="block text-sm font-medium mb-1.5 text-morandi-text-secondary">助手描述</label>
              <textarea
                v-model="formData.description"
                placeholder="请输入助手描述（可选）"
                class="w-full morandi-input px-4 py-2.5 rounded-md resize-none"
                rows="3"
              ></textarea>
            </div>
            <div>
              <label class="block text-sm font-medium mb-1.5 text-morandi-text-secondary">系统提示词</label>
              <textarea
                v-model="formData.personality"
                placeholder="定义助手的人设和性格..."
                class="w-full morandi-input px-4 py-2.5 rounded-md resize-none"
                rows="4"
              ></textarea>
            </div>
          </div>
          <div class="flex justify-end gap-3 mt-6">
            <button @click="closeModal" class="morandi-btn morandi-btn-ghost">取消</button>
            <button @click="addAssistant()" class="morandi-btn morandi-btn-primary">确定创建</button>
          </div>
        </div>

        <!-- 删除确认 -->
        <div v-if="showDeleteModal">
          <h3 class="serif text-lg font-semibold mb-2 text-morandi-error">确认删除</h3>
          <p class="text-sm mb-6 text-morandi-text-secondary">你确定要删除这个助手吗？此操作无法撤销。</p>
          <div class="flex justify-end gap-3">
            <button @click="cancelDelete" class="morandi-btn morandi-btn-ghost">取消</button>
            <button @click="doDelete" class="morandi-btn morandi-btn-danger">确认删除</button>
          </div>
        </div>
      </div>
    </div>

    <!-- 设置弹窗 -->
    <div
      v-if="showSettings"
      class="modal-mask fixed inset-0 z-40 flex items-center justify-center bg-morandi-overlay"
      @click.self="closeSettingsModal"
    >
      <div class="modal-large animate-modal-in morandi-card-elevated rounded-xl w-[900px] max-h-[85vh] flex flex-col overflow-hidden">
        <!-- 设置头部 -->
        <div class="modal-header flex items-center justify-between px-6 py-4 border-b border-morandi flex-shrink-0">
          <h2 class="serif text-xl font-semibold text-morandi-text">助手设置</h2>
          <button @click="closeSettingsModal" class="p-1.5 rounded-md transition-colors morandi-btn morandi-btn-ghost">
            <X class="w-5 h-5" />
          </button>
        </div>

        <!-- 设置内容 -->
        <div class="modal-body flex-1 min-h-0 overflow-y-auto morandi-scroll transparent-scrollbar p-6 space-y-5">
          <!-- 助手信息卡片 -->
          <div class="info-card morandi-card rounded-lg p-5">
            <div class="flex items-center gap-3">
              <div class="avatar w-10 h-10 rounded-full flex items-center justify-center flex-shrink-0 bg-morandi-tag-blue">
                <Bot class="w-5 h-5 text-white" />
              </div>
              <div>
                <div class="font-semibold text-morandi-text">{{ settingsAssistant?.name }}</div>
                <div class="text-sm mt-0.5 text-morandi-text-muted">{{ settingsAssistant?.description }}</div>
              </div>
            </div>
          </div>

          <!-- 人设设置 -->
          <div class="setting-card morandi-card rounded-lg p-5">
            <div class="card-head flex items-center justify-between mb-4">
              <h3 class="font-semibold text-morandi-text">人设设置</h3>
              <button
                @click="savePersonality"
                :disabled="!personalityChanged"
                class="morandi-btn morandi-btn-primary text-sm px-4 py-1.5"
                :class="{ 'opacity-40 cursor-not-allowed': !personalityChanged }"
              >
                {{ saving ? '保存中...' : '保存人设' }}
              </button>
            </div>
            <textarea
              v-model="personalityText"
              @input="onPersonalityChange"
              class="w-full h-40 resize-none morandi-input rounded-md p-4 text-sm leading-relaxed"
              placeholder="请输入机器人的人设描述，例如：你是一个专业的客服助手，擅长解答用户问题..."
            ></textarea>
            <div class="text-xs mt-2 text-right text-morandi-text-muted">
              {{ personalityText.length }}/500 字符
            </div>
          </div>

          <!-- 知识库配置 -->
          <div class="setting-card morandi-card rounded-lg p-5">
            <div class="card-head flex items-center justify-between mb-4">
              <h3 class="font-semibold text-morandi-text">知识库配置</h3>
              <button
                @click="openKnowledgeModal"
                class="morandi-btn morandi-btn-primary text-sm px-4 py-1.5 flex items-center gap-1.5"
              >
                <Database class="w-4 h-4" />
                <span>管理知识库</span>
              </button>
            </div>

            <!-- 单知识库选中 -->
            <div v-if="currentKnowledgeBase" class="kb-selected morandi-card px-4 py-3 rounded-md">
              <div class="flex items-center justify-between">
                <div class="flex items-center">
                  <Database class="w-5 h-5 mr-3 text-morandi-primary" />
                  <div>
                    <div class="font-medium text-sm text-morandi-text">{{ currentKnowledgeBase.name }}</div>
                    <div class="text-xs mt-0.5 text-morandi-text-muted">
                      {{ currentKnowledgeBase.documentCount || 0 }} 个文档 · {{ currentKnowledgeBase.chunkCount || 0 }} 切片
                    </div>
                  </div>
                </div>
                <span class="status-tag text-xs px-2 py-0.5 rounded-full bg-morandi-success-bg text-morandi-success">已选择</span>
              </div>
            </div>

            <!-- 多知识库选中 -->
            <div v-else-if="selectedKnowledgeBases.length > 0" class="kb-multi morandi-card px-4 py-3 rounded-md">
              <div class="flex items-center justify-between mb-2">
                <div class="flex items-center">
                  <Database class="w-5 h-5 mr-3 text-morandi-accent" />
                  <div>
                    <div class="font-medium text-sm text-morandi-text">已选择 {{ selectedKnowledgeBases.length }} 个知识库</div>
                    <div class="text-xs text-morandi-text-muted">多知识库联合检索模式</div>
                  </div>
                </div>
                <button @click="clearMultiSelection" class="clear-btn text-xs px-2 py-1 rounded-sm bg-morandi-error-bg text-morandi-error">
                  清空
                </button>
              </div>
              <div class="selected-list space-y-1 max-h-24 overflow-y-auto transparent-scrollbar">
                <div
                  v-for="kb in selectedKnowledgeBases"
                  :key="kb.id"
                  class="selected-item flex items-center justify-between px-3 py-1.5 rounded-sm bg-morandi-input-bg"
                >
                  <div class="flex items-center">
                    <Check class="w-3 h-3 mr-2 text-morandi-primary" />
                    <span class="text-sm text-morandi-text-secondary">{{ kb.name }}</span>
                  </div>
                  <button @click.stop="removeFromSelection(kb)" class="remove-btn p-0.5 text-morandi-text-muted hover:text-morandi-error">
                    <X class="w-3 h-3" />
                  </button>
                </div>
              </div>
            </div>

            <!-- 空状态 -->
            <div v-else class="kb-empty px-4 py-8 rounded-md text-center bg-morandi-input-bg border border-dashed border-morandi-border">
              <FolderOpen class="w-8 h-8 mx-auto mb-2 text-morandi-text-faint" />
              <p class="text-sm text-morandi-text-muted">点击「管理知识库」选择或创建知识库</p>
            </div>
          </div>
        </div>

        <!-- 设置底部 -->
        <div class="modal-footer px-6 py-4 border-t border-morandi flex-shrink-0">
          <div class="flex justify-end">
            <button @click="closeSettingsModal" class="morandi-btn morandi-btn-ghost">关闭</button>
          </div>
        </div>
      </div>
    </div>

    <!-- 知识库管理弹窗 -->
    <div
      v-if="showKnowledgeModal"
      class="modal-mask fixed inset-0 z-50 flex items-center justify-center bg-morandi-overlay"
      @click.self="closeKnowledgeModal"
    >
      <div
        class="kb-modal animate-modal-in morandi-card-elevated rounded-xl flex overflow-hidden transition-all duration-500"
        :class="showFileManager ? 'w-[1400px]' : 'w-[900px]'"
      >
        <!-- 知识库列表面板 -->
        <div
          class="kb-panel transition-all duration-500 ease-in-out flex flex-col"
          :class="showFileManager ? 'w-1/2' : 'w-full'"
        >
          <!-- 知识库头部 -->
          <div class="kb-header px-6 py-5 border-b border-morandi flex-shrink-0">
            <div class="flex items-center justify-between mb-5">
              <h2 class="serif text-xl font-semibold text-morandi-text">知识库管理</h2>
              <div class="header-actions flex items-center gap-3">
                <div class="layout-switch p-0.5 flex rounded-sm bg-morandi-input-bg">
                  <button
                    @click="knowledgeBaseLayout = 'list'"
                    class="layout-btn px-2.5 py-1 rounded-sm text-sm transition-all duration-200"
                    :class="knowledgeBaseLayout === 'list' ? 'morandi-btn morandi-btn-primary' : 'text-morandi-text-muted'"
                  >
                    <List class="w-4 h-4" />
                  </button>
                  <button
                    @click="knowledgeBaseLayout = 'grid'"
                    class="layout-btn px-2.5 py-1 rounded-sm text-sm transition-all duration-200"
                    :class="knowledgeBaseLayout === 'grid' ? 'morandi-btn morandi-btn-primary' : 'text-morandi-text-muted'"
                  >
                    <LayoutGrid class="w-4 h-4" />
                  </button>
                </div>
                <button @click="closeKnowledgeModal" class="p-1.5 rounded-sm morandi-btn morandi-btn-ghost">
                  <X class="w-5 h-5" />
                </button>
              </div>
            </div>

            <button
              @click="openCreateKnowledgeForm"
              class="morandi-btn morandi-btn-primary text-sm px-4 py-2 inline-flex items-center gap-1.5"
            >
              <Plus class="w-4 h-4" />
              <span>新建知识库</span>
            </button>
          </div>

          <!-- 知识库列表 -->
          <div class="kb-list flex-1 min-h-0 overflow-y-auto morandi-scroll transparent-scrollbar p-6">
            <div
              class="list-wrap transition-all duration-300"
              :class="knowledgeBaseLayout === 'grid' ? 'grid grid-cols-2 gap-4' : 'grid grid-cols-1 gap-3'"
            >
              <div
                v-for="kb in knowledgeBases"
                :key="kb.id"
                @click="selectKnowledgeBase(kb)"
                class="kb-item morandi-card rounded-lg p-4 cursor-pointer transition-all duration-200"
                :class="[
                  knowledgeBaseLayout === 'list' ? 'flex items-center' : 'block',
                  { 'ring-2 ring-[var(--morandi-primary)] bg-morandi-bg-subtle': selectedKnowledgeBases.some(s => s.id === kb.id) }
                ]"
              >
                <template v-if="knowledgeBaseLayout === 'list'">
                  <div class="item-row flex items-center w-full">
                    <div class="check-wrap mr-3 flex-shrink-0">
                      <div
                        class="check-box w-5 h-5 rounded border-2 flex items-center justify-center transition-all duration-200"
                        :class="selectedKnowledgeBases.some(s => s.id === kb.id) ? 'bg-morandi-primary border-morandi-primary' : 'border-morandi-border'"
                      >
                        <Check v-if="selectedKnowledgeBases.some(s => s.id === kb.id)" class="w-3 h-3 text-white" />
                      </div>
                    </div>
                    <div class="item-main flex items-center flex-1 min-w-0">
                      <Database class="w-5 h-5 mr-3 flex-shrink-0 text-morandi-tag-gold" />
                      <div class="item-text flex-1 min-w-0">
                        <h3 class="font-semibold text-sm truncate text-morandi-text">{{ kb.name }}</h3>
                        <p class="text-xs truncate mt-0.5 text-morandi-text-muted">{{ kb.description || '暂无描述' }}</p>
                      </div>
                    </div>
                    <div class="item-stat flex items-center gap-3 mx-3 flex-shrink-0">
                      <div class="stat text-center">
                        <div class="font-bold text-sm text-morandi-primary">{{ kb.documentCount || 0 }}</div>
                        <div class="text-xs text-morandi-text-faint">文档</div>
                      </div>
                    </div>
                    <div class="item-op flex gap-1 flex-shrink-0">
                      <button @click.stop="selectKnowledgeBaseForFileManager(kb)" class="op-btn p-1.5 rounded-sm text-morandi-text-muted hover:bg-morandi-surface">
                        <FolderOpen class="w-4 h-4" />
                      </button>
                      <button @click.stop="deleteKnowledgeBase(kb)" class="op-btn p-1.5 rounded-sm text-morandi-text-muted hover:text-morandi-error">
                        <Trash2 class="w-4 h-4" />
                      </button>
                    </div>
                  </div>
                </template>

                <template v-else>
                  <div class="grid-top flex items-start justify-between mb-3">
                    <div class="grid-left flex items-center">
                      <div class="check-wrap mr-3 flex-shrink-0">
                        <div
                          class="check-box w-5 h-5 rounded border-2 flex items-center justify-center transition-all duration-200"
                          :class="selectedKnowledgeBases.some(s => s.id === kb.id) ? 'bg-morandi-primary border-morandi-primary' : 'border-morandi-border'"
                        >
                          <Check v-if="selectedKnowledgeBases.some(s => s.id === kb.id)" class="w-3 h-3 text-white" />
                        </div>
                      </div>
                      <Database class="w-7 h-7 mr-3 text-morandi-tag-gold" />
                      <div class="grid-text">
                        <h3 class="font-semibold text-morandi-text">{{ kb.name }}</h3>
                        <p class="text-sm mt-0.5 text-morandi-text-secondary">{{ kb.description || '暂无描述' }}</p>
                      </div>
                    </div>
                    <div class="grid-op flex gap-1.5">
                      <button @click.stop="selectKnowledgeBaseForFileManager(kb)" class="op-btn p-1.5 rounded-sm text-morandi-text-muted hover:bg-morandi-surface">
                        <FolderOpen class="w-4 h-4" />
                      </button>
                      <button @click.stop="deleteKnowledgeBase(kb)" class="op-btn p-1.5 rounded-sm text-morandi-text-muted hover:text-morandi-error">
                        <Trash2 class="w-4 h-4" />
                      </button>
                    </div>
                  </div>
                  <div class="grid-bottom flex justify-between text-sm pt-3 border-t border-morandi-divider">
                    <div class="stat text-center">
                      <div class="font-bold text-morandi-primary">{{ kb.documentCount || 0 }}</div>
                      <div class="text-xs text-morandi-text-faint">文档数</div>
                    </div>
                    <div class="stat text-center">
                      <div class="font-bold text-morandi-accent">{{ kb.chunkCount || 0 }}</div>
                      <div class="text-xs text-morandi-text-faint">切片数</div>
                    </div>
                  </div>
                </template>
              </div>
            </div>
          </div>

          <!-- 知识库底部操作 -->
          <div class="kb-footer px-6 py-4 border-t border-morandi flex-shrink-0">
            <div class="selection-info mb-4">
              <div class="flex items-center justify-between mb-2">
                <span class="text-sm text-morandi-text-secondary">
                  已选择 {{ selectedKnowledgeBases.length }} 个知识库
                </span>
                <button
                  v-if="selectedKnowledgeBases.length > 0"
                  @click="clearAllSelections"
                  class="text-xs morandi-btn morandi-btn-ghost px-2 py-0.5 text-morandi-error"
                >
                  清空选择
                </button>
              </div>
              <div v-if="selectedKnowledgeBases.length > 0" class="tag-list flex flex-wrap gap-2">
                <div
                  v-for="kb in selectedKnowledgeBases"
                  :key="kb.id"
                  class="tag-item px-3 py-1 rounded-full text-sm flex items-center gap-1.5 bg-morandi-bg-subtle text-morandi-primary"
                >
                  <span>{{ kb.name }}</span>
                  <button @click="removeFromSelection(kb)" class="tag-close p-0.5 hover:opacity-60">
                    <X class="w-3 h-3" />
                  </button>
                </div>
              </div>
              <div v-else class="text-sm text-morandi-text-faint">未选择任何知识库</div>
            </div>
            <div class="btn-group flex justify-end gap-3">
              <button @click="closeKnowledgeModal" class="morandi-btn morandi-btn-ghost">取消</button>
              <button @click="confirmSelection" class="morandi-btn morandi-btn-primary">确认选择</button>
            </div>
          </div>
        </div>

        <!-- 文件管理面板 -->
        <div v-if="showFileManager" class="file-panel w-1/2 flex flex-col animate-slide-in-right border-l border-morandi bg-morandi-surface">
          <div class="file-header px-6 py-4 border-b border-morandi flex-shrink-0">
            <div class="flex items-center justify-between mb-4">
              <div class="file-title flex items-center">
                <button @click="closeFileManager" class="p-1.5 rounded-sm mr-3 morandi-btn morandi-btn-ghost">
                  <ChevronLeft class="w-5 h-5" />
                </button>
                <h3 class="serif text-lg font-semibold text-morandi-text">文件管理</h3>
              </div>
              <div class="file-op flex gap-2">
                <button
                  @click="triggerFileUpload"
                  :disabled="isUploading"
                  class="morandi-btn morandi-btn-primary text-sm px-3 py-1.5 flex items-center gap-1.5"
                  :class="{ 'opacity-60': isUploading }"
                >
                  <Upload class="w-4 h-4" />
                  <span>{{ isUploading ? '上传中...' : '上传文件' }}</span>
                </button>
              </div>
            </div>

            <div v-if="isUploading && uploadProgress > 0" class="progress-wrap mb-4">
              <div class="flex justify-between text-sm mb-2 text-morandi-text-secondary">
                <span>上传进度</span>
                <span>{{ Math.round(uploadProgress) }}%</span>
              </div>
              <div class="progress-bar w-full rounded-full h-1.5 bg-morandi-input-bg">
                <div
                  class="progress-inner h-1.5 rounded-full transition-all duration-300 bg-morandi-primary"
                  :style="{ width: uploadProgress + '%' }"
                ></div>
              </div>
            </div>

            <div v-if="currentFileManagerKB" class="kb-info rounded-md px-3 py-2.5 bg-morandi-input-bg">
              <div class="flex items-center">
                <Database class="w-4 h-4 mr-2 text-morandi-tag-gold" />
                <span class="font-medium text-sm text-morandi-text">{{ currentFileManagerKB.name }}</span>
              </div>
            </div>
          </div>

          <div class="file-list flex-1 overflow-y-auto morandi-scroll transparent-scrollbar p-6">
            <div v-if="currentFiles.length === 0" class="empty-state text-center py-12">
              <FileText class="w-14 h-14 mx-auto mb-3 text-morandi-text-faint" />
              <p class="text-sm font-medium text-morandi-text-muted">暂无文件</p>
              <p class="text-xs mt-1 text-morandi-text-faint">点击上传按钮添加文档</p>
            </div>
            <div v-else class="file-items space-y-2.5">
              <div
                v-for="file in currentFiles"
                :key="file.id"
                class="file-item morandi-card rounded-md p-4 transition-all duration-200 hover:bg-morandi-surface-raised"
              >
                <div class="flex items-center justify-between">
                  <div class="file-main flex items-center flex-1 min-w-0">
                    <FileText class="w-7 h-7 mr-3 flex-shrink-0 text-morandi-primary" />
                    <div class="file-info flex-1 min-w-0">
                      <h4 class="font-medium text-sm truncate text-morandi-text">{{ file.name }}</h4>
                      <div class="file-meta flex items-center text-xs mt-1 text-morandi-text-faint">
                        <span>{{ formatFileSize(file.size) }}</span>
                        <span class="mx-1.5">&middot;</span>
                        <span>{{ file.run || '未知' }}</span>
                      </div>
                    </div>
                  </div>
                  <div class="file-op ml-3 flex-shrink-0">
                    <button @click="deleteFile(file)" class="op-btn p-1.5 rounded-sm text-morandi-text-muted hover:text-morandi-error">
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
    <div
      v-if="showCreateKnowledgeForm"
      class="modal-mask fixed inset-0 z-[60] flex items-center justify-center bg-morandi-overlay"
      @click.self="showCreateKnowledgeForm = false"
    >
      <div class="modal-small animate-modal-in morandi-card-elevated rounded-xl w-[440px] p-6">
        <h3 class="serif text-lg font-semibold mb-5 text-morandi-text">创建知识库</h3>
        <div class="form-groups space-y-4">
          <div>
            <label class="block text-sm font-medium mb-1.5 text-morandi-text-secondary">
              <span class="text-morandi-error">*</span> 知识库名称
            </label>
            <input
              v-model="newKnowledgeBase.name"
              type="text"
              placeholder="请输入知识库名称"
              maxlength="20"
              class="w-full morandi-input px-4 py-2.5 rounded-md"
            />
            <div class="text-xs mt-1 text-right text-morandi-text-faint">{{ newKnowledgeBase.name.length }}/20</div>
          </div>
          <div>
            <label class="block text-sm font-medium mb-1.5 text-morandi-text-secondary">知识库描述</label>
            <textarea
              v-model="newKnowledgeBase.description"
              placeholder="请输入知识库描述"
              maxlength="200"
              rows="3"
              class="w-full morandi-input px-4 py-2.5 rounded-md resize-none"
            ></textarea>
            <div class="text-xs mt-1 text-right text-morandi-text-faint">{{ newKnowledgeBase.description.length }}/200</div>
          </div>
        </div>
        <div class="flex justify-end gap-3 mt-6">
          <button @click="showCreateKnowledgeForm = false" class="morandi-btn morandi-btn-ghost">取消</button>
          <button
            @click="createKnowledgeBase"
            :disabled="!newKnowledgeBase.name.trim()"
            class="morandi-btn morandi-btn-primary"
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
import { fetchAssistants, createAssistant, deleteAssistant, fetchKnowledgeConfig } from '../api/assistant'
import type { Assistant, DisplayMessage, KnowledgeBase, RAGFlowConfig, AsrDeltaData } from '../types'

// 主题与路由
const { themeMode, setTheme } = useTheme()
const router = useRouter()

// 助手数据
const assistants = ref<Assistant[]>([])
const selectedAssistant = ref<Assistant | null>(null)

// 用户信息
const userName = typeof localStorage !== 'undefined'
  ? (localStorage.getItem('username') || '用户')
  : '用户'
const userInitial = userName.charAt(0).toUpperCase()

// 弹窗状态
const showModal = ref(false)
const showDeleteModal = ref(false)
const deleteTarget = ref<Assistant | null>(null)
const formData = ref({ name: '', description: '', personality: '' })

// RAG 配置
const ragflowConfig = ref<RAGFlowConfig>({ endpoint: '', apiKey: '' })

// WebSocket 实例
let ws: ReturnType<typeof useWebSocket> | null = null
let voiceWs: ReturnType<typeof useWebSocket> | null = null
const isFirstOfStream = ref(true)

// 聊天状态
const inputText = ref('')
const isTyping = ref(false)
const messages = ref<DisplayMessage[]>([])

// 全局通知
const notification = ref({
  show: false,
  message: '',
  type: 'info' as 'success' | 'error' | 'warning' | 'info',
})
const showNotification = (message: string, type: 'success' | 'error' | 'warning' | 'info' = 'info') => {
  notification.value = { show: true, message, type }
  setTimeout(() => notification.value.show = false, 3000)
}

// 消息搜索
const { searchQuery, isSearchActive, searchResults, searchMessages, clearSearch, openSearch } = useMessageSearch()

// 快捷命令
const { quickCommands } = useQuickCommands()
const showQuickCommands = ref(false)

// 发送快捷命令 - 修复类型：对齐 useQuickCommands 返回结构
const sendQuickCommand = (cmd: { label: string; text: string }) => {
  inputText.value = cmd.text
  showQuickCommands.value = false
  sendMessage()
}

// 导出对话
const handleExport = () => {
  const isMarkdown = confirm('导出为 Markdown 格式？\n\n取消则导出为 JSON 格式')
  const name = selectedAssistant.value?.name || '对话'
  isMarkdown
    ? exportChatToMarkdown(messages.value, name)
    : exportChatToJson(messages.value, name)
}

// 语音通话
const webrtc = useWebRTC()
const audioLevel = webrtc.audioLevel
const voiceCallActive = ref(false)
const asrText = ref('')

// 设置弹窗
const showSettings = ref(false)
const settingsAssistant = ref<Assistant | null>(null)
const personalityText = ref('')
const originalPersonality = ref('')
const saving = ref(false)

const personalityChanged = computed(() =>
  personalityText.value !== originalPersonality.value && personalityText.value.trim() !== ''
)

const onPersonalityChange = () => {
  if (personalityText.value.length > 500) {
    personalityText.value = personalityText.value.substring(0, 500)
  }
}

const savePersonality = async () => {
  if (!personalityChanged.value) return
  saving.value = true
  try {
    ws?.send({ type: 'prompt', content: personalityText.value })
    showNotification('人设保存成功！', 'success')
    originalPersonality.value = personalityText.value
  } catch (error) {
    showNotification(`保存失败：${(error as Error).message}`, 'error')
  } finally {
    saving.value = false
  }
}

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
const newKnowledgeBase = ref({ name: '', description: '' })

// 获取助手类型
const getAssistantType = (bot: Assistant): string => {
  const name = (bot.name || '').toLowerCase()
  const desc = (bot.description || '').toLowerCase()
  if (name.includes('客服') || desc.includes('客服')) return '客服助手'
  if (name.includes('翻译') || desc.includes('翻译')) return '翻译助手'
  if (name.includes('编程') || name.includes('代码') || desc.includes('编程') || desc.includes('代码')) return '编程助手'
  if (name.includes('写作') || desc.includes('写作')) return '写作助手'
  if (name.includes('教学') || name.includes('教育') || desc.includes('教学') || desc.includes('教育')) return '教学助手'
  return '通用助手'
}

const getConversationCount = (_id: string): number => 0

// 加载助手列表
const loadAssistants = async () => {
  try {
    assistants.value = await fetchAssistants()
  } catch (error) {
    console.error('获取助手列表失败:', error)
  }
}

// 选择助手
const selectAssistant = (bot: Assistant) => {
  if (selectedAssistant.value?.id === bot.id) return

  // 清理旧连接
  if (ws) { ws.send({ type: 'close' }); ws.close(); ws = null }
  if (voiceWs) { voiceWs.send({ type: 'hangup' }); voiceWs.close(); voiceWs = null }
  webrtc.hangup()
  voiceCallActive.value = false

  selectedAssistant.value = bot
  personalityText.value = bot.personality || '这是一个默认的智能助手，擅长解答用户问题，提供准确、有用的信息。'
  originalPersonality.value = personalityText.value

  restoreKnowledgeBaseSelection()
  connectWebSocket()
  resetChat()
}

// 新增助手弹窗
const openModal = () => {
  formData.value = { name: '', description: '', personality: '' }
  showModal.value = true
}
const closeModal = () => showModal.value = false

// 新增助手
const addAssistant = async () => {
  if (!formData.value.name.trim()) {
    alert('请输入助手名称')
    return
  }
  try {
    await createAssistant({ ...formData.value })
    await loadAssistants()
    closeModal()
  } catch (error) {
    alert((error as Error).message)
  }
}

// 删除助手
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
      ws?.close(); ws = null
      selectedAssistant.value = null
      messages.value = []
    }
    await loadAssistants()
    cancelDelete()
  } catch (error) {
    alert((error as Error).message)
  }
}

// 设置弹窗
const openSettingsModal = (bot: Assistant) => {
  settingsAssistant.value = bot
  personalityText.value = bot.personality || '这是一个默认的智能助手，擅长解答用户问题，提供准确、有用的信息。'
  originalPersonality.value = personalityText.value
  showSettings.value = true
}
const closeSettingsModal = () => showSettings.value = false

// 语音通话
const startVoiceCall = async () => {
  if (!selectedAssistant.value?.id) return
  try {
    asrText.value = ''
    const offerSDP = await webrtc.createOffer()
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const host = window.location.host
    const wsUrl = `${protocol}//${host}/ws-voice/${selectedAssistant.value.id}`

    voiceWs = useWebSocket(wsUrl, {
      onOpen: () => voiceWs?.send({ type: 'offer', sdp: offerSDP }),
      onMessage: async (event) => {
        try {
          const data = JSON.parse(event.data)
          if (data.type === 'webrtc_answer') {
            await webrtc.handleAnswer(data.data)
            voiceCallActive.value = true
            voiceWs?.send({ type: 'webrtc_connected' })
            showNotification('语音通话已连接！', 'success')
          } else if (data.type === 'asr_delta') {
            asrText.value = (data.data as AsrDeltaData).text
          } else if (data.type === 'assistant_message') {
            handleStreamMessage(data.data)
          } else if (data.type === 'query_end') {
            finishStreamMessage(data.data)
            asrText.value = ''
          } else if (data.type === 'hangup') {
            endVoiceCall()
            showNotification('对方已挂断', 'info')
          }
        } catch (e) {
          console.error('语音消息解析失败', e)
        }
      },
      onClose: () => voiceCallActive.value && endVoiceCall(),
      onError: (err) => {
        console.error('语音WebSocket错误', err)
        endVoiceCall()
        showNotification('语音连接失败', 'error')
      },
    })
  } catch (error) {
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

// 聊天 WebSocket
const connectWebSocket = () => {
  if (!selectedAssistant.value?.id) return
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  const host = window.location.host
  const wsUrl = `${protocol}//${host}/ws/${selectedAssistant.value.id}`

  ws = useWebSocket(wsUrl, {
    onOpen: () => {
      setTimeout(() => {
        const kbIds = currentKnowledgeBase.value
          ? [currentKnowledgeBase.value.id]
          : selectedKnowledgeBases.value.map(kb => kb.id)
        ws?.send({ type: 'selectedKbIds', ids: kbIds })
      }, 100)
    },
    onMessage: (event) => {
      try {
        const data = JSON.parse(event.data)
        if (data.type === 'assistant_message') {
          handleStreamMessage(data.data)
        } else if (data.type === 'query_end') {
          finishStreamMessage(data.data)
        }
      } catch (e) {
        console.error('消息解析失败', e)
      }
    },
    onClose: () => isTyping.value = false,
    onError: () => showNotification('聊天连接异常，请刷新重试', 'error'),
  })
}

// 流式消息处理
const handleStreamMessage = (answer: { streamEnd: boolean; segment: string }) => {
  if (answer.streamEnd) {
    isTyping.value = false
    isFirstOfStream.value = true
    return
  }
  if (isFirstOfStream.value) {
    messages.value.push({
      role: 'assistant',
      text: answer.segment,
      isStreaming: true,
    } as DisplayMessage)
    isFirstOfStream.value = false
  } else {
    const lastMsg = messages.value[messages.value.length - 1]
    if (lastMsg?.role === 'assistant') lastMsg.text += answer.segment
  }
}

// 流式消息结束
const finishStreamMessage = (queryData: { 
  message: string; 
  costTime: number; 
  knowledgebase?: string | { docCount?: number; docName?: string[] } 
}) => {
  isTyping.value = false
  isFirstOfStream.value = true
  const lastMsg = messages.value[messages.value.length - 1]
  if (lastMsg?.role === 'assistant' && lastMsg.isStreaming) {
    lastMsg.text = queryData.message || lastMsg.text
    lastMsg.isStreaming = false
    lastMsg.costTime = queryData.costTime
    // 统一转换为对象格式存储
    if (typeof queryData.knowledgebase === 'string') {
      lastMsg.knowledgebase = { docName: [queryData.knowledgebase] }
    } else {
      lastMsg.knowledgebase = queryData.knowledgebase
    }
  }
}

// 重置对话
const resetChat = () => {
  messages.value = [
    {
      role: 'assistant',
      text: `您好，我是${selectedAssistant.value?.name || '智能助手'}，专门解答相关问题。`,
    } as DisplayMessage,
  ]
  ws?.send({ type: 'resetMessage' })
}

// 发送消息
const sendMessage = () => {
  if (!inputText.value.trim() || isTyping.value) return
  const content = inputText.value.trim()
  messages.value.push({ role: 'user', text: content } as DisplayMessage)
  ws?.send({ type: 'chat', content })
  inputText.value = ''
  isTyping.value = true
  isFirstOfStream.value = true
}

const handleScrollStateChange = () => {}

// 退出登录
const handleLogout = () => {
  ws?.close()
  voiceWs?.close()
  webrtc.hangup()
  localStorage.removeItem('token')
  localStorage.removeItem('userId')
  localStorage.removeItem('username')
  router.push('/login')
}

// 加载 RAG 配置
const loadRAGFlowConfig = async () => {
  try {
    ragflowConfig.value = await fetchKnowledgeConfig()
  } catch (error) {
    console.error('加载RAGFlow配置失败:', error)
  }
}

// 加载知识库列表
const loadKnowledgeBases = async () => {
  if (!ragflowConfig.value.endpoint || !ragflowConfig.value.apiKey) return
  try {
    const res = await fetch(`${ragflowConfig.value.endpoint}/api/v1/datasets?page=1&page_size=10`, {
      headers: { Authorization: ragflowConfig.value.apiKey },
    })
    if (!res.ok) throw new Error(`获取数据集列表失败：${res.status}`)
    const result = await res.json()
    if (result.code === 0) {
      knowledgeBases.value = result.data.map((ds: any) => ({
        id: ds.id,
        name: ds.name,
        description: ds.description,
        documentCount: ds.document_count || 0,
        chunkCount: ds.chunk_count || 0,
        tokenCount: ds.token_count || 0,
      }))
    }
  } catch (error) {
    showNotification(`获取知识库列表失败：${(error as Error).message}`, 'error')
  }
}

// 恢复知识库选择
const restoreKnowledgeBaseSelection = () => {
  if (!selectedAssistant.value?.id) return
  try {
    const saved = localStorage.getItem(`knowledgeBaseSelection_${selectedAssistant.value.id}`)
    if (saved) {
      const data = JSON.parse(saved)
      const expired = Date.now() - data.timestamp > 24 * 60 * 60 * 1000
      if (!expired) {
        currentKnowledgeBase.value = data.currentKnowledgeBase
        selectedKnowledgeBases.value = data.selectedKnowledgeBases || []
      }
    }
  } catch (error) {
    console.error('恢复知识库选择失败：', error)
  }
}

// 保存知识库选择
const saveKnowledgeBaseSelection = () => {
  if (!selectedAssistant.value?.id) return
  localStorage.setItem(
    `knowledgeBaseSelection_${selectedAssistant.value.id}`,
    JSON.stringify({
      currentKnowledgeBase: currentKnowledgeBase.value,
      selectedKnowledgeBases: selectedKnowledgeBases.value,
      timestamp: Date.now(),
    })
  )
}

// 知识库弹窗
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

// 选择知识库
const selectKnowledgeBase = (kb: KnowledgeBase) => {
  const idx = selectedKnowledgeBases.value.findIndex(s => s.id === kb.id)
  idx > -1
    ? selectedKnowledgeBases.value.splice(idx, 1)
    : selectedKnowledgeBases.value.push(kb)
}

// 打开文件管理
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

// 加载文件列表
const loadKnowledgeBaseFiles = async (kbId: string) => {
  if (!kbId || !ragflowConfig.value.endpoint) {
    currentFiles.value = []
    return
  }
  try {
    const res = await fetch(
      `${ragflowConfig.value.endpoint}/api/v1/datasets/${kbId}/documents?page=1&page_size=100`,
      { headers: { Authorization: ragflowConfig.value.apiKey } }
    )
    if (!res.ok) throw new Error(`获取文档列表失败: ${res.status}`)
    const result = await res.json()
    currentFiles.value = result.code === 0 && result.data?.docs
      ? result.data.docs.map((doc: any) => ({
          id: doc.id,
          name: doc.name,
          size: doc.size || 0,
          type: doc.type || doc.name?.split('.').pop()?.toUpperCase() || 'Unknown',
          run: doc.run,
          chunkCount: doc.chunk_count || 0,
          progress: doc.progress || 0,
        }))
      : []
  } catch (error) {
    showNotification(`获取文件列表失败：${(error as Error).message}`, 'error')
    currentFiles.value = []
  }
}

// 确认选择
const confirmSelection = () => {
  if (selectedKnowledgeBases.value.length > 0) {
    if (selectedKnowledgeBases.value.length === 1) {
      currentKnowledgeBase.value = selectedKnowledgeBases.value[0]
      selectedKnowledgeBases.value = []
      showNotification(`已选择知识库：${currentKnowledgeBase.value.name}`, 'success')
    } else {
      const names = selectedKnowledgeBases.value.map(kb => kb.name).join('、')
      showNotification(`已选择 ${selectedKnowledgeBases.value.length} 个知识库：${names}`, 'success')
      currentKnowledgeBase.value = null
    }
    const kbIds = currentKnowledgeBase.value
      ? [currentKnowledgeBase.value.id]
      : selectedKnowledgeBases.value.map(kb => kb.id)
    ws?.send({ type: 'selectedKbIds', ids: kbIds })
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
  const idx = selectedKnowledgeBases.value.findIndex(s => s.id === kb.id)
  if (idx > -1) selectedKnowledgeBases.value.splice(idx, 1)
}

// 创建知识库
const openCreateKnowledgeForm = () => {
  showKnowledgeModal.value = false
  showCreateKnowledgeForm.value = true
}

const createKnowledgeBase = async () => {
  if (!newKnowledgeBase.value.name.trim()) return
  try {
    const res = await fetch(`${ragflowConfig.value.endpoint}/api/v1/datasets`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: ragflowConfig.value.apiKey,
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
    if (!res.ok) throw new Error(`创建数据集失败：${res.status}`)
    const result = await res.json()
    if (result.code === 0) {
      showNotification('知识库创建成功！', 'success')
      await loadKnowledgeBases()
      newKnowledgeBase.value = { name: '', description: '' }
      showCreateKnowledgeForm.value = false
      showKnowledgeModal.value = true
    }
  } catch (error) {
    showNotification(`创建知识库失败：${(error as Error).message}`, 'error')
  }
}

// 删除知识库
const deleteKnowledgeBase = async (kb: KnowledgeBase) => {
  if (!confirm(`确定要删除知识库"${kb.name}"吗？此操作不可撤销。`)) return
  try {
    const res = await fetch(`${ragflowConfig.value.endpoint}/api/v1/datasets`, {
      method: 'DELETE',
      headers: {
        'Content-Type': 'application/json',
        Authorization: ragflowConfig.value.apiKey,
      },
      body: JSON.stringify({ ids: [kb.id] }),
    })
    if (!res.ok) throw new Error(`删除数据集失败：${res.status}`)
    const result = await res.json()
    if (result.code === 0) {
      showNotification('知识库删除成功！', 'success')
      await loadKnowledgeBases()
      if (currentKnowledgeBase.value?.id === kb.id) currentKnowledgeBase.value = null
      selectedKnowledgeBases.value = selectedKnowledgeBases.value.filter(s => s.id !== kb.id)
    }
  } catch (error) {
    showNotification(`删除知识库失败：${(error as Error).message}`, 'error')
  }
}

// 文件上传
const triggerFileUpload = () => {
  if (!currentFileManagerKB.value) return
  const input = document.createElement('input')
  input.type = 'file'
  input.multiple = true
  input.accept = '.txt,.pdf,.doc,.docx,.md,.mdx,.csv,.xlsx,.xls'
  input.onchange = e => {
    const files = Array.from((e.target as HTMLInputElement).files || [])
    processFilesForFileManager(files)
  }
  input.click()
}

const processFilesForFileManager = async (files: File[]) => {
  if (!files.length || !currentFileManagerKB.value) return
  isUploading.value = true
  uploadProgress.value = 0
  const uploaded: string[] = []
  const total = files.length

  try {
    for (let i = 0; i < files.length; i++) {
      const file = files[i]
      if (file.size > 50 * 1024 * 1024) {
        showNotification(`文件 ${file.name} 超过50MB限制`, 'error')
        continue
      }
      try {
        const form = new FormData()
        form.append('file', file)
        const res = await fetch(
          `${ragflowConfig.value.endpoint}/api/v1/datasets/${currentFileManagerKB.value!.id}/documents`,
          { method: 'POST', headers: { Authorization: ragflowConfig.value.apiKey }, body: form }
        )
        if (!res.ok) throw new Error(`上传失败: ${res.status}`)
        const result = await res.json()
        if (result.code === 0) {
          result.data.forEach((item: any) => uploaded.push(item.id))
          showNotification(`文件 ${file.name} 上传成功`, 'success')
        }
        uploadProgress.value = Math.round(((i + 1) / total) * 100)
      } catch (error) {
        showNotification(`上传文件 ${file.name} 失败`, 'error')
      }
    }

    if (uploaded.length > 0) {
      await loadKnowledgeBaseFiles(currentFileManagerKB.value!.id)
      try {
        await parseDocuments(currentFileManagerKB.value!.id, uploaded)
      } catch {
        showNotification('文件上传成功，但自动解析失败，请手动解析', 'warning')
      }
    }
  } finally {
    isUploading.value = false
    uploadProgress.value = 0
  }
}

const parseDocuments = async (datasetId: string, docIds: string[]) => {
  const valid = docIds.filter(id => id && typeof id === 'string')
  if (!valid.length) return
  const res = await fetch(`${ragflowConfig.value.endpoint}/api/v1/datasets/${datasetId}/chunks`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: ragflowConfig.value.apiKey,
    },
    body: JSON.stringify({ document_ids: valid }),
  })
  if (!res.ok) throw new Error(`解析文档失败: ${res.status}`)
  showNotification('文档解析已开始，请稍后查看进度', 'success')
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
          Authorization: ragflowConfig.value.apiKey,
        },
        body: JSON.stringify({ ids: [file.id] }),
      }
    )
    if (!res.ok) throw new Error(`删除文档失败: ${res.status}`)
    const result = await res.json()
    if (result.code === 0) {
      showNotification('文件删除成功', 'success')
      await loadKnowledgeBaseFiles(currentFileManagerKB.value!.id)
    }
  } catch (error) {
    showNotification(`删除文件失败：${(error as Error).message}`, 'error')
  }
}

// 格式化文件大小
const formatFileSize = (bytes: number): string => {
  if (bytes === 0) return '0 Bytes'
  const units = ['Bytes', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(1024))
  return parseFloat((bytes / Math.pow(1024, i)).toFixed(2)) + ' ' + units[i]
}

// 生命周期
onMounted(async () => {
  await loadRAGFlowConfig()
  await loadAssistants()
  await loadKnowledgeBases()
})

onBeforeUnmount(() => {
  ws?.close()
  voiceWs?.send({ type: 'hangup' })
  voiceWs?.close()
  webrtc.hangup()
})
</script>

<style scoped>
/* 通知提示 */
.notification-toast {
  position: fixed;
  top: 1rem;
  right: 1rem;
  z-index: 50;
  padding: 0.75rem 1.5rem;
  border-radius: var(--radius-md);
  box-shadow: 0 4px 16px var(--morandi-shadow-color), 0 1px 3px var(--morandi-shadow-color);
  border: 1px solid var(--morandi-border-strong);
  color: var(--morandi-text-on-primary);
  font-weight: 500;
}
.toast-success { background: var(--morandi-success); }
.toast-error { background: var(--morandi-error); }
.toast-warning { background: var(--morandi-warning); }
.toast-info { background: var(--morandi-primary); }

/* 侧边栏选中项左侧边框 */
.assistant-item.item-active {
  border-left: 3px solid var(--morandi-primary);
  padding-left: calc(0.75rem - 3px);
}

/* 滑入动画 */
@keyframes slide-in-right {
  from { opacity: 0; transform: translateX(100%); }
  to { opacity: 1; transform: translateX(0); }
}
.animate-slide-in-right {
  animation: slide-in-right 0.4s cubic-bezier(0.16, 1, 0.3, 1);
}

/* 通知过渡 */
.notification-enter-active,
.notification-leave-active {
  transition: all 0.25s cubic-bezier(0.16, 1, 0.3, 1);
}
.notification-enter-from,
.notification-leave-to {
  opacity: 0;
  transform: translateX(24px);
}
</style>