<template>
  <div
    class="h-screen w-full flex flex-col bg-gradient-to-br from-blue-300 via-pink-300 to-yellow-200 bg-[length:400%_400%] animate-gradient-diagonal relative overflow-hidden antialiased">
    <transition name="notification">
      <div v-if="notification.show" :class="[
        'fixed top-4 right-4 z-50 px-6 py-3 rounded-2xl shadow-2xl backdrop-blur-xl ring-1 ring-white/20 text-white font-medium',
        notification.type === 'success' ? 'bg-green-500/90' :
          notification.type === 'error' ? 'bg-red-500/90' :
            notification.type === 'warning' ? 'bg-yellow-500/90' : 'bg-blue-500/90'
      ]">
        {{ notification.message }}
      </div>
    </transition>

    <div class="flex flex-1 gap-6 z-10 relative p-6"
      :class="{ 'blur-sm pointer-events-none': showCreateKnowledgeForm }">
      <div
        class="w-1/2 p-6 bg-white/20 backdrop-blur-xl rounded-3xl shadow-2xl ring-1 ring-white/30 flex flex-col gap-6">
        <div class="text-center">
          <h2 class="text-2xl font-bold text-gray-800 drop-shadow-sm mb-2">{{ currentAssistant?.name || '默认机器人' }}</h2>
          <p class="text-gray-600">{{ currentAssistant?.description || '这是一个可以用于简单测试对话的智能助手' }}</p>
        </div>

        <div class="flex-1 flex flex-col">
          <div class="flex items-center justify-between mb-4">
            <h3 class="text-lg font-semibold text-gray-800">人设设置</h3>
            <button @click="savePersonality" :disabled="!personalityChanged" :class="[
              'px-6 py-2 rounded-xl font-medium transition-all duration-300 transform active:scale-95',
              personalityChanged
                ? 'bg-blue-500/80 backdrop-blur-lg text-white hover:bg-blue-600/80 shadow-lg ring-1 ring-blue-400/30'
                : 'bg-gray-300/50 text-gray-500 cursor-not-allowed'
            ]">
              {{ saving ? '保存中...' : '保存人设' }}
            </button>
          </div>
          <textarea v-model="personalityText" @input="onPersonalityChange"
            class="w-full flex-1 resize-none bg-white/30 backdrop-blur-lg rounded-2xl p-4 text-gray-800 placeholder:text-gray-500 focus:outline-none focus:ring-2 focus:ring-blue-400/50 transition-all duration-300 ring-1 ring-white/40 shadow-inner"
            placeholder="请输入机器人的人设描述，例如：你是一个专业的客服助手，擅长解答用户问题..."></textarea>
          <div class="text-xs text-gray-600 mt-2 text-right">
            {{ personalityText.length }}/500 字符
          </div>
        </div>

        <div class="bg-white/25 backdrop-blur-lg rounded-2xl p-5 ring-1 ring-white/40 shadow-lg">
          <div class="flex items-center justify-between mb-4">
            <h3 class="text-lg font-semibold text-gray-800">选择知识库</h3>
            <div class="flex gap-3">
              <button @click="openKnowledgeModal"
                class="px-6 py-3 bg-blue-500/80 backdrop-blur-lg text-white rounded-xl hover:bg-blue-600/80 transition-all duration-300 font-medium ring-1 ring-blue-400/30 transform active:scale-95 shadow-lg">
                管理知识库
              </button>
              <button @click="goBack"
                class="px-6 py-3 bg-gray-500/60 backdrop-blur-lg text-white rounded-xl hover:bg-gray-600/60 transition-all duration-300 ring-1 ring-gray-400/30 transform active:scale-95 shadow-lg">
                返回
              </button>
            </div>
          </div>

          <div v-if="currentKnowledgeBase"
            class="bg-white/40 backdrop-blur-lg px-4 py-3 rounded-xl ring-1 ring-white/50 mb-4">
            <div class="flex items-center justify-between">
              <div class="flex items-center">
                <Database class="w-5 h-5 text-blue-600 mr-3" />
                <div>
                  <div class="font-medium text-gray-800">{{ currentKnowledgeBase.name }}</div>
                  <div class="text-xs text-gray-600">
                    {{ currentKnowledgeBase.documentCount || 0 }} 个文档 · {{ currentKnowledgeBase.chunkCount || 0 }} 切片
                  </div>
                </div>
              </div>
              <span class="text-xs text-green-600 bg-green-100/50 px-2 py-1 rounded-lg">已选择</span>
            </div>
          </div>

          <div v-else-if="selectedKnowledgeBases.length > 0"
            class="bg-white/40 backdrop-blur-lg px-4 py-3 rounded-xl ring-1 ring-white/50 mb-4">
            <div class="flex items-center justify-between mb-3">
              <div class="flex items-center">
                <Database class="w-5 h-5 text-purple-600 mr-3" />
                <div>
                  <div class="font-medium text-gray-800">已选择 {{ selectedKnowledgeBases.length }} 个知识库</div>
                  <div class="text-xs text-gray-600">多知识库联合检索模式</div>
                </div>
              </div>
              <div class="flex items-center gap-2">
                <span class="text-xs text-purple-600 bg-purple-100/50 px-2 py-1 rounded-lg">多选模式</span>
                <button @click="clearMultiSelection"
                  class="text-xs text-red-600 hover:text-red-700 bg-red-100/50 hover:bg-red-200/50 px-2 py-1 rounded-lg transition-colors">
                  清空
                </button>
              </div>
            </div>
            <div class="space-y-2 max-h-32 overflow-y-auto transparent-scrollbar">
              <div v-for="kb in selectedKnowledgeBases" :key="kb.id"
                class="flex items-center justify-between bg-white/30 px-3 py-2 rounded-lg">
                <div class="flex items-center">
                  <Check class="w-4 h-4 text-blue-500 mr-2" />
                  <span class="text-sm text-gray-700">{{ kb.name }}</span>
                </div>
                <div class="flex items-center gap-2">
                  <span class="text-xs text-gray-500">{{ kb.documentCount || 0 }} 文档</span>
                  <button @click.stop="removeFromSelection(kb)"
                    class="text-red-500 hover:text-red-700 transition-colors">
                    <X class="w-3 h-3" />
                  </button>
                </div>
              </div>
            </div>
          </div>

          <div v-else
            class="bg-white/30 backdrop-blur-lg px-4 py-6 rounded-xl ring-1 ring-white/50 text-center text-gray-600">
            <FolderOpen class="w-8 h-8 text-gray-400 mx-auto mb-2" />
            <p>点击"管理知识库"选择或创建知识库</p>
          </div>
        </div>
      </div>

      <div class="w-1/2 bg-white/20 backdrop-blur-xl rounded-3xl shadow-2xl ring-1 ring-white/30 flex flex-col">
        <div class="flex justify-between items-center px-6 py-5 border-b border-white/30">
          <h3 class="text-xl font-semibold text-gray-800">与 {{ currentAssistant?.name || '机器人' }} 对话测试</h3>
          <div class="flex items-center gap-3">
            <div v-if="voiceCallActive" class="flex items-center gap-2">
              <span class="relative flex h-3 w-3">
                <span class="animate-ping absolute inline-flex h-full w-full rounded-full bg-red-400 opacity-75"></span>
                <span class="relative inline-flex rounded-full h-3 w-3 bg-red-500"></span>
              </span>
              <span class="text-sm text-red-600 font-medium">语音通话中</span>
            </div>
            <button @click="resetChat"
              class="text-sm text-gray-600 hover:text-gray-800 hover:bg-white/20 px-3 py-1 rounded-lg transition-all duration-300">
              重置对话
            </button>
          </div>
        </div>

        <div v-if="asrText && voiceCallActive"
          class="mx-6 mt-3 px-4 py-2 bg-blue-50/80 rounded-xl border border-blue-200/50 text-sm text-blue-700">
          <span class="font-medium">语音识别：</span>{{ asrText }}
        </div>

        <div class="flex-1 min-h-0">
          <ChatMessages :messages="messages" :auto-scroll="true"
            @scroll-state-change="handleScrollStateChange" class="h-full transparent-scrollbar" />
        </div>

        <div class="border-t border-white/30 p-5">
          <div v-if="voiceCallActive" class="flex flex-col items-center gap-4 py-2">
            <div class="flex items-center gap-4">
              <div class="relative">
                <Mic class="w-8 h-8 text-red-500 animate-pulse" />
                <div class="absolute -inset-2 rounded-full border-2 border-red-400/50 animate-ping"></div>
              </div>
              <div class="flex items-center gap-1">
                <div v-for="i in 5" :key="i" class="w-1.5 rounded-full bg-blue-400 transition-all duration-150"
                  :style="{
                    height: `${Math.max(4, audioLevel * 40 * (0.5 + Math.random() * 0.5))}px`,
                    opacity: audioLevel > (i - 1) * 0.2 ? 1 : 0.3
                  }"></div>
              </div>
            </div>
            <button @click="endVoiceCall"
              class="px-8 py-2.5 bg-red-500/80 text-white rounded-xl hover:bg-red-600/80 transition-all duration-300 font-medium shadow-lg ring-1 ring-red-400/30 transform active:scale-95">
              <PhoneOff class="w-4 h-4 inline mr-2" />
              挂断
            </button>
          </div>

          <div v-else class="flex items-center gap-3">
            <input v-model="inputText" type="text"
              class="flex-1 bg-white/30 backdrop-blur-lg rounded-xl px-4 py-3 text-gray-800 placeholder:text-gray-500 focus:outline-none focus:ring-2 focus:ring-blue-400/50 transition-all duration-300 ring-1 ring-white/40 shadow-inner"
              placeholder="请输入您想问的问题" @keyup.enter="sendMessage" :disabled="isTyping" />
            <button @click="startVoiceCall" :disabled="!currentAssistant?.id"
              :class="[
                'px-4 py-3 rounded-xl font-medium transition-all duration-300 transform active:scale-95',
                !currentAssistant?.id
                  ? 'bg-gray-300/50 text-gray-500 cursor-not-allowed'
                  : 'bg-purple-500/80 backdrop-blur-lg text-white hover:bg-purple-600/80 shadow-lg ring-1 ring-purple-400/30'
              ]">
              <Mic class="w-5 h-5" />
            </button>
            <button @click="sendMessage" :disabled="!inputText.trim() || isTyping" :class="[
              'px-6 py-3 rounded-xl font-medium transition-all duration-300 transform active:scale-95',
              (!inputText.trim() || isTyping)
                ? 'bg-gray-300/50 text-gray-500 cursor-not-allowed'
                : 'bg-blue-500/80 backdrop-blur-lg text-white hover:bg-blue-600/80 shadow-lg ring-1 ring-blue-400/30'
            ]">
              发送
            </button>
          </div>
        </div>
      </div>
    </div>

    <div v-if="showKnowledgeModal"
      class="fixed inset-0 z-40 flex items-center justify-center bg-black/30 backdrop-blur-md"
      @click.self="closeKnowledgeModal">
      <div :class="[
        'animate-modal-in bg-white/10 backdrop-blur-xl max-h-[80vh] rounded-3xl shadow-2xl ring-1 ring-white/20 flex overflow-hidden transition-all duration-500',
        showFileManager ? 'w-[1400px]' : 'w-[900px]'
      ]">
        <div :class="[
          'transition-all duration-500 ease-in-out flex flex-col',
          showFileManager ? 'w-1/2' : 'w-full'
        ]">
          <div class="p-8 border-b border-white/20">
            <div class="flex items-center justify-between mb-6">
              <h2 class="text-2xl font-bold text-white">知识库管理</h2>
              <div class="flex items-center gap-4">
                <div class="flex bg-white/10 rounded-lg p-1">
                  <button @click="knowledgeBaseLayout = 'list'" :class="[
                    'px-3 py-1 rounded text-sm transition-all duration-200',
                    knowledgeBaseLayout === 'list'
                      ? 'bg-blue-500 text-white'
                      : 'text-white/60 hover:text-white'
                  ]">
                    <List class="w-4 h-4" />
                  </button>
                  <button @click="knowledgeBaseLayout = 'grid'" :class="[
                    'px-3 py-1 rounded text-sm transition-all duration-200',
                    knowledgeBaseLayout === 'grid'
                      ? 'bg-blue-500 text-white'
                      : 'text-white/60 hover:text-white'
                  ]">
                    <LayoutGrid class="w-4 h-4" />
                  </button>
                </div>
                <button @click="closeKnowledgeModal" class="text-white/60 hover:text-white transition-colors">
                  <X class="w-6 h-6" />
                </button>
              </div>
            </div>

            <div class="flex gap-3">
              <button @click="openCreateKnowledgeForm"
                class="px-4 py-2 bg-blue-500/80 text-white rounded-xl hover:bg-blue-600/80 transition-all duration-300 font-medium">
                <Plus class="w-4 h-4 inline mr-2" />
                新建知识库
              </button>
            </div>
          </div>

          <div class="flex-1 min-h-0 overflow-y-auto transparent-scrollbar p-8">
            <div :class="[
              'transition-all duration-300',
              knowledgeBaseLayout === 'grid' ? 'grid grid-cols-2 gap-4' : 'grid grid-cols-1 gap-4'
            ]">
              <div v-for="kb in knowledgeBases" :key="kb.id" @click="selectKnowledgeBase(kb)" :class="[
                'bg-white/20 backdrop-blur-lg rounded-2xl p-4 cursor-pointer transition-all duration-300 ring-1 ring-white/30 hover:bg-white/30 transform hover:scale-[1.02]',
                selectedKnowledgeBases.some(selected => selected.id === kb.id) ? 'ring-2 ring-blue-400 bg-blue-500/20' : '',
                knowledgeBaseLayout === 'list' ? 'flex items-center' : 'block'
              ]">
                <template v-if="knowledgeBaseLayout === 'list'">
                  <div class="flex items-center">
                    <div class="mr-3 flex-shrink-0">
                      <div :class="[
                        'w-5 h-5 rounded border-2 flex items-center justify-center transition-all duration-200',
                        selectedKnowledgeBases.some(selected => selected.id === kb.id)
                          ? 'bg-blue-500 border-blue-500'
                          : 'border-white/40 hover:border-white/60'
                      ]">
                        <Check v-if="selectedKnowledgeBases.some(selected => selected.id === kb.id)"
                          class="w-3 h-3 text-white" />
                      </div>
                    </div>
                    <div class="flex items-center flex-1 min-w-0">
                      <Database class="w-6 h-6 text-yellow-400 mr-3 flex-shrink-0" />
                      <div class="flex-1 min-w-0">
                        <h3 class="font-bold text-white text-base truncate">{{ kb.name }}</h3>
                        <p class="text-white/60 text-sm truncate">{{ kb.description || '暂无描述' }}</p>
                      </div>
                    </div>
                    <div class="flex items-center gap-4 mx-4 flex-shrink-0">
                      <div class="text-center">
                        <div class="text-blue-300 font-bold text-sm">{{ kb.documentCount || 0 }}</div>
                        <div class="text-white/50 text-xs">文档</div>
                      </div>
                    </div>
                    <div class="flex gap-1 flex-shrink-0">
                      <button @click.stop="selectKnowledgeBaseForFileManager(kb)"
                        class="text-blue-400 hover:text-blue-300 transition-colors p-1.5 rounded hover:bg-white/10">
                        <FolderOpen class="w-4 h-4" />
                      </button>
                      <button @click.stop="deleteKnowledgeBase(kb)"
                        class="text-red-400 hover:text-red-300 transition-colors p-1.5 rounded hover:bg-white/10">
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
                          selectedKnowledgeBases.some(selected => selected.id === kb.id)
                            ? 'bg-blue-500 border-blue-500'
                            : 'border-white/40 hover:border-white/60'
                        ]">
                          <Check v-if="selectedKnowledgeBases.some(selected => selected.id === kb.id)"
                            class="w-3 h-3 text-white" />
                        </div>
                      </div>
                      <Database class="w-8 h-8 text-yellow-400 mr-3" />
                      <div>
                        <h3 class="font-bold text-white text-lg">{{ kb.name }}</h3>
                        <p class="text-white/70 text-sm">{{ kb.description || '暂无描述' }}</p>
                      </div>
                    </div>
                    <div class="flex gap-2">
                      <button @click.stop="selectKnowledgeBaseForFileManager(kb)"
                        class="text-blue-400 hover:text-blue-300 transition-colors p-1 rounded hover:bg-white/10">
                        <FolderOpen class="w-5 h-5" />
                      </button>
                      <button @click.stop="deleteKnowledgeBase(kb)"
                        class="text-red-400 hover:text-red-300 transition-colors p-1 rounded hover:bg-white/10">
                        <Trash2 class="w-5 h-5" />
                      </button>
                    </div>
                  </div>
                  <div class="flex justify-between text-sm">
                    <div class="text-center">
                      <div class="text-blue-300 font-bold text-lg">{{ kb.documentCount || 0 }}</div>
                      <div class="text-white/60">包含文档数</div>
                    </div>
                    <div class="text-center">
                      <div class="text-purple-300 font-bold text-lg">{{ kb.chunkCount || 0 }}</div>
                      <div class="text-white/60">切片数</div>
                    </div>
                  </div>
                </template>
              </div>
            </div>
          </div>

          <div class="p-8 pt-4 border-t border-white/20">
            <div class="mb-4">
              <div class="flex items-center justify-between mb-2">
                <span class="text-white/80 text-sm font-medium">
                  已选择 {{ selectedKnowledgeBases.length }} 个知识库
                </span>
                <button v-if="selectedKnowledgeBases.length > 0" @click="clearAllSelections"
                  class="text-red-400 hover:text-red-300 text-sm transition-colors">
                  清空选择
                </button>
              </div>
              <div v-if="selectedKnowledgeBases.length > 0" class="flex flex-wrap gap-2">
                <div v-for="kb in selectedKnowledgeBases" :key="kb.id"
                  class="bg-blue-500/20 text-blue-300 px-3 py-1 rounded-lg text-sm flex items-center gap-2">
                  <span>{{ kb.name }}</span>
                  <button @click="removeFromSelection(kb)" class="text-blue-300 hover:text-blue-200 transition-colors">
                    <X class="w-3 h-3" />
                  </button>
                </div>
              </div>
              <div v-else class="text-white/50 text-sm">
                未选择任何知识库
              </div>
            </div>
            <div class="flex justify-end gap-3">
              <button @click="closeKnowledgeModal"
                class="px-6 py-2 bg-gray-500/60 text-white rounded-xl hover:bg-gray-600/60 transition-all duration-300">
                取消
              </button>
              <button @click="confirmSelection"
                class="px-6 py-2 bg-blue-500/80 text-white rounded-xl hover:bg-blue-600/80 transition-all duration-300 font-medium">
                确认选择
              </button>
            </div>
          </div>
        </div>

        <div v-if="showFileManager"
          class="w-1/2 bg-white/5 backdrop-blur-lg border-l border-white/20 flex flex-col animate-slide-in-right">
          <div class="p-6 border-b border-white/20">
            <div class="flex items-center justify-between mb-4">
              <div class="flex items-center">
                <button @click="closeFileManager" class="text-white/60 hover:text-white transition-colors mr-4">
                  <ChevronLeft class="w-5 h-5" />
                </button>
                <h3 class="text-xl font-bold text-white">文件管理</h3>
              </div>
              <div class="flex gap-2">
                <button @click="triggerFileUpload" :disabled="isUploading"
                  class="px-3 py-2 bg-purple-500/80 text-white rounded-lg hover:bg-purple-600/80 transition-all duration-300 text-sm disabled:opacity-50 disabled:cursor-not-allowed">
                  <Upload class="w-4 h-4 inline mr-1" />
                  {{ isUploading ? '上传中...' : '上传文件' }}
                </button>
              </div>
            </div>

            <div v-if="isUploading && uploadProgress > 0" class="mb-4">
              <div class="flex justify-between text-sm text-white/80 mb-2">
                <span>上传进度</span>
                <span>{{ Math.round(uploadProgress) }}%</span>
              </div>
              <div class="w-full bg-white/20 rounded-full h-2">
                <div class="bg-purple-500 h-2 rounded-full transition-all duration-300"
                  :style="{ width: uploadProgress + '%' }"></div>
              </div>
            </div>

            <div v-if="currentFileManagerKB" class="bg-white/10 rounded-lg p-3">
              <div class="flex items-center">
                <Database class="w-5 h-5 text-yellow-400 mr-2" />
                <span class="text-white font-medium">{{ currentFileManagerKB.name }}</span>
                <span class="text-white/60 text-sm ml-2">{{ currentFileManagerKB.description }}</span>
              </div>
            </div>
          </div>

          <div class="flex-1 overflow-y-auto transparent-scrollbar p-6">
            <div v-if="currentFiles.length === 0" class="text-center text-white/60 py-12">
              <FileText class="w-16 h-16 mx-auto mb-4 text-white/40" />
              <p class="text-lg">暂无文件</p>
              <p class="text-sm">点击上传文件按钮添加文档</p>
            </div>
            <div v-else class="space-y-3">
              <div v-for="file in currentFiles" :key="file.id"
                class="bg-white/10 backdrop-blur-lg rounded-lg p-4 hover:bg-white/20 transition-all duration-300">
                <div class="flex items-center justify-between">
                  <div class="flex items-center flex-1 min-w-0">
                    <FileText class="w-8 h-8 text-blue-400 mr-3 flex-shrink-0" />
                    <div class="flex-1 min-w-0">
                      <h4 class="text-white font-medium truncate">{{ file.name }}</h4>
                      <div class="flex items-center text-sm text-white/60 mt-1">
                        <span>{{ formatFileSize(file.size) }}</span>
                        <span class="mx-2">·</span>
                        <span>{{ file.run || '未知' }}</span>
                      </div>
                    </div>
                  </div>
                  <div class="flex items-center gap-2 ml-4">
                    <button @click="deleteFile(file)" class="text-red-400 hover:text-red-300 transition-colors">
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

    <div v-if="showCreateKnowledgeForm"
      class="fixed inset-0 z-50 flex items-center justify-center bg-black/10 backdrop-blur-md"
      @click.self="showCreateKnowledgeForm = false">
      <div
        class="animate-modal-in bg-black/50 backdrop-blur-xl w-96 p-8 rounded-3xl shadow-2xl ring-1 ring-white/30 border border-white/20">
        <h3 class="text-xl font-bold text-white mb-6">创建知识库</h3>
        <div class="space-y-4">
          <div>
            <label class="block text-white text-sm font-medium mb-2">
              <span class="text-red-500">*</span> 知识库名称：
            </label>
            <input v-model="newKnowledgeBase.name" type="text" placeholder="请输入知识库名称" maxlength="20"
              class="w-full px-4 py-3 rounded-xl border border-gray-200 bg-gray-200 text-black placeholder:text-gray-800 focus:outline-none focus:ring-2 focus:ring-blue-400/50 focus:border-blue-400 transition" />
            <div class="text-xs text-gray-500 mt-1 text-right">{{ newKnowledgeBase.name.length }}/20</div>
          </div>
          <div>
            <label class="block text-white text-sm font-medium mb-2">知识库描述：</label>
            <textarea v-model="newKnowledgeBase.description" placeholder="请输入知识库描述" maxlength="200" rows="3"
              class="w-full px-4 py-3 rounded-xl border border-gray-200 bg-gray-200 text-black placeholder:text-gray-800 focus:outline-none focus:ring-2 focus:ring-blue-400/50 focus:border-blue-400 transition resize-none" />
            <div class="text-xs text-gray-500 mt-1 text-right">{{ newKnowledgeBase.description.length }}/200</div>
          </div>
        </div>
        <div class="flex justify-end gap-3 mt-6">
          <button @click="showCreateKnowledgeForm = false"
            class="px-6 py-2 bg-gray-500 text-white rounded-xl hover:bg-gray-600 transition-all duration-300">
            取消
          </button>
          <button @click="createKnowledgeBase" :disabled="!newKnowledgeBase.name.trim()"
            class="px-6 py-2 rounded-xl transition-all duration-300 font-medium bg-blue-500 text-white hover:bg-blue-600 disabled:opacity-50 disabled:cursor-not-allowed">
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
  Trash2, ChevronLeft, Upload, FileText, Mic, PhoneOff
} from 'lucide-vue-next'
import ChatMessages from '../components/ChatMessages.vue'
import { useWebSocket } from '../utils/websocket'
import { useWebRTC } from '../composables/useWebRTC'
import { fetchAssistant, fetchKnowledgeConfig } from '../api/assistant'
import type { Assistant, DisplayMessage, KnowledgeBase, RAGFlowConfig, AsrDeltaData } from '../types'

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
  animation: slide-in-right 0.5s ease-out;
}

.notification-enter-active,
.notification-leave-active {
  transition: all 0.3s ease;
}

.notification-enter-from {
  opacity: 0;
  transform: translateX(100%);
}

.notification-leave-to {
  opacity: 0;
  transform: translateX(100%);
}
</style>
