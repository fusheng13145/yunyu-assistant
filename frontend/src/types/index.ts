export interface KnowledgebaseInfo {
  docCount?: number
  docName?: string[]
}

export interface ChatMessage {
  role: 'user' | 'assistant'
  message: string
  costTime?: number
  knowledgebase?: KnowledgebaseInfo
}

export interface Assistant {
  id: string
  name: string
  description: string
  personality: string
  voice: string
  modelName?: string
  temperature?: number
  maxTokens?: number
  /** 关联知识库ID列表（后端为 JSON 字符串时前端自行解析） */
  knowledgeIds?: string[] | string
  userId: string
  createdAt: string
  updatedAt: string
}

/** 文本对话会话（会话维度持久化容器） */
export interface ChatSession {
  id: string
  userId: string
  assistantId: string
  title: string
  isPinned: number
  createdAt: string
  updatedAt: string
}

export interface WebSocketMessage {
  type: string
  data?: unknown
}

export interface ChatAnswer {
  segment: string
  streamEnd: boolean
}

export interface ToolCallResult {
  name: string
  arguments: string
  result: string
}

export interface AsrDeltaData {
  text: string
}

export interface VoiceCallState {
  isActive: boolean
  isConnecting: boolean
}

export interface TokenUsage {
  promptTokens?: number
  completionTokens?: number
}

export interface DisplayMessage {
  role: 'user' | 'assistant' | 'tool_call' | 'tool_result'
  text: string
  isStreaming?: boolean
  costTime?: number
  knowledgebase?: KnowledgebaseInfo
  toolCalls?: ToolCallResult[]
  toolName?: string
  toolResult?: string
  tokenUsage?: TokenUsage
}

export interface KnowledgeBase {
  id: string
  name: string
  description: string
  documentCount?: number
  chunkCount?: number
  tokenCount?: number
}

export interface RAGFlowConfig {
  endpoint: string
  apiKey: string
}

export interface VoiceInfo {
  id: string
  name: string
  gender: number
  description: string
}

export interface ModelInfo {
  id: string
  name: string
  description: string
}

export interface CallRecord {
  id: string
  assistantId: string
  assistantName: string
  status: number
  durationSec: number
  messageCount: number
  startedAt: string
  endedAt: string
  failReason?: string
  recording?: boolean
}

export interface CallRecordDetail extends CallRecord {
  messages: Array<{ role: number; message: string; costTime?: number; createdAt: string }>
}

export interface UsageStats {
  range: string
  callCount: number
  totalDurationSec: number
  messageCount: number
  days: Array<{ date: string; callCount: number; durationSec: number }>
}

export interface CreateAssistantData {
  name: string
  description: string
  personality?: string
  voice?: string
  modelName?: string
  temperature?: number
  maxTokens?: number
  knowledgeIds?: string[]
}

export interface UpdateAssistantData extends CreateAssistantData {
  id: string
}

export interface LoginData {
  username: string
  password: string
}

export interface RegisterData {
  username: string
  password: string
}

export interface AuthResponse {
  token: string
  refreshToken: string
  userId: string
  username: string
  role?: string
}

export interface User {
  id: string
  username: string
  nickname: string
  avatar: string
  email: string
  phone: string
  role?: string
  createdAt: string
  updatedAt: string
}

export interface ToolCallMessage {
  type: 'tool_call' | 'tool_result'
  name?: string
  arguments?: string
  result?: string
}

export interface QueryEndData {
  message?: string
  costTime?: number
  knowledgebase?: KnowledgebaseInfo
}

export interface WsChatMessage {
  type: 'assistant_message' | 'tool_call' | 'tool_result' | 'error' | 'query_end'
  data: ChatAnswer | ToolCallMessage | QueryEndData | string
}

/** 组织（P2-10 多租户） */
export interface Org {
  id: string
  name: string
  ownerUserId: string
  description?: string
  createdAt: string
  updatedAt: string
}

/** 组织成员（P2-10 角色矩阵 owner/editor/viewer） */
export interface OrgMember {
  id: string
  orgId: string
  userId: string
  username?: string
  role: 'owner' | 'editor' | 'viewer'
  joinedAt: string
}
