// Test-only preload. The outer runner also places this process in a private
// network namespace and hides the real configuration and data directories.
import fs from 'node:fs';
import net from 'node:net';
import tls from 'node:tls';
import http from 'node:http';
import https from 'node:https';
import dgram from 'node:dgram';
import childProcess from 'node:child_process';
import {syncBuiltinESMExports} from 'node:module';
import {resolve,join} from 'node:path';

const scratch=resolve(process.env.BM_TEST_SCRATCH??'');
if(!process.env.BM_TEST_SCRATCH || (fs.statSync(scratch).mode&0o077)!==0)
  throw new Error('A new private synthetic scratch directory is required');
const telemetry=join(scratch,'network-guard.jsonl');
const ports=new Set();
const forbiddenPorts=new Set([22,80,443,8443,8788,18088,18089,18104,18105,18788,19088,19089]);
const originalAppend=fs.appendFileSync;
const record=kind=>originalAppend(telemetry,JSON.stringify({kind,pid:process.pid})+'\n',{mode:0o600});
function blocked(kind) {
  record(kind);
  const error=new Error('Synthetic runtime isolation rejected an operation');
  error.code='BM_SYNTHETIC_ISOLATION_BLOCKED';
  throw error;
}
function localHost(value) {
  const host=String(value??'127.0.0.1').replace(/^\[|\]$/g,'').toLowerCase();
  if(host==='localhost')return '127.0.0.1';
  if(host==='::1')return host;
  if(net.isIP(host)===4 && host.startsWith('127.'))return host;
  return blocked('non-loopback-target');
}
function optionsForConnection(args) {
  // net.createConnection normalizes its arguments, then calls Socket.connect
  // with that array as a single argument. Preserve the normalized port/options.
  if(Array.isArray(args[0]))args=args[0];
  let options;
  if(args[0] && typeof args[0]==='object')options={...args[0]};
  else if(typeof args[0]==='number' || /^\d+$/.test(String(args[0]??'')))
    options={port:Number(args[0]),host:typeof args[1]==='string'?args[1]:'127.0.0.1'};
  else return blocked('non-tcp-connection');
  if(options.path || options.fd!==undefined)return blocked('non-tcp-connection');
  const socket=options.socket;
  const host=localHost(options.hostname??options.host??socket?.remoteAddress);
  const port=Number(options.port??socket?.remotePort);
  if(!Number.isInteger(port) || !ports.has(port))return blocked('non-owned-loopback-port');
  delete options.hostname;
  options.host=host;
  options.port=port;
  options.family=host.includes(':')?6:4;
  // Hostnames and custom resolvers cannot redirect an allowed target elsewhere.
  delete options.lookup;
  return [options,...(typeof args.at(-1)==='function'?[args.at(-1)]:[])];
}
const originalConnect=net.Socket.prototype.connect;
net.Socket.prototype.connect=function(...args) {
  return originalConnect.apply(this,optionsForConnection(args));
};
for(const name of ['connect','createConnection']) {
  const original=net[name];
  net[name]=function(...args){return original.apply(this,optionsForConnection(args));};
}
const originalTLSConnect=tls.connect;
tls.connect=function(...args){return originalTLSConnect.apply(this,optionsForConnection(args));};

const originalListen=net.Server.prototype.listen;
net.Server.prototype.listen=function(...args) {
  let options;
  if(args[0] && typeof args[0]==='object')options={...args[0]};
  else if(typeof args[0]==='number' || /^\d+$/.test(String(args[0]??'')))
    options={port:Number(args[0]),host:typeof args[1]==='string'?args[1]:'127.0.0.1'};
  else return blocked('non-tcp-listener');
  if(options.path || options.fd!==undefined)return blocked('non-tcp-listener');
  options.host=localHost(options.host);
  if(!Number.isInteger(Number(options.port)) || forbiddenPorts.has(Number(options.port)))
    return blocked('fixed-service-port-listener');
  let owned;
  this.once('listening',()=>{owned=this.address()?.port;if(owned)ports.add(owned);});
  this.once('close',()=>{if(owned)ports.delete(owned);});
  return originalListen.call(this,options,...(typeof args.at(-1)==='function'?[args.at(-1)]:[]));
};

function checkHttpArgs(args,scheme) {
  let host,port;
  if(typeof args[0]==='string' || args[0] instanceof URL) {
    const url=new URL(args[0]);
    if(!['http:','https:'].includes(url.protocol))return blocked('unsupported-http-protocol');
    host=url.hostname;port=Number(url.port||(url.protocol==='https:'?443:80));
    if(args[1] && typeof args[1]==='object') {
      host=args[1].hostname??args[1].host??host;port=Number(args[1].port??port);
    }
  } else {
    const options=args[0]??{};
    if(options.socketPath)return blocked('non-tcp-http');
    host=options.hostname??options.host;port=Number(options.port??(scheme==='https:'?443:80));
  }
  localHost(host);
  if(!ports.has(port))return blocked('non-owned-http-port');
}
for(const [module,scheme] of [[http,'http:'],[https,'https:']]) {
  for(const name of ['request','get']) {
    const original=module[name];
    module[name]=function(...args){checkHttpArgs(args,scheme);return original.apply(this,args);};
  }
}
const originalFetch=globalThis.fetch;
globalThis.fetch=function(input,options) {
  try {
    const url=new URL(typeof input==='string'?input:input?.url??input?.href);
    if(!['http:','https:'].includes(url.protocol))return blocked('unsupported-fetch-protocol');
    localHost(url.hostname);
    if(!ports.has(Number(url.port||(url.protocol==='https:'?443:80))))
      return blocked('non-owned-fetch-port');
    return originalFetch(input,options);
  } catch(error) {return Promise.reject(error);}
};
// No shell, provider CLI, extra Node process or UDP resolver can escape the
// preloaded guards. --test-isolation=none avoids Node spawning test children.
for(const name of ['spawn','spawnSync','exec','execSync','execFile','execFileSync','fork'])
  childProcess[name]=()=>blocked('subprocess-disabled');
dgram.createSocket=()=>blocked('udp-disabled');
syncBuiltinESMExports();
record('guard-loaded');
