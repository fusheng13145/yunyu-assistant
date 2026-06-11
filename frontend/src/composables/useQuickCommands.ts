import { ref } from 'vue'

export interface QuickCommand {
  label: string
  text: string
}

const quickCommands = ref<QuickCommand[]>([
  { label: '总结上文', text: '请总结我们刚才讨论的内容' },
  { label: '翻译英文', text: '请将以下内容翻译成英文：' },
  { label: '解释代码', text: '请帮我解释这段代码的含义：' },
  { label: '写文档', text: '请帮我写一份文档：' },
])

export function useQuickCommands() {
  const addCommand = (command: QuickCommand) => {
    quickCommands.value.push(command)
  }

  const removeCommand = (index: number) => {
    quickCommands.value.splice(index, 1)
  }

  return {
    quickCommands,
    addCommand,
    removeCommand,
  }
}
