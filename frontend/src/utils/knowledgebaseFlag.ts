import type { KnowledgebaseInfo } from '../types'

export type KnowledgebaseFlag = 'failed' | 'cited' | 'none'

/**
 * 把 query_end 帧的 knowledgebase 翻译成角标三态。
 * failed 必须优先：后端 v2.39 起，检索故障与"知识库没答案"是两件事，合并显示会把故障读成没答案。
 */
export function knowledgebaseFlag(kb?: KnowledgebaseInfo | null): KnowledgebaseFlag {
  if (!kb) return 'none'
  if (kb.failed) return 'failed'
  if ((kb.docCount ?? 0) > 0 || (kb.docName?.length ?? 0) > 0) return 'cited'
  return 'none'
}
