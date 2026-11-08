// Native executors can return structured failure feedback without throwing.
export function toolFailed(event){
 const outputs=Array.isArray(event.output)?event.output:[event.output];
 return Boolean(event.error)||outputs.some(value=>value&&typeof value==='object'&&(value.success===false||value.isError===true||Boolean(value.error)));
}
