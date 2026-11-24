export const profileNames = {
  generic: 'Generic OpenAI Compatible',
  cline: 'Native SDK',
  langchain4j: 'LangChain4j',
  python: 'OpenAI Python SDK',
  node: 'OpenAI Node.js SDK',
} as const;
export type Profile = keyof typeof profileNames;
