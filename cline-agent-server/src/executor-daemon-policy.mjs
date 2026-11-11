import {lstatSync, realpathSync, readFileSync, statSync} from 'node:fs';
import {posix} from 'node:path';

const {isAbsolute, join, resolve, sep} = posix; // Docker Executor runs on Linux.

/** Pin the control daemon independently of any task supplied identity. */
export function verifyExecutorDaemon(cfg, daemon, inspectPath = path => ({
  stat: lstatSync(path), realpath: realpathSync(path),
}), inspectProcess = pid => ({
  networkNamespace:statSync('/proc/'+pid+'/ns/net').ino,
  startTicks:readFileSync('/proc/'+pid+'/stat','utf8').slice(readFileSync('/proc/'+pid+'/stat','utf8').lastIndexOf(')')+2).split(' ')[19],
  command:readFileSync('/proc/'+pid+'/cmdline','utf8').split('\0').filter(Boolean),
})) {
  if (!cfg.expectedDaemonId || daemon.ID !== cfg.expectedDaemonId)
    throw Error('Wrong Docker daemon: refusing Executor startup');
  const rootless = daemon.SecurityOptions?.includes('name=rootless') === true;
  const resources = daemon.CgroupDriver !== 'none' && !!daemon.MemoryLimit
    && !!daemon.CpuCfsQuota && !!daemon.PidsLimit;
  const mode = cfg.daemonMode ?? 'dedicated-rootless';
  if (mode === 'dedicated-rootless') {
    if (!rootless) throw Error('Rootful daemon is not permitted by the rootless Executor policy');
    if (!resources && !cfg.allowUnenforcedPrototype)
      throw Error('Per-Worker cgroup resource enforcement unavailable');
  } else if (mode === 'dedicated-rootful') {
    // Explicit opt-in for hosts with cgroup v1, where rootless cannot enforce
    // memory/CPU/PID limits. A shared/default Docker socket is never admitted.
    const anchor = cfg.dedicatedDaemonRoot;
    if (rootless || cfg.allowUnenforcedPrototype || !resources)
      throw Error('Dedicated rootful mode requires enforced cgroup limits');
    if (typeof anchor !== 'string' || !isAbsolute(anchor) || resolve(anchor) !== anchor
        || anchor.split(sep).filter(Boolean).length < 3
        || ['/var/run', '/run', '/var/lib/docker', '/opt/docker'].includes(anchor))
      throw Error('Explicit private daemon anchor required');
    if (cfg.dockerSocket !== join(anchor, 'run', 'docker.sock')
        || daemon.DockerRootDir !== join(anchor, 'data'))
      throw Error('Daemon socket/data root is outside the pinned private anchor');
    // Pin real processes, not a caller supplied namespace claim. Docker may
    // remove docker0 even with bridge=none; isolate daemon AND containerd first.
    const host=inspectProcess(1);
    for(const [role,program,configName] of [['daemon','dockerd','daemon.json'],['containerd','containerd','containerd.toml']]) {
      const binding=cfg.dedicatedProcesses?.[role];
      if(!binding||!Number.isSafeInteger(binding.pid)||binding.pid<=1||typeof binding.startTicks!=='string')
        throw Error('Pinned isolated daemon/containerd processes required');
      const observed=inspectProcess(binding.pid),flag=program==='dockerd'?'--config-file':'--config';
      if(observed.startTicks!==binding.startTicks||observed.networkNamespace===host.networkNamespace
          ||!observed.command[0]?.endsWith(program)
          ||observed.command[observed.command.indexOf(flag)+1]!==join(anchor,configName))
        throw Error('Daemon process or network namespace isolation differs from pinned policy');
    }
    for (const path of [anchor, join(anchor, 'run'), join(anchor, 'data'), cfg.dockerSocket]) {
      const {stat, realpath} = inspectPath(path);
      if (realpath !== path || stat.isSymbolicLink() || stat.uid !== 0
          || (stat.mode & 0o077) !== 0)
        throw Error('Daemon anchors must be canonical, root owned and private');
      if (path === cfg.dockerSocket ? !stat.isSocket() : !stat.isDirectory())
        throw Error('Unexpected daemon anchor type');
    }
  } else throw Error('Unknown Executor daemon mode');
  return {mode, rootless, resourceSupport: resources};
}
