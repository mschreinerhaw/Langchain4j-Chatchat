import test from 'node:test';
import assert from 'node:assert/strict';
import {nativeModelSettings} from '../src/native-model-settings.mjs';
test('legacy Native config preserves installed model defaults',()=>{
 assert.deepEqual(nativeModelSettings({model:'fixed'}),{global:{},providers:null});
});
test('isolated parameters use the installed Core provider and both execution modes',()=>{
 const config={model:'fixed',apiKey:'not-written',modelOptions:{maxOutputTokens:8192,thinking:true,reasoningEffort:'low'}};
 const value=nativeModelSettings(config);
 assert.equal(value.global.actModeOpenAiModelInfo.maxTokens,8192);
 assert.equal(value.global.planModeOpenAiModelInfo.maxTokens,8192);
 assert.deepEqual(value.providers.providers['openai-compatible'].settings.reasoning,{enabled:true,effort:'low'});
 assert.ok(Number.isFinite(Date.parse(value.providers.providers['openai-compatible'].updatedAt)));
 assert.equal(value.providers.providers['openai-compatible'].tokenSource,'manual');
 assert.ok(!JSON.stringify(value).includes('not-written'));
 assert.equal(config.modelOptions.reasoningEffort,'low');
});
test('invalid or conflicting parameters fail before a Core process or inference starts',()=>{
 for(const modelOptions of [{maxOutputTokens:-1},{maxOutputTokens:Infinity},{thinking:'true'},{thinking:false,reasoningEffort:'high'},{maxIterations:32},[]])assert.throws(()=>nativeModelSettings({modelOptions}));
});
