// The original restart test restarts an HTTP server within one Node process.
// Close test-client sockets between calls so undici cannot reuse its old socket.
// This is a test runner adapter only; the application and original tests stay intact.
const originalFetch=globalThis.fetch;
globalThis.fetch=(input,options={})=>{
 const url=new URL(typeof input==='string'?input:input.url??input.href);
 if(url.protocol==='http:' && ['127.0.0.1','localhost'].includes(url.hostname)){
  const headers=new Headers(options.headers);headers.set('Connection','close');
  return originalFetch(input,{...options,headers});
 }
 throw new Error('This test adapter only permits loopback HTTP requests');
};
