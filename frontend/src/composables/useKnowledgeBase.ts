import { ref, reactive } from 'vue'
import type { KnowledgeBase } from '../types'
import { RagflowApi } from '../api/ragflow'

export interface KnowledgeBaseFile {
  id: string
  name: string
  size: number
  type: string
  run?: string
  chunkCount?: number
  progress?: number
}

export interface UseKnowledgeBaseOptions {
  getAssistantId: () => string | null | undefined
  onKbSelected?: (ids: string[]) => void
  onNotification?: (message: string, type: 'success' | 'error' | 'warning' | 'info') => void
}

export function useKnowledgeBase(options: UseKnowledgeBaseOptions) {
  const knowledgeBases = ref<KnowledgeBase[]>([])
  const showKnowledgeModal = ref(false)
  const showCreateKnowledgeForm = ref(false)
  const showFileManager = ref(false)
  const currentFileManagerKB = ref<KnowledgeBase | null>(null)
  const currentFiles = ref<KnowledgeBaseFile[]>([])
  const knowledgeBaseLayout = ref<'list' | 'grid'>('grid')
  const selectedKnowledgeBases = ref<KnowledgeBase[]>([])
  const currentKnowledgeBase = ref<KnowledgeBase | null>(null)
  const isUploading = ref(false)
  const uploadProgress = ref(0)

  const newKnowledgeBase = reactive({
    name: '',
    description: '',
  })

  const notify = options.onNotification || ((_msg: string, _type: string) => {})

  const loadKnowledgeBases = async () => {
    try {
      knowledgeBases.value = await RagflowApi.getDatasets(1, 1000)
    } catch (error) {
      console.error('获取知识库列表失败:', error)
      notify(`获取知识库列表失败: ${(error as Error).message}`, 'error')
    }
  }

  const restoreKnowledgeBaseSelection = () => {
    const assistantId = options.getAssistantId()
    if (!assistantId) return
    try {
      const saved = localStorage.getItem(`knowledgeBaseSelection_${assistantId}`)
      if (saved) {
        const selectionData = JSON.parse(saved)
        const isExpired = Date.now() - selectionData.timestamp > 24 * 60 * 60 * 1000
        if (!isExpired) {
          currentKnowledgeBase.value = selectionData.currentKnowledgeBase
          selectedKnowledgeBases.value = selectionData.selectedKnowledgeBases || []
        } else {
          localStorage.removeItem(`knowledgeBaseSelection_${assistantId}`)
        }
      }
    } catch (error) {
      console.error('恢复知识库选择状态失败:', error)
    }
  }

  const saveKnowledgeBaseSelection = () => {
    const assistantId = options.getAssistantId()
    if (!assistantId) return
    const selectionData = {
      currentKnowledgeBase: currentKnowledgeBase.value,
      selectedKnowledgeBases: selectedKnowledgeBases.value,
      timestamp: Date.now(),
    }
    localStorage.setItem(`knowledgeBaseSelection_${assistantId}`, JSON.stringify(selectionData))
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
    try {
      currentFiles.value = await RagflowApi.getDocuments(knowledgeBaseId)
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
        notify(`已选择知识库：${currentKnowledgeBase.value.name}`, 'success')
        if (options.onKbSelected) {
          options.onKbSelected([currentKnowledgeBase.value.id])
        }
      } else {
        const names = selectedKnowledgeBases.value.map(kb => kb.name).join('、')
        notify(`已选择 ${selectedKnowledgeBases.value.length} 个知识库：${names}`, 'success')
        currentKnowledgeBase.value = null
        if (options.onKbSelected) {
          options.onKbSelected(selectedKnowledgeBases.value.map(kb => kb.id))
        }
      }
    } else {
      currentKnowledgeBase.value = null
      selectedKnowledgeBases.value = []
      notify('已清空知识库选择', 'info')
      if (options.onKbSelected) {
        options.onKbSelected([])
      }
    }

    saveKnowledgeBaseSelection()
    closeKnowledgeModal()
  }

  const clearAllSelections = () => {
    selectedKnowledgeBases.value = []
    notify('已清空所有选择', 'info')
  }

  const clearMultiSelection = () => {
    selectedKnowledgeBases.value = []
    currentKnowledgeBase.value = null
    notify('已清空知识库选择', 'info')
    const assistantId = options.getAssistantId()
    if (assistantId) {
      localStorage.removeItem(`knowledgeBaseSelection_${assistantId}`)
    }
  }

  const removeFromSelection = (kb: KnowledgeBase) => {
    const index = selectedKnowledgeBases.value.findIndex(selected => selected.id === kb.id)
    if (index > -1) {
      selectedKnowledgeBases.value.splice(index, 1)
      notify(`已移除：${kb.name}`, 'info')
    }
  }

  const openCreateKnowledgeForm = () => {
    showKnowledgeModal.value = false
    showCreateKnowledgeForm.value = true
  }

  const createKnowledgeBase = async () => {
    if (!newKnowledgeBase.name.trim()) {
      notify('请输入知识库名称', 'warning')
      return
    }

    try {
      await RagflowApi.createDataset({
        name: newKnowledgeBase.name,
        description: newKnowledgeBase.description,
      })
      notify('知识库创建成功', 'success')
      await loadKnowledgeBases()
      newKnowledgeBase.name = ''
      newKnowledgeBase.description = ''
      showCreateKnowledgeForm.value = false
      showKnowledgeModal.value = true
    } catch (error) {
      console.error('创建知识库失败:', error)
      notify(`创建知识库失败: ${(error as Error).message}`, 'error')
    }
  }

  const deleteKnowledgeBase = async (kb: KnowledgeBase) => {
    if (!confirm(`确定要删除知识库"${kb.name}"吗？此操作不可撤销。`)) return

    try {
      await RagflowApi.deleteDataset([kb.id])
      notify('知识库删除成功', 'success')
      await loadKnowledgeBases()
      if (currentKnowledgeBase.value?.id === kb.id) {
        currentKnowledgeBase.value = null
      }
      selectedKnowledgeBases.value = selectedKnowledgeBases.value.filter(selected => selected.id !== kb.id)
    } catch (error) {
      console.error('删除知识库失败:', error)
      notify(`删除知识库失败: ${(error as Error).message}`, 'error')
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
          notify(`文件 ${file.name} 超过50MB限制`, 'error')
          continue
        }

        try {
          const docIds = await RagflowApi.uploadDocument(currentFileManagerKB.value.id, file)
          uploadedDocs.push(...docIds)
          notify(`文件 ${file.name} 上传成功`, 'success')
          uploadProgress.value = Math.round(((i + 1) / totalFiles) * 100)
        } catch (error) {
          console.error(`上传文件 ${file.name} 失败:`, error)
          notify(`上传文件 ${file.name} 失败: ${(error as Error).message}`, 'error')
        }
      }

      if (uploadedDocs.length > 0) {
        notify(`成功上传 ${uploadedDocs.length} 个文件`, 'success')
        await loadKnowledgeBaseFiles(currentFileManagerKB.value.id)

        try {
          await RagflowApi.parseDocuments(currentFileManagerKB.value.id, uploadedDocs)
        } catch (parseError) {
          console.warn('文档解析失败，但文件已成功上传:', parseError)
          notify('文件上传成功，但自动解析失败，请手动解析', 'warning')
        }
      }
    } catch (error) {
      console.error('批量上传失败:', error)
      notify(`批量上传失败: ${(error as Error).message}`, 'error')
    } finally {
      isUploading.value = false
      uploadProgress.value = 0
    }
  }

  const deleteFile = async (file: KnowledgeBaseFile) => {
    if (!currentFileManagerKB.value) return
    if (!confirm(`确定要删除文件"${file.name}"吗？此操作不可撤销。`)) return

    try {
      await RagflowApi.deleteDocument(currentFileManagerKB.value.id, [file.id])
      notify('文件删除成功', 'success')
      await loadKnowledgeBaseFiles(currentFileManagerKB.value.id)
    } catch (error) {
      console.error('删除文件失败:', error)
      notify(`删除文件失败: ${(error as Error).message}`, 'error')
    }
  }

  const formatFileSize = (bytes: number): string => {
    if (bytes === 0) return '0 Bytes'
    const k = 1024
    const sizes = ['Bytes', 'KB', 'MB', 'GB']
    const i = Math.floor(Math.log(bytes) / Math.log(k))
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i]
  }

  const getSelectedKbIds = (): string[] => {
    if (currentKnowledgeBase.value) {
      return [currentKnowledgeBase.value.id]
    }
    if (selectedKnowledgeBases.value.length > 0) {
      return selectedKnowledgeBases.value.map(kb => kb.id)
    }
    return []
  }

  return {
    knowledgeBases,
    showKnowledgeModal,
    showCreateKnowledgeForm,
    showFileManager,
    currentFileManagerKB,
    currentFiles,
    knowledgeBaseLayout,
    selectedKnowledgeBases,
    currentKnowledgeBase,
    isUploading,
    uploadProgress,
    newKnowledgeBase,

    loadKnowledgeBases,
    restoreKnowledgeBaseSelection,
    saveKnowledgeBaseSelection,
    openKnowledgeModal,
    closeKnowledgeModal,
    selectKnowledgeBase,
    selectKnowledgeBaseForFileManager,
    closeFileManager,
    loadKnowledgeBaseFiles,
    confirmSelection,
    clearAllSelections,
    clearMultiSelection,
    removeFromSelection,
    openCreateKnowledgeForm,
    createKnowledgeBase,
    deleteKnowledgeBase,
    triggerFileUpload,
    processFilesForFileManager,
    deleteFile,
    formatFileSize,
    getSelectedKbIds,
  }
}
