import type { KnowledgeBase, RAGFlowConfig } from '../types'

interface RAGFlowDatasetResponse {
  code: number
  message?: string
  data: RAGFlowDataset[] | RAGFlowPaginatedData
}

interface RAGFlowPaginatedData {
  docs?: RAGFlowDocument[]
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

interface RAGFlowResponse {
  code: number
  message?: string
  data: RAGFlowDocument[] | unknown
}

export class RagflowApiError extends Error {
  constructor(message: string, public status?: number) {
    super(message)
    this.name = 'RagflowApiError'
  }
}

function getRagflowHeaders(apiKey: string): HeadersInit {
  return {
    'Content-Type': 'application/json',
    'Authorization': apiKey,
  }
}

async function ragflowFetch<T>(url: string, options: RequestInit, apiKey: string): Promise<T> {
  const response = await fetch(url, {
    ...options,
    headers: {
      ...getRagflowHeaders(apiKey),
      ...(options.headers as Record<string, string> || {}),
    },
  })

  if (!response.ok) {
    throw new RagflowApiError(`请求失败: ${response.status}`, response.status)
  }

  const result = await response.json()
  if (result.code !== 0) {
    throw new RagflowApiError(result.message || '请求失败')
  }
  return result.data as T
}

export function createRagflowApi(config: () => RAGFlowConfig) {
  return {
    async getDatasets(): Promise<KnowledgeBase[]> {
      const cfg = config()
      if (!cfg.endpoint || !cfg.apiKey) return []

      const result = await ragflowFetch<RAGFlowDataset[] | RAGFlowPaginatedData>(
        `${cfg.endpoint}/api/v1/datasets?page=1&page_size=10`,
        { method: 'GET' },
        cfg.apiKey
      )

      const datasets = Array.isArray(result) ? result : (result as RAGFlowPaginatedData).docs || []
      if (!Array.isArray(datasets)) return []

      return datasets.map((dataset: any) => ({
        id: dataset.id,
        name: dataset.name,
        description: dataset.description,
        documentCount: dataset.document_count || 0,
        chunkCount: dataset.chunk_count || 0,
        tokenCount: dataset.token_count || 0,
      }))
    },

    async createDataset(data: { name: string; description: string }): Promise<void> {
      const cfg = config()
      await ragflowFetch<unknown>(
        `${cfg.endpoint}/api/v1/datasets`,
        {
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
        },
        cfg.apiKey
      )
    },

    async deleteDataset(ids: string[]): Promise<void> {
      const cfg = config()
      await ragflowFetch<unknown>(
        `${cfg.endpoint}/api/v1/datasets`,
        {
          method: 'DELETE',
          body: JSON.stringify({ ids }),
        },
        cfg.apiKey
      )
    },

    async getDocuments(datasetId: string): Promise<RAGFlowDocument[]> {
      const cfg = config()
      const result = await ragflowFetch<RAGFlowPaginatedData>(
        `${cfg.endpoint}/api/v1/datasets/${datasetId}/documents?page=1&page_size=100`,
        { method: 'GET' },
        cfg.apiKey
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
    },

    async uploadDocument(datasetId: string, file: File): Promise<string[]> {
      const cfg = config()
      const formData = new FormData()
      formData.append('file', file)

      const response = await fetch(
        `${cfg.endpoint}/api/v1/datasets/${datasetId}/documents`,
        {
          method: 'POST',
          headers: { 'Authorization': cfg.apiKey },
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
    },

    async deleteDocument(datasetId: string, documentIds: string[]): Promise<void> {
      const cfg = config()
      await ragflowFetch<unknown>(
        `${cfg.endpoint}/api/v1/datasets/${datasetId}/documents`,
        {
          method: 'DELETE',
          body: JSON.stringify({ ids: documentIds }),
        },
        cfg.apiKey
      )
    },

    async parseDocuments(datasetId: string, documentIds: string[]): Promise<void> {
      const cfg = config()
      const validIds = documentIds.filter(id => id && typeof id === 'string')
      if (validIds.length === 0) return

      await ragflowFetch<unknown>(
        `${cfg.endpoint}/api/v1/datasets/${datasetId}/chunks`,
        {
          method: 'POST',
          body: JSON.stringify({ document_ids: validIds }),
        },
        cfg.apiKey
      )
    },
  }
}
