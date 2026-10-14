export type SqlDialect = 'spark' | 'hive' | 'postgresql' | 'mysql' | 'oracle' | 'tsql' | 'trino' | 'ansi';
export type SqlFileKind = 'SQL' | 'MAPPING' | 'REFERENCE';
export interface SqlProject { id: string; name: string; dialect: SqlDialect; sessionId: string; tenantId: string; ownerId: string; }
export interface SqlFile { id: string; projectId: string; path: string; kind: SqlFileKind; content: string; version: number; editable: boolean; sourceTaskId: string | null; }
export interface SqlFileWrite { path: string; kind: SqlFileKind; content: string; expectedVersion: number; }
export interface SqlFileVersion { version: number; content: string; sourceTaskId: string | null; }
export interface MappingDocument {
  schemaVersion: 'mapping.v1'; sourceTables: string[]; targetTable: string;
  fields: Array<{ source?: string; target: string; expression?: string; type?: string; nullable?: boolean; description?: string }>;
  joins?: string[]; filters?: string[]; writeMode?: string; notes?: string;
}
export interface SqlGenerationInput { prompt: string; dialect: SqlDialect; mappingIds?: string[]; mappingDocumentIds?: string[]; referenceIds?: string[]; agentId?: string | null; modelName?: string | null; }
export interface StudioCodePlan { planId:string; status:'READY'; interpretationPlan:{version:string; intent:{type:string; goal:string}; context:{key_facts:string[]; assumptions:string[]; missing_info:string[]; constraints:string[]}; plan:{steps:Array<{id:number; action_type:string; input:{description:string}; depends_on:number[]}>}}; }
export interface ExistingAgentTaskRequest { tenantId: string; userId: string; sessionId: string; idempotencyKey: string; query: string; mode: 'agent_chat' | 'llm_chat'; systemPrompt?: string; agentId?: string | null; modelName?: string | null; toolInput?:{sqlStudioAction?:string; studioCodePlan?:StudioCodePlan}; }
export interface PreparedSqlGeneration { generationId: string; taskRequest: ExistingAgentTaskRequest; }
export interface SqlGeneration { id: string; taskId: string | null; applied: boolean; prepared: PreparedSqlGeneration; }
export interface SqlOutputBundle { schemaVersion: 'sql-studio.output.v1'; files: Array<{ path: string; content: string }>; notes?: string; }
/** Relevant projection of the unchanged Runtime response, not a new execution contract. */
export interface ExistingAgentTask { taskId: string; sessionId: string; status: string; answerSummary: string | null; errorMessage?: string | null; canonicalState: string; lastEventSequence: number; }
export interface ExistingAgentEvent { taskId: string; type: string; sequence: number; status: string; payload: string; createTime: number; eventId?: string; toolName?: string; }
export interface SqlGenerationView { generation: SqlGeneration; task: ExistingAgentTask | null; events: ExistingAgentEvent[]; files: SqlOutputBundle['files']; rawAnswer: string; outputError: string; validation: 'NOT_RUN'; }
