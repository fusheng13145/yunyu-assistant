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
  userId: string
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

export interface DisplayMessage {
  role: 'user' | 'assistant' | 'tool_call' | 'tool_result'
  text: string
  isStreaming?: boolean
  costTime?: number
  knowledgebase?: KnowledgebaseInfo
  toolCalls?: ToolCallResult[]
  toolName?: string
  toolResult?: string
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

export interface CreateAssistantData {
  name: string
  description: string
  personality?: string
  voice?: string
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
  userId: string
  username: string
}

export interface User {
  id: string
  username: string
  nickname: string
  avatar: string
  email: string
  phone: string
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
