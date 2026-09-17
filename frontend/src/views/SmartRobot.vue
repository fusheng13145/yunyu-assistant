<template>
  <!-- 首次加载骨架屏 -->
  <div v-if="pageLoading" class="geek-body theme-transition h-screen w-full flex overflow-hidden antialiased">
    <SkeletonLoader />
  </div>

  <div v-else class="geek-body theme-transition h-screen w-full flex overflow-hidden antialiased">
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
    <aside class="sidebar w-72 flex-shrink-0 flex flex-col bg-geek-surface border-r border-geek">
      <!-- 品牌标题区 -->
      <div class="brand-header px-6 py-5 border-b border-geek">
        <h1 class="font-display text-xl font-bold tracking-tight text-geek-text">云谕助手</h1>
        <p class="text-xs mt-1 mono tracking-widest text-geek-text-muted">WORKSPACE // 智能对话工作台</p>
      </div>

      <!-- 新建助手按钮 -->
      <div class="px-4 py-4">
        <button
          @click="openModal"
          class="geek-btn geek-btn-primary w-full flex items-center justify-center gap-2 py-2.5"
        >
          <Plus class="w-4 h-4" />
          <span>新建助手</span>
        </button>
        <button
          @click="router.push('/records')"
          class="geek-btn geek-btn-ghost w-full flex items-center justify-center gap-2 py-2.5 mt-2"
        >
          <History class="w-4 h-4" />
          <span>通话记录</span>
        </button>
        <button
          v-if="userRole === 'admin'"
          @click="router.push('/admin')"
          class="geek-btn geek-btn-ghost w-full flex items-center justify-center gap-2 py-2.5 mt-2"
        >
          <Shield class="w-4 h-4" />
          <span>管理后台</span>
        </button>
      </div>

      <!-- 助手列表 -->
      <div class="flex-1 min-h-0 overflow-y-auto geek-scroll transparent-scrollbar px-3 pb-4">
        <!-- 搜索助手 -->
        <div class="pt-3 pb-2">
          <input
            v-model="searchKeyword"
            type="text"
            placeholder="搜索助手"
            class="geek-input w-full px-3 py-1.5 rounded-md text-sm"
            @keyup.enter="onSearchAssistant"
          />
        </div>

        <div v-if="assistants.length === 0" class="empty-state text-center py-12 px-4">
          <Bot class="w-10 h-10 mx-auto mb-3 text-geek-text-faint" />
          <p class="text-sm text-geek-text-muted">{{ searchKeyword ? '未找到匹配的助手' : '暂无助手' }}</p>
          <p class="text-xs mt-1 text-geek-text-faint">{{ searchKeyword ? '请尝试更换关键词' : '点击上方按钮创建' }}</p>
        </div>

        <div
          v-for="(bot, index) in assistants"
          :key="bot.id"
          @click="selectAssistant(bot)"
          class="assistant-item group relative flex items-center gap-3 px-3 py-3 mb-1 rounded-md cursor-pointer transition-all duration-200"
          :class="selectedAssistant?.id === bot.id ? 'bg-geek-bg-subtle item-active' : 'hover:bg-geek-bg-subtle'"
        >
          <!-- 头像 -->
          <div
            class="avatar w-9 h-9 rounded-lg flex items-center justify-center flex-shrink-0"
            :style="{ background: ['var(--geek-tag-blue)', 'var(--geek-tag-purple)', 'var(--geek-tag-gold)'][index % 3] }"
          >
            <Bot class="w-4 h-4 text-white" />
          </div>
          <!-- 名称和类型 -->
          <div class="flex-1 min-w-0">
            <div class="text-sm font-medium truncate text-geek-text">{{ bot.name || '小助手' }}</div>
            <div class="flex items-center gap-2 mt-0.5">
              <span class="text-xs text-geek-text-muted">{{ getAssistantType(bot) }}</span>
              <span
                v-if="getConversationCount(bot.id) > 0"
                class="count-badge text-xs px-1.5 py-0.5 rounded-sm bg-geek-bg-subtle text-geek-text-secondary"
              >
                {{ getConversationCount(bot.id) }}
              </span>
            </div>
          </div>
          <!-- hover 操作按钮 -->
          <div class="item-actions flex items-center gap-1 flex-shrink-0">
            <button
              class="action-btn p-1 rounded-sm transition-colors text-geek-text-muted hover:text-geek-text-secondary"
              @click.stop="openSettingsModal(bot)"
            >
              <Settings class="w-3.5 h-3.5" />
            </button>
            <button
              class="action-btn p-1 rounded-sm transition-colors text-geek-text-muted hover:text-geek-error"
              @click.stop="confirmDelete(bot)"
            >
              <Trash2 class="w-3.5 h-3.5" />
            </button>
          </div>
        </div>

        <!-- 分页控件 -->
        <div v-if="assistantTotal > ASSISTANT_PAGE_SIZE" class="flex items-center justify-between px-1 pt-3">
          <button
            @click="prevAssistantPage"
            :disabled="assistantPage <= 1"
            class="geek-btn geek-btn-ghost geek-btn-sm"
            :class="{ 'opacity-40 cursor-not-allowed': assistantPage <= 1 }"
          >
            上一页
          </button>
          <span class="text-xs text-geek-text-muted">{{ assistantPage }} / {{ assistantTotalPages }}</span>
          <button
            @click="nextAssistantPage"
            :disabled="assistantPage >= assistantTotalPages"
            class="geek-btn geek-btn-ghost geek-btn-sm"
            :class="{ 'opacity-40 cursor-not-allowed': assistantPage >= assistantTotalPages }"
          >
            下一页
          </button>
        </div>
      </div>

      <!-- 底部用户区 -->
      <div class="user-bar px-4 py-3 border-t border-geek">
        <div class="flex items-center justify-between">
          <div class="user-info flex items-center gap-2">
            <div class="user-avatar w-7 h-7 rounded-md flex items-center justify-center text-xs font-medium text-white bg-geek-primary">
              {{ userInitial }}
            </div>
            <span class="text-sm text-geek-text">{{ userName }}</span>
          </div>
          <button
            @click="handleLogout"
            class="geek-btn geek-btn-ghost text-xs px-2 py-1 flex items-center gap-1"
          >
            <LogOut class="w-3.5 h-3.5" />
            <span>登出</span>
          </button>
        </div>
      </div>
    </aside>

    <!-- 右侧主区域 -->
    <main class="main-panel flex-1 flex flex-col min-w-0 bg-geek-base">
      <!-- 顶部工具栏 -->
      <header class="toolbar h-14 flex items-center justify-between px-6 border-b border-geek bg-geek-subtle flex-shrink-0">
        <div class="toolbar-left flex items-center gap-3">
          <h2 v-if="selectedAssistant" class="text-lg font-bold tracking-tight text-geek-text">
            {{ selectedAssistant.name }}
          </h2>
          <span v-else class="text-sm text-geek-text-muted">请选择一个助手开始对话</span>
        </div>
        <div class="toolbar-right flex items-center gap-2 relative">
          <button
            v-if="selectedAssistant"
            @click="openSettingsModal(selectedAssistant)"
            class="geek-btn geek-btn-ghost px-3 py-1.5 text-sm flex items-center gap-1.5"
          >
            <Settings class="w-4 h-4" />
            <span>设置</span>
          </button>
          <button
            v-if="selectedAssistant"
            @click="resetChat"
            class="geek-btn geek-btn-ghost px-3 py-1.5 text-sm flex items-center gap-1.5"
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
              class="geek-btn geek-btn-ghost geek-btn-sm"
              title="搜索消息"
            >
              <Search class="w-3.5 h-3.5" />
            </button>
            <!-- 导出对话 -->
            <button
              v-if="messages.length > 0"
              @click="handleExport"
              class="geek-btn geek-btn-ghost geek-btn-sm"
              title="导出对话"
            >
              <Download class="w-3.5 h-3.5" />
            </button>
            <!-- 快捷命令 -->
            <button
              @click="showQuickCommands = !showQuickCommands"
              class="geek-btn geek-btn-ghost geek-btn-sm"
              title="快捷命令"
            >
              <Zap class="w-3.5 h-3.5" />
            </button>
          </div>
          <!-- 快捷命令面板 -->
          <div v-if="showQuickCommands" class="cmd-panel absolute top-full right-6 mt-1 animate-modal-in">
            <div class="geek-card-elevated p-2 rounded-xl shadow-lg z-50 min-w-[160px]">
              <div class="text-xs font-medium px-2 py-1 text-geek-muted">快捷命令</div>
              <button
                v-for="cmd in quickCommands"
                :key="cmd.label"
                @click="sendQuickCommand(cmd)"
                class="cmd-item w-full text-left px-2 py-1.5 rounded-md text-sm transition-colors text-geek-text hover:bg-geek-primary-light"
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
        <div v-if="isSearchActive" class="search-bar px-4 py-2 border-b border-geek bg-geek-subtle flex items-center gap-2 flex-shrink-0">
          <input
            v-model="searchQuery"
            placeholder="搜索消息..."
            class="geek-input flex-1 text-sm py-1.5"
            @input="searchMessages(messages)"
          />
          <button @click="clearSearch()" class="geek-btn geek-btn-ghost geek-btn-sm">
            <X class="w-3.5 h-3.5" />
          </button>
        </div>

        <!-- 空状态 -->
        <div v-if="!selectedAssistant" class="empty-home absolute inset-0 flex items-center justify-center">
          <div class="text-center animate-fade-up">
            <div class="welcome-icon w-20 h-20 rounded-xl flex items-center justify-center mx-auto mb-5 geek-card">
              <MessageCircle class="w-10 h-10 text-geek-text-muted" />
            </div>
            <h3 class="text-xl font-bold tracking-tight mb-2 text-geek-text-secondary">欢迎使用云谕助手</h3>
            <p class="text-sm text-geek-text-muted">请从左侧选择一个助手开始对话</p>
          </div>
        </div>

        <template v-if="selectedAssistant">
          <!-- 语音识别提示 -->
          <div
            v-if="asrText && voiceCallActive"
            class="asr-tip mx-6 mt-3 px-4 py-2 rounded-md border text-sm bg-geek-bg-subtle border-geek-primary text-geek-primary"
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
      <div v-if="selectedAssistant" class="input-area border-t border-geek p-4 bg-geek-base flex-shrink-0">
        <div v-if="voiceCallActive" class="voice-panel flex flex-col items-center gap-4 py-2">
          <div class="voice-main flex items-center gap-4">
            <div class="mic-wrap relative">
              <Mic class="w-8 h-8 animate-pulse text-geek-error" />
              <div class="mic-ring absolute -inset-2 rounded-full border-2 animate-ping border-geek-error opacity-30"></div>
            </div>
            <!-- 音量波形 -->
            <div class="audio-bars flex items-center gap-1">
              <div
                v-for="i in 5"
                :key="i"
                class="bar-item w-1.5 rounded-full transition-all duration-150 bg-geek-primary"
                :style="{
                  height: `${Math.max(4, audioLevel * 40 * (0.5 + Math.random() * 0.5))}px`,
                  opacity: audioLevel > (i - 1) * 0.2 ? 1 : 0.3
                }"
              ></div>
            </div>
          </div>
          <button
            @click="endVoiceCall"
            class="geek-btn geek-btn-danger px-8 py-2.5 flex items-center gap-2"
          >
            <PhoneOff class="w-4 h-4" />
            <span>挂断</span>
          </button>
        </div>

        <div v-else class="input-row flex items-center gap-3">
          <input
            v-model="inputText"
            type="text"
            class="flex-1 geek-input h-12 px-4 rounded-lg"
            placeholder="请输入您想问的问题..."
            @keyup.enter="sendMessage"
            :disabled="isTyping"
          />
          <button
            @click="startVoiceCall"
            class="geek-btn geek-btn-ghost w-12 h-12 rounded-full flex items-center justify-center"
            :class="{ 'opacity-40 cursor-not-allowed': !selectedAssistant?.id }"
            :disabled="!selectedAssistant?.id"
          >
            <Mic class="w-5 h-5" />
          </button>
          <button
            @click="sendMessage"
            class="geek-btn geek-btn-primary w-12 h-12 rounded-full flex items-center justify-center"
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
      class="modal-mask fixed inset-0 z-50 flex items-center justify-center bg-geek-overlay"
      @click.self="showModal ? closeModal() : cancelDelete()"
    >
      <div class="modal-card animate-modal-in geek-card-elevated rounded-xl w-[420px] max-w-[95vw] p-6">
        <!-- 新增助手 -->
        <div v-if="showModal">
          <h3 class="text-lg font-bold tracking-tight mb-6 text-geek-text">新增助手</h3>
          <div class="form-groups space-y-4">
            <div>
              <label class="block text-sm font-medium mb-1.5 text-geek-text-secondary">助手名称</label>
              <input
                v-model="formData.name"
                type="text"
                placeholder="请输入助手名称"
                class="w-full geek-input px-4 py-2.5 rounded-md"
              />
            </div>
            <div>
              <label class="block text-sm font-medium mb-1.5 text-geek-text-secondary">助手描述</label>
              <textarea
                v-model="formData.description"
                placeholder="请输入助手描述（可选）"
                class="w-full geek-input px-4 py-2.5 rounded-md resize-none"
                rows="3"
              ></textarea>
            </div>
            <div>
              <label class="block text-sm font-medium mb-1.5 text-geek-text-secondary">人设模板</label>
              <select
                class="w-full geek-input px-4 py-2.5 rounded-md"
                @change="onTemplateChange($event, 'create')"
              >
                <option value="">选择模板（可选）</option>
                <option v-for="tpl in personaTemplates" :key="tpl.id" :value="tpl.id">{{ tpl.name }}</option>
              </select>
            </div>
            <div>
              <label class="block text-sm font-medium mb-1.5 text-geek-text-secondary">系统提示词</label>
              <textarea
                v-model="formData.personality"
                placeholder="定义助手的人设和性格..."
                class="w-full geek-input px-4 py-2.5 rounded-md resize-none"
                rows="4"
              ></textarea>
            </div>
            <div>
              <label class="block text-sm font-medium mb-1.5 text-geek-text-secondary">音色</label>
              <select
                v-model="formData.voice"
                class="w-full geek-input px-4 py-2.5 rounded-md"
              >
                <option value="">默认音色</option>
                <option v-for="v in voices" :key="v.id" :value="v.id">
                  {{ v.name }}（{{ v.gender === 1 ? '女' : '男' }}）
                </option>
              </select>
            </div>
            <div>
              <label class="block text-sm font-medium mb-1.5 text-geek-text-secondary">模型</label>
              <select
                v-model="formData.modelName"
                class="w-full geek-input px-4 py-2.5 rounded-md"
              >
                <option value="">默认模型</option>
                <option v-for="m in models" :key="m.id" :value="m.id">{{ m.name }}</option>
              </select>
            </div>
            <div class="grid grid-cols-2 gap-3">
              <div>
                <label class="block text-sm font-medium mb-1.5 text-geek-text-secondary">温度 (0-2)</label>
                <input
                  v-model.number="formData.temperature"
                  type="number"
                  min="0"
                  max="2"
                  step="0.1"
                  class="w-full geek-input px-4 py-2.5 rounded-md"
                />
              </div>
              <div>
                <label class="block text-sm font-medium mb-1.5 text-geek-text-secondary">最大输出</label>
                <input
                  v-model.number="formData.maxTokens"
                  type="number"
                  min="1"
                  step="1"
                  class="w-full geek-input px-4 py-2.5 rounded-md"
                />
              </div>
            </div>
          </div>
          <div class="flex justify-end gap-3 mt-6">
            <button @click="closeModal" class="geek-btn geek-btn-ghost">取消</button>
            <button @click="addAssistant()" class="geek-btn geek-btn-primary">确定创建</button>
          </div>
        </div>

        <!-- 删除确认 -->
        <div v-if="showDeleteModal">
          <h3 class="text-lg font-bold tracking-tight mb-2 text-geek-error">确认删除</h3>
          <p class="text-sm mb-6 text-geek-text-secondary">你确定要删除这个助手吗？此操作无法撤销。</p>
          <div class="flex justify-end gap-3">
            <button @click="cancelDelete" class="geek-btn geek-btn-ghost">取消</button>
            <button @click="doDelete" class="geek-btn geek-btn-danger">确认删除</button>
          </div>
        </div>
      </div>
    </div>

    <!-- 设置弹窗 -->
    <div
      v-if="showSettings"
      class="modal-mask fixed inset-0 z-40 flex items-center justify-center bg-geek-overlay"
      @click.self="closeSettingsModal"
    >
      <div class="modal-large animate-modal-in geek-card-elevated rounded-xl w-[900px] max-w-[95vw] max-h-[85vh] flex flex-col overflow-hidden">
        <!-- 设置头部 -->
        <div class="modal-header flex items-center justify-between px-6 py-4 border-b border-geek flex-shrink-0">
          <h2 class="text-xl font-bold tracking-tight text-geek-text">助手设置</h2>
          <button @click="closeSettingsModal" class="p-1.5 rounded-md transition-colors geek-btn geek-btn-ghost">
            <X class="w-5 h-5" />
          </button>
        </div>

        <!-- 设置内容 -->
        <div class="modal-body flex-1 min-h-0 overflow-y-auto geek-scroll transparent-scrollbar p-6 space-y-5">
          <!-- 助手信息卡片 -->
          <div class="info-card geek-card rounded-lg p-5">
            <div class="flex items-center gap-3">
              <div class="avatar w-10 h-10 rounded-lg flex items-center justify-center flex-shrink-0 bg-geek-tag-blue">
                <Bot class="w-5 h-5 text-white" />
              </div>
              <div>
                <div class="font-semibold text-geek-text">{{ settingsAssistant?.name }}</div>
                <div class="text-sm mt-0.5 text-geek-text-muted">{{ settingsAssistant?.description }}</div>
              </div>
            </div>
          </div>

          <!-- 人设设置 -->
          <div class="setting-card geek-card rounded-lg p-5">
            <div class="card-head flex items-center justify-between mb-4">
              <h3 class="font-semibold text-geek-text">人设设置</h3>
              <button
                @click="savePersonality"
                :disabled="!personalityChanged"
                class="geek-btn geek-btn-primary text-sm px-4 py-1.5"
                :class="{ 'opacity-40 cursor-not-allowed': !personalityChanged }"
              >
                {{ saving ? '保存中...' : '保存人设' }}
              </button>
            </div>
            <select
              class="w-full geek-input rounded-md px-4 py-2.5 mb-3"
              @change="onTemplateChange($event, 'settings')"
            >
              <option value="">选择人设模板（可选）</option>
              <option v-for="tpl in personaTemplates" :key="tpl.id" :value="tpl.id">{{ tpl.name }}</option>
            </select>
            <textarea
              v-model="personalityText"
              @input="onPersonalityChange"
              class="w-full h-40 resize-none geek-input rounded-md p-4 text-sm leading-relaxed"
              placeholder="请输入机器人的人设描述，例如：你是一个专业的客服助手，擅长解答用户问题..."
            ></textarea>
            <div class="text-xs mt-2 text-right text-geek-text-muted">
              {{ personalityText.length }}/500 字符
            </div>
          </div>

          <!-- 音色设置 -->
          <div class="setting-card geek-card rounded-lg p-5">
            <div class="card-head flex items-center justify-between mb-4">
              <h3 class="font-semibold text-geek-text">音色设置</h3>
              <button
                @click="saveVoice"
                class="geek-btn geek-btn-primary text-sm px-4 py-1.5"
              >
                保存音色
              </button>
            </div>
            <select v-model="settingsVoice" class="w-full geek-input rounded-md px-4 py-2.5">
              <option value="">默认音色</option>
              <option v-for="v in voices" :key="v.id" :value="v.id">
                {{ v.name }}（{{ v.gender === 1 ? '女' : '男' }}） - {{ v.description }}
              </option>
            </select>
          </div>

          <!-- 模型参数设置 -->
          <div class="setting-card geek-card rounded-lg p-5">
            <div class="card-head flex items-center justify-between mb-4">
              <h3 class="font-semibold text-geek-text">模型参数</h3>
              <button
                @click="saveModelParams"
                class="geek-btn geek-btn-primary text-sm px-4 py-1.5"
              >
                保存参数
              </button>
            </div>
            <div class="space-y-3">
              <div>
                <label class="block text-sm font-medium mb-1.5 text-geek-text-secondary">模型</label>
                <select v-model="settingsModelName" class="w-full geek-input rounded-md px-4 py-2.5">
                  <option value="">默认模型</option>
                  <option v-for="m in models" :key="m.id" :value="m.id">{{ m.name }}</option>
                </select>
              </div>
              <div class="grid grid-cols-2 gap-3">
                <div>
                  <label class="block text-sm font-medium mb-1.5 text-geek-text-secondary">温度 (0-2)</label>
                  <input
                    v-model.number="settingsTemperature"
                    type="number"
                    min="0"
                    max="2"
                    step="0.1"
                    class="w-full geek-input rounded-md px-4 py-2.5"
                  />
                </div>
                <div>
                  <label class="block text-sm font-medium mb-1.5 text-geek-text-secondary">最大输出</label>
                  <input
                    v-model.number="settingsMaxTokens"
                    type="number"
                    min="1"
                    step="1"
                    class="w-full geek-input rounded-md px-4 py-2.5"
                  />
                </div>
              </div>
            </div>
          </div>

          <!-- 知识库配置 -->
          <div class="setting-card geek-card rounded-lg p-5">
            <div class="card-head flex items-center justify-between mb-4">
              <h3 class="font-semibold text-geek-text">知识库配置</h3>
              <div class="flex items-center gap-2">
                <button
                  @click="showRetrievalTest = true"
                  class="geek-btn geek-btn-ghost text-sm px-3 py-1.5 flex items-center gap-1.5"
                >
                  <Search class="w-4 h-4" />
                  <span>检索测试</span>
                </button>
                <button
                  @click="openKnowledgeModal"
                  class="geek-btn geek-btn-primary text-sm px-4 py-1.5 flex items-center gap-1.5"
                >
                  <Database class="w-4 h-4" />
                  <span>管理知识库</span>
                </button>
              </div>
            </div>

            <!-- 单知识库选中 -->
            <div v-if="currentKnowledgeBase" class="kb-selected geek-card px-4 py-3 rounded-md">
              <div class="flex items-center justify-between">
                <div class="flex items-center">
                  <Database class="w-5 h-5 mr-3 text-geek-primary" />
                  <div>
                    <div class="font-medium text-sm text-geek-text">{{ currentKnowledgeBase.name }}</div>
                    <div class="text-xs mt-0.5 text-geek-text-muted">
                      {{ currentKnowledgeBase.documentCount || 0 }} 个文档 · {{ currentKnowledgeBase.chunkCount || 0 }} 切片
                    </div>
                  </div>
                </div>
                <span class="status-tag text-xs px-2 py-0.5 rounded-sm bg-geek-success-bg text-geek-success">已选择</span>
              </div>
            </div>

            <!-- 多知识库选中 -->
            <div v-else-if="selectedKnowledgeBases.length > 0" class="kb-multi geek-card px-4 py-3 rounded-md">
              <div class="flex items-center justify-between mb-2">
                <div class="flex items-center">
                  <Database class="w-5 h-5 mr-3 text-geek-accent" />
                  <div>
                    <div class="font-medium text-sm text-geek-text">已选择 {{ selectedKnowledgeBases.length }} 个知识库</div>
                    <div class="text-xs text-geek-text-muted">多知识库联合检索模式</div>
                  </div>
                </div>
                <button @click="clearMultiSelection" class="clear-btn text-xs px-2 py-1 rounded-sm bg-geek-error-bg text-geek-error">
                  清空
                </button>
              </div>
              <div class="selected-list space-y-1 max-h-24 overflow-y-auto transparent-scrollbar">
                <div
                  v-for="kb in selectedKnowledgeBases"
                  :key="kb.id"
                  class="selected-item flex items-center justify-between px-3 py-1.5 rounded-sm bg-geek-input-bg"
                >
                  <div class="flex items-center">
                    <Check class="w-3 h-3 mr-2 text-geek-primary" />
                    <span class="text-sm text-geek-text-secondary">{{ kb.name }}</span>
                  </div>
                  <button @click.stop="removeFromSelection(kb)" class="remove-btn p-0.5 text-geek-text-muted hover:text-geek-error">
                    <X class="w-3 h-3" />
                  </button>
                </div>
              </div>
            </div>

            <!-- 空状态 -->
            <div v-else class="kb-empty px-4 py-8 rounded-md text-center bg-geek-input-bg border border-dashed border-geek-border">
              <FolderOpen class="w-8 h-8 mx-auto mb-2 text-geek-text-faint" />
              <p class="text-sm text-geek-text-muted">点击「管理知识库」选择或创建知识库</p>
            </div>
          </div>
        </div>

        <!-- 设置底部 -->
        <div class="modal-footer px-6 py-4 border-t border-geek flex-shrink-0">
          <div class="flex justify-end">
            <button @click="closeSettingsModal" class="geek-btn geek-btn-ghost">关闭</button>
          </div>
        </div>
      </div>
    </div>

    <!-- 知识库管理弹窗 -->
    <div
      v-if="showKnowledgeModal"
      class="modal-mask fixed inset-0 z-50 flex items-center justify-center bg-geek-overlay"
      @click.self="closeKnowledgeModal"
    >
      <div
        class="kb-modal animate-modal-in geek-card-elevated rounded-xl max-w-[95vw] flex overflow-hidden transition-all duration-500"
        :class="showFileManager ? 'w-[1400px]' : 'w-[900px]'"
      >
        <!-- 知识库列表面板 -->
        <div
          class="kb-panel transition-all duration-500 ease-in-out flex flex-col"
          :class="showFileManager ? 'w-1/2' : 'w-full'"
        >
          <!-- 知识库头部 -->
          <div class="kb-header px-6 py-5 border-b border-geek flex-shrink-0">
            <div class="flex items-center justify-between mb-5">
              <h2 class="text-xl font-bold tracking-tight text-geek-text">知识库管理</h2>
              <div class="header-actions flex items-center gap-3">
                <div class="layout-switch p-0.5 flex rounded-sm bg-geek-input-bg">
                  <button
                    @click="knowledgeBaseLayout = 'list'"
                    class="layout-btn px-2.5 py-1 rounded-sm text-sm transition-all duration-200"
                    :class="knowledgeBaseLayout === 'list' ? 'geek-btn geek-btn-primary' : 'text-geek-text-muted'"
                  >
                    <List class="w-4 h-4" />
                  </button>
                  <button
                    @click="knowledgeBaseLayout = 'grid'"
                    class="layout-btn px-2.5 py-1 rounded-sm text-sm transition-all duration-200"
                    :class="knowledgeBaseLayout === 'grid' ? 'geek-btn geek-btn-primary' : 'text-geek-text-muted'"
                  >
                    <LayoutGrid class="w-4 h-4" />
                  </button>
                </div>
                <button @click="closeKnowledgeModal" class="p-1.5 rounded-sm geek-btn geek-btn-ghost">
                  <X class="w-5 h-5" />
                </button>
              </div>
            </div>

            <button
              @click="openCreateKnowledgeForm"
              class="geek-btn geek-btn-primary text-sm px-4 py-2 inline-flex items-center gap-1.5"
            >
              <Plus class="w-4 h-4" />
              <span>新建知识库</span>
            </button>
          </div>

          <!-- 知识库列表 -->
          <div class="kb-list flex-1 min-h-0 overflow-y-auto geek-scroll transparent-scrollbar p-6">
            <div
              class="list-wrap transition-all duration-300"
              :class="knowledgeBaseLayout === 'grid' ? 'grid grid-cols-2 gap-4' : 'grid grid-cols-1 gap-3'"
            >
              <div
                v-for="kb in knowledgeBases"
                :key="kb.id"
                @click="selectKnowledgeBase(kb)"
                class="kb-item geek-card rounded-lg p-4 cursor-pointer transition-all duration-200"
                :class="[
                  knowledgeBaseLayout === 'list' ? 'flex items-center' : 'block',
                  { 'ring-2 ring-[var(--geek-primary)] bg-geek-bg-subtle': selectedKnowledgeBases.some(s => s.id === kb.id) }
                ]"
              >
                <template v-if="knowledgeBaseLayout === 'list'">
                  <div class="item-row flex items-center w-full">
                    <div class="check-wrap mr-3 flex-shrink-0">
                      <div
                        class="check-box w-5 h-5 rounded border-2 flex items-center justify-center transition-all duration-200"
                        :class="selectedKnowledgeBases.some(s => s.id === kb.id) ? 'bg-geek-primary border-geek-primary' : 'border-geek-border'"
                      >
                        <Check v-if="selectedKnowledgeBases.some(s => s.id === kb.id)" class="w-3 h-3 text-white" />
                      </div>
                    </div>
                    <div class="item-main flex items-center flex-1 min-w-0">
                      <Database class="w-5 h-5 mr-3 flex-shrink-0 text-geek-tag-gold" />
                      <div class="item-text flex-1 min-w-0">
                        <h3 class="font-semibold text-sm truncate text-geek-text">{{ kb.name }}</h3>
                        <p class="text-xs truncate mt-0.5 text-geek-text-muted">{{ kb.description || '暂无描述' }}</p>
                      </div>
                    </div>
                    <div class="item-stat flex items-center gap-3 mx-3 flex-shrink-0">
                      <div class="stat text-center">
                        <div class="font-bold text-sm text-geek-primary">{{ kb.documentCount || 0 }}</div>
                        <div class="text-xs text-geek-text-faint">文档</div>
                      </div>
                    </div>
                    <div class="item-op flex gap-1 flex-shrink-0">
                      <button @click.stop="selectKnowledgeBaseForFileManager(kb)" class="op-btn p-1.5 rounded-sm text-geek-text-muted hover:bg-geek-surface">
                        <FolderOpen class="w-4 h-4" />
                      </button>
                      <button @click.stop="deleteKnowledgeBase(kb)" class="op-btn p-1.5 rounded-sm text-geek-text-muted hover:text-geek-error">
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
                          :class="selectedKnowledgeBases.some(s => s.id === kb.id) ? 'bg-geek-primary border-geek-primary' : 'border-geek-border'"
                        >
                          <Check v-if="selectedKnowledgeBases.some(s => s.id === kb.id)" class="w-3 h-3 text-white" />
                        </div>
                      </div>
                      <Database class="w-7 h-7 mr-3 text-geek-tag-gold" />
                      <div class="grid-text">
                        <h3 class="font-semibold text-geek-text">{{ kb.name }}</h3>
                        <p class="text-sm mt-0.5 text-geek-text-secondary">{{ kb.description || '暂无描述' }}</p>
                      </div>
                    </div>
                    <div class="grid-op flex gap-1.5">
                      <button @click.stop="selectKnowledgeBaseForFileManager(kb)" class="op-btn p-1.5 rounded-sm text-geek-text-muted hover:bg-geek-surface">
                        <FolderOpen class="w-4 h-4" />
                      </button>
                      <button @click.stop="deleteKnowledgeBase(kb)" class="op-btn p-1.5 rounded-sm text-geek-text-muted hover:text-geek-error">
                        <Trash2 class="w-4 h-4" />
                      </button>
                    </div>
                  </div>
                  <div class="grid-bottom flex justify-between text-sm pt-3 border-t border-geek-divider">
                    <div class="stat text-center">
                      <div class="font-bold text-geek-primary">{{ kb.documentCount || 0 }}</div>
                      <div class="text-xs text-geek-text-faint">文档数</div>
                    </div>
                    <div class="stat text-center">
                      <div class="font-bold text-geek-accent">{{ kb.chunkCount || 0 }}</div>
                      <div class="text-xs text-geek-text-faint">切片数</div>
                    </div>
                  </div>
                </template>
              </div>
            </div>
          </div>

          <!-- 知识库底部操作 -->
          <div class="kb-footer px-6 py-4 border-t border-geek flex-shrink-0">
            <div class="selection-info mb-4">
              <div class="flex items-center justify-between mb-2">
                <span class="text-sm text-geek-text-secondary">
                  已选择 {{ selectedKnowledgeBases.length }} 个知识库
                </span>
                <button
                  v-if="selectedKnowledgeBases.length > 0"
                  @click="clearAllSelections"
                  class="text-xs geek-btn geek-btn-ghost px-2 py-0.5 text-geek-error"
                >
                  清空选择
                </button>
              </div>
              <div v-if="selectedKnowledgeBases.length > 0" class="tag-list flex flex-wrap gap-2">
                <div
                  v-for="kb in selectedKnowledgeBases"
                  :key="kb.id"
                  class="tag-item px-3 py-1 rounded-md text-sm flex items-center gap-1.5 bg-geek-bg-subtle text-geek-primary border border-geek"
                >
                  <span>{{ kb.name }}</span>
                  <button @click="removeFromSelection(kb)" class="tag-close p-0.5 hover:opacity-60">
                    <X class="w-3 h-3" />
                  </button>
                </div>
              </div>
              <div v-else class="text-sm text-geek-text-faint">未选择任何知识库</div>
            </div>
            <div class="btn-group flex justify-end gap-3">
              <button @click="closeKnowledgeModal" class="geek-btn geek-btn-ghost">取消</button>
              <button @click="confirmSelection" class="geek-btn geek-btn-primary">确认选择</button>
            </div>
          </div>
        </div>

        <!-- 文件管理面板 -->
        <div v-if="showFileManager" class="file-panel w-1/2 flex flex-col animate-slide-in-right border-l border-geek bg-geek-surface">
          <div class="file-header px-6 py-4 border-b border-geek flex-shrink-0">
            <div class="flex items-center justify-between mb-4">
              <div class="file-title flex items-center">
                <button @click="closeFileManager" class="p-1.5 rounded-sm mr-3 geek-btn geek-btn-ghost">
                  <ChevronLeft class="w-5 h-5" />
                </button>
                <h3 class="text-lg font-bold tracking-tight text-geek-text">文件管理</h3>
              </div>
              <div class="file-op flex gap-2">
                <button
                  @click="triggerFileUpload"
                  :disabled="isUploading"
                  class="geek-btn geek-btn-primary text-sm px-3 py-1.5 flex items-center gap-1.5"
                  :class="{ 'opacity-60': isUploading }"
                >
                  <Upload class="w-4 h-4" />
                  <span>{{ isUploading ? '上传中...' : '上传文件' }}</span>
                </button>
              </div>
            </div>

            <div v-if="isUploading && uploadProgress > 0" class="progress-wrap mb-4">
              <div class="flex justify-between text-sm mb-2 text-geek-text-secondary">
                <span>上传进度</span>
                <span>{{ Math.round(uploadProgress) }}%</span>
              </div>
              <div class="progress-bar w-full rounded-full h-1.5 bg-geek-input-bg">
                <div
                  class="progress-inner h-1.5 rounded-full transition-all duration-300 bg-geek-primary"
                  :style="{ width: uploadProgress + '%' }"
                ></div>
              </div>
            </div>

            <div v-if="currentFileManagerKB" class="kb-info rounded-md px-3 py-2.5 bg-geek-input-bg">
              <div class="flex items-center">
                <Database class="w-4 h-4 mr-2 text-geek-tag-gold" />
                <span class="font-medium text-sm text-geek-text">{{ currentFileManagerKB.name }}</span>
              </div>
            </div>
          </div>

          <div
            class="file-list flex-1 overflow-y-auto geek-scroll transparent-scrollbar p-6"
            :class="{ 'drag-active': dragActive }"
            @dragover.prevent="dragActive = true"
            @dragleave.prevent="dragActive = false"
            @drop.prevent="handleDrop"
          >
            <div v-if="currentFiles.length === 0" class="empty-state text-center py-12">
              <FileText class="w-14 h-14 mx-auto mb-3 text-geek-text-faint" />
              <p class="text-sm font-medium text-geek-text-muted">暂无文件</p>
              <p class="text-xs mt-1 text-geek-text-faint">点击上传按钮或拖拽文件到此区域</p>
            </div>
            <div v-else class="file-items space-y-2.5">
              <div
                v-for="file in currentFiles"
                :key="file.id"
                class="file-item geek-card rounded-md p-4 transition-all duration-200 hover:bg-geek-surface-raised"
              >
                <div class="flex items-center justify-between">
                  <div class="file-main flex items-center flex-1 min-w-0">
                    <FileText class="w-7 h-7 mr-3 flex-shrink-0 text-geek-primary" />
                    <div class="file-info flex-1 min-w-0">
                      <h4 class="font-medium text-sm truncate text-geek-text">{{ file.name }}</h4>
                      <div class="file-meta flex items-center text-xs mt-1 text-geek-text-faint">
                        <span>{{ formatFileSize(file.size) }}</span>
                        <span class="mx-1.5">&middot;</span>
                        <span>{{ file.run || '未知' }}</span>
                      </div>
                    </div>
                  </div>
                  <div class="file-op ml-3 flex-shrink-0">
                    <button @click="deleteFile(file)" class="op-btn p-1.5 rounded-sm text-geek-text-muted hover:text-geek-error">
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
      class="modal-mask fixed inset-0 z-[60] flex items-center justify-center bg-geek-overlay"
      @click.self="showCreateKnowledgeForm = false"
    >
      <div class="modal-small animate-modal-in geek-card-elevated rounded-xl w-[440px] max-w-[95vw] p-6">
        <h3 class="text-lg font-bold tracking-tight mb-5 text-geek-text">创建知识库</h3>
        <div class="form-groups space-y-4">
          <div>
            <label class="block text-sm font-medium mb-1.5 text-geek-text-secondary">
              <span class="text-geek-error">*</span> 知识库名称
            </label>
            <input
              v-model="newKnowledgeBase.name"
              type="text"
              placeholder="请输入知识库名称"
              maxlength="20"
              class="w-full geek-input px-4 py-2.5 rounded-md"
            />
            <div class="text-xs mt-1 text-right text-geek-text-faint">{{ newKnowledgeBase.name.length }}/20</div>
          </div>
          <div>
            <label class="block text-sm font-medium mb-1.5 text-geek-text-secondary">知识库描述</label>
            <textarea
              v-model="newKnowledgeBase.description"
              placeholder="请输入知识库描述"
              maxlength="200"
              rows="3"
              class="w-full geek-input px-4 py-2.5 rounded-md resize-none"
            ></textarea>
            <div class="text-xs mt-1 text-right text-geek-text-faint">{{ newKnowledgeBase.description.length }}/200</div>
          </div>
        </div>
        <div class="flex justify-end gap-3 mt-6">
          <button @click="showCreateKnowledgeForm = false" class="geek-btn geek-btn-ghost">取消</button>
          <button
            @click="createKnowledgeBase"
            :disabled="!newKnowledgeBase.name.trim()"
            class="geek-btn geek-btn-primary"
            :class="{ 'opacity-40 cursor-not-allowed': !newKnowledgeBase.name.trim() }"
          >
            创建
          </button>
        </div>
      </div>
    </div>

    <!-- 检索效果测试弹窗（F5.5） -->
    <div
      v-if="showRetrievalTest"
      class="modal-mask fixed inset-0 z-50 flex items-center justify-center bg-geek-overlay"
      @click.self="showRetrievalTest = false"
    >
      <div class="modal-card animate-modal-in geek-card-elevated rounded-xl w-[560px] max-w-[95vw] max-h-[80vh] flex flex-col overflow-hidden">
        <div class="flex items-center justify-between px-6 py-4 border-b" style="border-color: var(--geek-divider)">
          <h3 class="font-bold tracking-tight text-geek-text">检索效果测试</h3>
          <button @click="showRetrievalTest = false" class="p-1" style="color: var(--geek-text-muted)">
            <X class="w-5 h-5" />
          </button>
        </div>
        <div class="flex-1 min-h-0 overflow-y-auto geek-scroll p-6">
          <div class="flex gap-2 mb-4">
            <input
              v-model="testQuestion"
              type="text"
              placeholder="输入测试问题，验证检索命中效果..."
              class="flex-1 geek-input rounded-md px-4 py-2.5"
              @keyup.enter="runRetrievalTest"
            />
            <button
              @click="runRetrievalTest"
              :disabled="testLoading"
              class="geek-btn geek-btn-primary px-4 py-2.5 whitespace-nowrap"
            >
              {{ testLoading ? '测试中...' : '测试' }}
            </button>
          </div>

          <div v-if="testResults.length === 0 && !testLoading" class="text-center py-10 text-sm" style="color: var(--geek-text-muted)">
            暂无检索结果，请输入问题并点击测试
          </div>

          <div v-else class="space-y-3">
            <div
              v-for="(chunk, i) in testResults"
              :key="i"
              class="geek-card rounded-lg p-4"
            >
              <div class="flex items-center justify-between mb-2">
                <span class="text-xs" style="color: var(--geek-text-secondary)">
                  {{ chunk.document || '未知文档' }}
                </span>
                <span class="text-xs px-2 py-0.5 rounded" style="background: var(--geek-input-bg); color: var(--geek-primary)">
                  相似度 {{ (chunk.similarity * 100).toFixed(1) }}%
                </span>
              </div>
              <p class="text-sm leading-relaxed whitespace-pre-wrap" style="color: var(--geek-text)">{{ chunk.content }}</p>
            </div>
          </div>
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
  Search, Download, Zap, History, Shield
} from 'lucide-vue-next'
import ChatMessages from '../components/ChatMessages.vue'
import ThemeToggle from '../components/ThemeToggle.vue'
import SkeletonLoader from '../components/SkeletonLoader.vue'
import { personaTemplates } from '../composables/usePersonaTemplates'
import { useTheme } from '../composables/useTheme'
import { useMessageSearch } from '../composables/useMessageSearch'
import { useQuickCommands } from '../composables/useQuickCommands'
import { exportChatToMarkdown, exportChatToJson } from '../utils/exportChat'
import { useWebSocket } from '../utils/websocket'
import { useWebRTC } from '../composables/useWebRTC'
import { fetchAssistantsPage, createAssistant, deleteAssistant, updateAssistant, fetchVoices, fetchModels } from '../api/assistant'
import { logout } from '../api/auth'
import { RagflowApi } from '../api/ragflow'
import type { Assistant, DisplayMessage, KnowledgeBase, AsrDeltaData, VoiceInfo, ModelInfo } from '../types'

