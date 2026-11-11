import test from 'node:test';
import assert from 'node:assert/strict';
import {verifyExecutorDaemon} from '../src/executor-daemon-policy.mjs';

const anchor='/srv/coding-test/daemon';
const cfg={expectedDaemonId:'owned-daemon',daemonMode:'dedicated-rootful',dedicatedDaemonRoot:anchor,dockerSocket:anchor+'/run/docker.sock',dedicatedProcesses:{daemon:{pid:20,startTicks:'123'},containerd:{pid:21,startTicks:'124'}}};
const daemon={ID:'owned-daemon',SecurityOptions:[],DockerRootDir:anchor+'/data',CgroupDriver:'cgroupfs',MemoryLimit:true,CpuCfsQuota:true,PidsLimit:true};
const inspect=path=>({realpath:path,stat:{uid:0,mode:path.endsWith('.sock')?0o140600:0o40700,isSymbolicLink:()=>false,isSocket:()=>path.endsWith('.sock'),isDirectory:()=>!path.endsWith('.sock')}});
const process=pid=>pid===1?{networkNamespace:1}:{networkNamespace:pid,startTicks:pid===20?'123':'124',command:pid===20?['dockerd','--config-file',anchor+'/daemon.json']:['containerd','--config',anchor+'/containerd.toml']};
const verify=(config,info,paths=inspect,processes=process)=>verifyExecutorDaemon(config,info,paths,processes);

test('rootful cgroup-v1 mode requires an explicit private, pinned daemon',()=>{
  assert.deepEqual(verify(cfg,daemon),{mode:'dedicated-rootful',rootless:false,resourceSupport:true});
  assert.throws(()=>verifyExecutorDaemon({...cfg,daemonMode:undefined},daemon,inspect),/Rootful/);
  assert.throws(()=>verifyExecutorDaemon(cfg,{...daemon,ID:'shared-daemon'},inspect),/Wrong/);
  assert.throws(()=>verifyExecutorDaemon({...cfg,dockerSocket:'/var/run/docker.sock'},daemon,inspect),/outside/);
  assert.throws(()=>verifyExecutorDaemon(cfg,{...daemon,DockerRootDir:'/opt/docker'},inspect),/outside/);
});
test('missing cgroup support or prototype bypass cannot weaken rootful enforcement',()=>{
  for(const key of ['MemoryLimit','CpuCfsQuota','PidsLimit'])
    assert.throws(()=>verifyExecutorDaemon(cfg,{...daemon,[key]:false},inspect),/enforced/);
  assert.throws(()=>verifyExecutorDaemon({...cfg,allowUnenforcedPrototype:true},daemon,inspect),/enforced/);
});
test('private daemon rejects symlink, writable, non-root and wrong-type anchors',()=>{
  for(const change of [{realpath:'/other'}, {stat:{uid:10001}}, {stat:{mode:0o40770}}, {stat:{isSymbolicLink:()=>true}}, {stat:{isDirectory:()=>false}}]) {
    assert.throws(()=>verify(cfg,daemon,path=>{
      const observed=inspect(path);
      return {...observed,...change,stat:{...observed.stat,...change.stat}};
    }),/anchors|type/);
  }
});
test('rootful daemon and containerd must both run outside the host network namespace',()=>{
  assert.throws(()=>verify({...cfg,dedicatedProcesses:undefined},daemon),/Pinned/);
  for(const pid of [20,21]){
    assert.throws(()=>verify(cfg,daemon,inspect,id=>({...process(id),...(id===pid?{networkNamespace:1}:{})})),/isolation/);
    assert.throws(()=>verify(cfg,daemon,inspect,id=>({...process(id),...(id===pid?{startTicks:'reused-pid'}:{})})),/isolation/);
  }
});
test('default rootless policy preserves strict resource and daemon identity checks',()=>{
  const rootless={...daemon,SecurityOptions:['name=rootless']};
  assert.equal(verifyExecutorDaemon({expectedDaemonId:daemon.ID},rootless).rootless,true);
  assert.throws(()=>verifyExecutorDaemon({expectedDaemonId:daemon.ID},{...rootless,PidsLimit:false}),/enforcement/);
});
