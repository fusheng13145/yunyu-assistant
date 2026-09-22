import type { KnowledgeBase } from '../types'
import { getAuthHeaders } from './auth'

interface RAGFlowPaginatedData {
  docs?: RAGFlowDocument[]
  datasets?: RAGFlowDataset[]
  [key: string]: unknown
}

interface RAGFlowDataset {
  id: string
  name: string
  description: string
  document_count?: number
  chunk_count?: number
  token_count?: number
}

interface RAGFlowDocument {
  id: string
  name: string
  size?: number
  type?: string
  run?: string
  chunk_count?: number
  progress?: number
}

/** 文档信息（getDocuments 返回的规范化结构） */
export interface RAGFlowDocumentInfo {
  id: string
  name: string
  size: number
  type: string
  run?: string
  chunkCount: number
  progress: number
}

class RagflowApiError extends Error {
  constructor(message: string, public status?: number) {
    super(message)
    this.name = 'RagflowApiError'
  }
}

/** 后端代理基础路径（所有 RAGFlow 请求均通过后端转发，避免暴露 apiKey） */
const PROXY_BASE = '/api/ragflow'

/**
 * 通过后端代理发起请求（统一处理认证头，不再需要前端传递 RAGFlow apiKey）
 */
async function proxyFetch<T>(url: string, options: RequestInit = {}): Promise<T> {
  const response = await fetch(url, {
    ...options,
    headers: {
      ...getAuthHeaders(),
      ...(options.headers as Record<string, string> || {}),
    },
  })

  if (!response.ok) {
    throw new RagflowApiError(`请求失败: ${response.status}`, response.status)
  }

  const result = await response.json()
  // 后端代理直接透传 RAGFlow 原始响应格式（code=0 表示成功）
  if (result.code !== 0) {
    throw new RagflowApiError(result.message || '请求失败')
  }
  return result.data as T
}

export class RagflowApi {
  /**
   * 获取 RAGFlow 配置信息（仅 endpoint，不含 apiKey）
   * 用于获取服务地址等非敏感配置
   */
  static async getConfig(): Promise<{ endpoint: string }> {
    const response = await fetch(`${PROXY_BASE}/config`, {
      headers: getAuthHeaders(),
    })
    if (!response.ok) throw new RagflowApiError(`获取配置失败: ${response.status}`)
    const result = await response.json()
    return result.data as { endpoint: string }
  }

  /** 获取数据集列表 */
  static async getDatasets(page = 1, pageSize = 100): Promise<KnowledgeBase[]> {
    const result = await proxyFetch<RAGFlowDataset[] | RAGFlowPaginatedData>(
      `${PROXY_BASE}/datasets?page=${page}&page_size=${pageSize}`
    )

    // 兼容两种返回格式：数组 或 分页对象（含 docs/datasets 字段）
    const datasets = Array.isArray(result)
      ? result
      : (result as RAGFlowPaginatedData).datasets || (result as RAGFlowPaginatedData).docs || []
    if (!Array.isArray(datasets)) return []

    return datasets.map((dataset: any) => ({
      id: dataset.id,
      name: dataset.name,
      description: dataset.description,
      documentCount: dataset.document_count || 0,
      chunkCount: dataset.chunk_count || 0,
      tokenCount: dataset.token_count || 0,
    }))
  }

  /** 创建数据集 */
  static async createDataset(data: { name: string; description: string }): Promise<void> {
    await proxyFetch<unknown>(`${PROXY_BASE}/datasets`, {
      method: 'POST',
      body: JSON.stringify({
        name: data.name.trim(),
        description: data.description.trim(),
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
  }

  /** 删除数据集 */
  static async deleteDataset(ids: string[]): Promise<void> {
    await proxyFetch<unknown>(`${PROXY_BASE}/datasets`, {
      method: 'DELETE',
      body: JSON.stringify({ ids }),
    })
  }

  /** 获取指定数据集下的文档列表 */
  static async getDocuments(datasetId: string): Promise<RAGFlowDocumentInfo[]> {
    const result = await proxyFetch<RAGFlowPaginatedData>(
      `${PROXY_BASE}/datasets/${datasetId}/documents?page=1&page_size=100`
    )

    if (result.docs && Array.isArray(result.docs)) {
      return result.docs.map((doc: any) => ({
        id: doc.id,
        name: doc.name,
        size: doc.size || 0,
        type: doc.type || doc.name?.split('.').pop()?.toUpperCase() || 'Unknown',
        run: doc.run,
        chunkCount: doc.chunk_count || 0,
        progress: doc.progress || 0,
      }))
    }
    return []
  }

  /** 上传文档到数据集 */
  static async uploadDocument(datasetId: string, file: File): Promise<string[]> {
    const formData = new FormData()
    formData.append('file', file)

    const response = await fetch(
      `${PROXY_BASE}/datasets/${datasetId}/documents`,
      {
        method: 'POST',
        headers: {
          // 不手动设置 Content-Type，让浏览器自动设置 multipart boundary
          Authorization: getAuthHeaders().Authorization || '',
        },
        body: formData,
      }
    )

    if (!response.ok) {
      throw new RagflowApiError(`上传失败: ${response.status}`, response.status)
    }

    const result = await response.json()
    if (result.code !== 0) {
      throw new RagflowApiError(result.message || '上传失败')
    }

    return (result.data as RAGFlowDocument[]).map((item: RAGFlowDocument) => item.id)
  }

  /** 删除文档 */
  static async deleteDocument(datasetId: string, documentIds: string[]): Promise<void> {
    await proxyFetch<unknown>(`${PROXY_BASE}/datasets/${datasetId}/documents`, {
      method: 'DELETE',
      body: JSON.stringify({ ids: documentIds }),
    })
  }

  /** 解析文档切片 */
  static async parseDocuments(datasetId: string, documentIds: string[]): Promise<void> {
    const validIds = documentIds.filter(id => id && typeof id === 'string')
    if (validIds.length === 0) return

    await proxyFetch<unknown>(`${PROXY_BASE}/datasets/${datasetId}/chunks`, {
      method: 'POST',
      body: JSON.stringify({ document_ids: validIds }),
    })
  }

  /** 检索效果测试（F5.5） */
  static async testRetrieval(question: string, datasetIds: string[]): Promise<Array<{ content: string; similarity: number; document: string }>> {
    const response = await fetch(`${PROXY_BASE}/retrieval-test`, {
      method: 'POST',
      headers: getAuthHeaders(),
      body: JSON.stringify({ question, datasetIds }),
    })
    if (!response.ok) throw new RagflowApiError(`检索测试失败: ${response.status}`, response.status)
    const result = await response.json()
    if (result.code !== 200) throw new RagflowApiError(result.message || '检索测试失败')
    return (result.data || []) as Array<{ content: string; similarity: number; document: string }>
  }
}