// 主题与路由
const { themeMode, setTheme } = useTheme()
const router = useRouter()

// 助手数据
const assistants = ref<Assistant[]>([])
const selectedAssistant = ref<Assistant | null>(null)
const pageLoading = ref(true)

// 助手列表分页与搜索
const searchKeyword = ref('')
const assistantPage = ref(1)
const ASSISTANT_PAGE_SIZE = 10
const assistantTotal = ref(0)
const assistantTotalPages = computed(() => Math.max(1, Math.ceil(assistantTotal.value / ASSISTANT_PAGE_SIZE)))

// 用户信息
const userName = typeof localStorage !== 'undefined'
  ? (localStorage.getItem('username') || '用户')
  : '用户'
const userInitial = userName.charAt(0).toUpperCase()
const userRole = typeof localStorage !== 'undefined'
  ? (localStorage.getItem('role') || 'user')
  : 'user'

// 弹窗状态
const showModal = ref(false)
const showDeleteModal = ref(false)
const deleteTarget = ref<Assistant | null>(null)
const formData = ref({ name: '', description: '', personality: '', voice: '', modelName: '', temperature: 0.7, maxTokens: 1024 })

// 音色字典
const voices = ref<VoiceInfo[]>([])
// 模型字典
const models = ref<ModelInfo[]>([])

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
  if (isMarkdown) {
    exportChatToMarkdown(messages.value, name)
  } else {
    exportChatToJson(messages.value, name)
  }
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
const settingsVoice = ref('')
const settingsModelName = ref('')
const settingsTemperature = ref(0.7)
const settingsMaxTokens = ref(1024)

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
  const bot = settingsAssistant.value
  try {
    // 热更新当前会话人设
    ws?.send({ type: 'prompt', content: personalityText.value })
    // 持久化到服务器助手记录，避免切换/重连后人设丢失
    if (bot?.id) {
      await updateAssistant({
        id: bot.id,
        name: bot.name,
        description: bot.description,
        personality: personalityText.value,
        voice: bot.voice,
        knowledgeIds: normalizeKnowledgeIds(bot.knowledgeIds),
      })
      bot.personality = personalityText.value
      if (selectedAssistant.value?.id === bot.id) {
        selectedAssistant.value.personality = personalityText.value
      }
    }
    originalPersonality.value = personalityText.value
    showNotification('人设保存成功！', 'success')
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
const showRetrievalTest = ref(false)
const testQuestion = ref('')
const testResults = ref<Array<{ content: string; similarity: number; document: string }>>([])
const testLoading = ref(false)
const currentFileManagerKB = ref<KnowledgeBase | null>(null)
const currentFiles = ref<any[]>([])
const knowledgeBaseLayout = ref<'list' | 'grid'>('grid')
const selectedKnowledgeBases = ref<KnowledgeBase[]>([])
const currentKnowledgeBase = ref<KnowledgeBase | null>(null)
const isUploading = ref(false)
const uploadProgress = ref(0)
const dragActive = ref(false)
const newKnowledgeBase = ref({ name: '', description: '' })

// 规范化 knowledgeIds（后端可能返回 JSON 字符串或数组）
const normalizeKnowledgeIds = (ids?: string[] | string): string[] | undefined => {
  if (!ids) return undefined
  if (Array.isArray(ids)) return ids
  try {
    const parsed = JSON.parse(ids)
    return Array.isArray(parsed) ? parsed : undefined
  } catch {
    return undefined
  }
}

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

// 加载助手列表（分页 + 关键词搜索）
const loadAssistants = async () => {
  try {
    const result = await fetchAssistantsPage(assistantPage.value, ASSISTANT_PAGE_SIZE, searchKeyword.value)
    assistants.value = result.list
    assistantTotal.value = result.total
    // 当前页超出总页数时回退到最后一页
    if (assistants.value.length === 0 && assistantPage.value > 1) {
      assistantPage.value = Math.max(1, assistantTotalPages.value)
      await loadAssistants()
    }
  } catch (error) {
    console.error('获取助手列表失败:', error)
  }
}

/** 搜索触发：回到第一页并重新加载 */
const onSearchAssistant = () => {
  assistantPage.value = 1
  loadAssistants()
}

/** 上一页 */
const prevAssistantPage = () => {
  if (assistantPage.value <= 1) return
  assistantPage.value -= 1
  loadAssistants()
}

/** 下一页 */
const nextAssistantPage = () => {
  if (assistantPage.value >= assistantTotalPages.value) return
  assistantPage.value += 1
  loadAssistants()
}

// 加载音色字典
const loadVoices = async () => {
  try {
    voices.value = await fetchVoices()
  } catch (error) {
    console.error('获取音色列表失败:', error)
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
  formData.value = { name: '', description: '', personality: '', voice: '', modelName: '', temperature: 0.7, maxTokens: 1024 }
  showModal.value = true
}

// 加载模型字典
const loadModels = async () => {
  try {
    models.value = await fetchModels()
  } catch (error) {
    console.error('获取模型列表失败:', error)
  }
}

// 人设模板一键填充
const onTemplateChange = (event: Event, mode: 'create' | 'settings') => {
  const id = (event.target as HTMLSelectElement).value
  const tpl = personaTemplates.find(t => t.id === id)
  if (!tpl) return
  if (mode === 'create') {
    formData.value.personality = tpl.prompt
  } else {
    personalityText.value = tpl.prompt
  }
}
const closeModal = () => showModal.value = false

// 新增助手
const addAssistant = async () => {
  if (!formData.value.name.trim()) {
    alert('请输入助手名称')
    return
  }
  try {
    await createAssistant({
      ...formData.value,
      modelName: formData.value.modelName || undefined,
    })
    // 新助手排在列表最前，跳回第一页刷新
    assistantPage.value = 1
    searchKeyword.value = ''
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
  settingsVoice.value = bot.voice || ''
  settingsModelName.value = bot.modelName || ''
  settingsTemperature.value = bot.temperature ?? 0.7
  settingsMaxTokens.value = bot.maxTokens ?? 1024
  showSettings.value = true
}
const closeSettingsModal = () => showSettings.value = false

// 保存音色
const saveVoice = async () => {
  const bot = settingsAssistant.value
  if (!bot?.id) return
  try {
    await updateAssistant({
      id: bot.id,
      name: bot.name,
      description: bot.description,
      personality: bot.personality,
      voice: settingsVoice.value,
      knowledgeIds: normalizeKnowledgeIds(bot.knowledgeIds),
    })
    bot.voice = settingsVoice.value
    // 同步选中助手
    if (selectedAssistant.value?.id === bot.id) {
      selectedAssistant.value.voice = settingsVoice.value
    }
    showNotification('音色保存成功！', 'success')
  } catch (error) {
    showNotification(`音色保存失败：${(error as Error).message}`, 'error')
  }
}

// 保存模型参数
const saveModelParams = async () => {
  const bot = settingsAssistant.value
  if (!bot?.id) return
  try {
    await updateAssistant({
      id: bot.id,
      name: bot.name,
      description: bot.description,
      personality: bot.personality,
      voice: bot.voice,
      modelName: settingsModelName.value || undefined,
      temperature: settingsTemperature.value,
      maxTokens: settingsMaxTokens.value,
      knowledgeIds: normalizeKnowledgeIds(bot.knowledgeIds),
    })
    bot.modelName = settingsModelName.value
    bot.temperature = settingsTemperature.value
    bot.maxTokens = settingsMaxTokens.value
    if (selectedAssistant.value?.id === bot.id) {
      selectedAssistant.value.modelName = settingsModelName.value
      selectedAssistant.value.temperature = settingsTemperature.value
      selectedAssistant.value.maxTokens = settingsMaxTokens.value
    }
    showNotification('模型参数保存成功！', 'success')
  } catch (error) {
    showNotification(`模型参数保存失败：${(error as Error).message}`, 'error')
  }
}

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
          } else if (data.type === 'tool_call') {
            messages.value.push({ role: 'tool_call', toolName: data.toolName, text: data.toolArgs || '' })
          } else if (data.type === 'tool_result') {
            const tr = data.data
            messages.value.push({ role: 'tool_result', toolName: tr?.name, toolResult: tr?.result, text: tr?.result || '' })
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
  } catch {
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
        } else if (data.type === 'tool_call') {
          messages.value.push({ role: 'tool_call', toolName: data.toolName, text: data.toolArgs || '' })
        } else if (data.type === 'tool_result') {
          const tr = data.data
          messages.value.push({ role: 'tool_result', toolName: tr?.name, toolResult: tr?.result, text: tr?.result || '' })
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
  knowledgebase?: string | { docCount?: number; docName?: string[] };
  tokenUsage?: { promptTokens?: number; completionTokens?: number }
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
    if (queryData.tokenUsage) {
      lastMsg.tokenUsage = queryData.tokenUsage
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
  // 通知服务端将当前令牌加入黑名单（登出失效）
  const refreshToken = localStorage.getItem('refreshToken') || undefined
  logout(refreshToken).catch(() => {
    // 网络异常不阻塞本地登出
  })
  localStorage.removeItem('token')
  localStorage.removeItem('refreshToken')
  localStorage.removeItem('userId')
  localStorage.removeItem('username')
  router.push('/login')
}

// 加载知识库列表（通过后端代理，密钥零下发）
const loadKnowledgeBases = async () => {
  try {
    knowledgeBases.value = await RagflowApi.getDatasets(1, 1000)
  } catch (error) {
    showNotification(`获取知识库列表失败：${(error as Error).message}`, 'error')
  }
}

// 恢复知识库选择
const restoreKnowledgeBaseSelection = () => {
  if (!selectedAssistant.value?.id) return

  // 优先从服务端持久化的 knowledge_ids 恢复
  const rawIds = selectedAssistant.value.knowledgeIds
  let serverIds: string[] = []
  if (Array.isArray(rawIds)) {
    serverIds = rawIds
  } else if (typeof rawIds === 'string' && rawIds.trim()) {
    try {
      const parsed = JSON.parse(rawIds)
      if (Array.isArray(parsed)) serverIds = parsed
    } catch {
      serverIds = []
    }
  }

  if (serverIds.length > 0 && knowledgeBases.value.length > 0) {
    const matched = serverIds
      .map(id => knowledgeBases.value.find(kb => kb.id === id))
      .filter((kb): kb is KnowledgeBase => !!kb)
    if (matched.length === 1) {
      currentKnowledgeBase.value = matched[0]
      selectedKnowledgeBases.value = []
      return
    }
    if (matched.length > 1) {
      currentKnowledgeBase.value = null
      selectedKnowledgeBases.value = matched
      return
    }
  }

  // 兜底：从 localStorage 恢复
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
  if (idx > -1) {
    selectedKnowledgeBases.value.splice(idx, 1)
  } else {
    selectedKnowledgeBases.value.push(kb)
  }
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
  if (!kbId) {
    currentFiles.value = []
    return
  }
  try {
    currentFiles.value = await RagflowApi.getDocuments(kbId)
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
    // 服务端持久化知识库关联（换设备/浏览器不丢失）
    persistKnowledgeIds(kbIds)
  } else {
    currentKnowledgeBase.value = null
    selectedKnowledgeBases.value = []
    showNotification('已清空知识库选择', 'info')
    ws?.send({ type: 'selectedKbIds', ids: [] })
    persistKnowledgeIds([])
  }
  saveKnowledgeBaseSelection()
  closeKnowledgeModal()
}

/**
 * 服务端持久化助手关联的知识库ID列表
 */
const persistKnowledgeIds = async (ids: string[]) => {
  const assistant = selectedAssistant.value
  if (!assistant?.id) return
  try {
    await updateAssistant({
      id: assistant.id,
      name: assistant.name,
      description: assistant.description,
      personality: assistant.personality,
      voice: assistant.voice,
      knowledgeIds: ids,
    })
  } catch (error) {
    console.error('持久化知识库关联失败:', error)
  }
}

// 检索效果测试（F5.5）
const runRetrievalTest = async () => {
  const ids = currentKnowledgeBase.value
    ? [currentKnowledgeBase.value.id]
    : selectedKnowledgeBases.value.map(kb => kb.id)
  if (!ids.length) {
    showNotification('请先选择知识库', 'warning')
    return
  }
  if (!testQuestion.value.trim()) {
    showNotification('请输入测试问题', 'warning')
    return
  }
  testLoading.value = true
  try {
    testResults.value = await RagflowApi.testRetrieval(testQuestion.value.trim(), ids)
  } catch (error) {
    showNotification(`检索测试失败：${(error as Error).message}`, 'error')
    testResults.value = []
  } finally {
    testLoading.value = false
  }
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
    await RagflowApi.createDataset({
      name: newKnowledgeBase.value.name.trim(),
      description: newKnowledgeBase.value.description.trim(),
    })
    showNotification('知识库创建成功！', 'success')
    await loadKnowledgeBases()
    newKnowledgeBase.value = { name: '', description: '' }
    showCreateKnowledgeForm.value = false
    showKnowledgeModal.value = true
  } catch (error) {
    showNotification(`创建知识库失败：${(error as Error).message}`, 'error')
  }
}

// 删除知识库
const deleteKnowledgeBase = async (kb: KnowledgeBase) => {
  if (!confirm(`确定要删除知识库"${kb.name}"吗？此操作不可撤销。`)) return
  try {
    await RagflowApi.deleteDataset([kb.id])
    showNotification('知识库删除成功！', 'success')
    await loadKnowledgeBases()
    if (currentKnowledgeBase.value?.id === kb.id) currentKnowledgeBase.value = null
    selectedKnowledgeBases.value = selectedKnowledgeBases.value.filter(s => s.id !== kb.id)
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

// 拖拽上传
const handleDrop = (event: DragEvent) => {
  dragActive.value = false
  const files = Array.from(event.dataTransfer?.files || [])
  if (files.length > 0) {
    processFilesForFileManager(files)
  }
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
        const docIds = await RagflowApi.uploadDocument(currentFileManagerKB.value!.id, file)
        uploaded.push(...docIds)
        showNotification(`文件 ${file.name} 上传成功`, 'success')
        uploadProgress.value = Math.round(((i + 1) / total) * 100)
      } catch (error) {
        showNotification(`上传文件 ${file.name} 失败: ${(error as Error).message}`, 'error')
      }
    }

    if (uploaded.length > 0) {
      await loadKnowledgeBaseFiles(currentFileManagerKB.value!.id)
      try {
        await RagflowApi.parseDocuments(currentFileManagerKB.value!.id, uploaded)
      } catch {
        showNotification('文件上传成功，但自动解析失败，请手动解析', 'warning')
      }
    }
  } finally {
    isUploading.value = false
    uploadProgress.value = 0
  }
}

// 删除文件
const deleteFile = async (file: any) => {
  if (!currentFileManagerKB.value) return
  if (!confirm(`确定要删除文件"${file.name}"吗？此操作不可撤销。`)) return
  try {
    await RagflowApi.deleteDocument(currentFileManagerKB.value.id, [file.id])
    showNotification('文件删除成功', 'success')
    await loadKnowledgeBaseFiles(currentFileManagerKB.value!.id)
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
  try {
    await loadVoices()
    await loadModels()
    await loadAssistants()
    await loadKnowledgeBases()
  } finally {
    pageLoading.value = false
  }
  window.addEventListener('keydown', handleKeydown)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', handleKeydown)
  ws?.close()
  voiceWs?.send({ type: 'hangup' })
  voiceWs?.close()
  webrtc.hangup()
})

// 全局快捷键：Esc 关闭最上层弹窗
const handleKeydown = (event: KeyboardEvent) => {
  if (event.key !== 'Escape') return
  if (showQuickCommands.value) {
    showQuickCommands.value = false
    return
  }
  if (showCreateKnowledgeForm.value) {
    showCreateKnowledgeForm.value = false
    return
  }
  if (showFileManager.value) {
    closeFileManager()
    return
  }
  if (showKnowledgeModal.value) {
    closeKnowledgeModal()
    return
  }
  if (showSettings.value) {
    closeSettingsModal()
    return
  }
  if (showDeleteModal.value) {
    cancelDelete()
    return
  }
  if (showModal.value) {
    closeModal()
  }
}
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
  box-shadow: 0 4px 16px var(--geek-shadow-md);
  color: #fff;
  font-weight: 500;
}
.toast-success { background: var(--geek-success); }
.toast-error { background: var(--geek-error); }
.toast-warning { background: var(--geek-warning); }
.toast-info { background: var(--geek-primary); }

/* 侧边栏选中项左侧强调条 */
.assistant-item.item-active {
  border-left: 3px solid var(--geek-accent);
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

/* 拖拽上传高亮 */
.file-list.drag-active {
  background: var(--geek-bg-subtle);
  outline: 2px dashed var(--geek-accent);
  outline-offset: -8px;
}
</style>