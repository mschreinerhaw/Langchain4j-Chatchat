/** Restricted values used by the existing coding-canonical-json-v1 contract. */
import {createHash} from 'node:crypto';
export function canonicalCodingJson(value){
  if(value===null||typeof value==='string'||typeof value==='boolean')return JSON.stringify(value);
  if(typeof value==='number'&&Number.isSafeInteger(value))return String(value);
  if(Array.isArray(value))return '['+value.map(canonicalCodingJson).join(',')+']';
  if(value&&Object.getPrototypeOf(value)===Object.prototype)return '{'+Object.keys(value).sort().map(k=>JSON.stringify(k)+':'+canonicalCodingJson(value[k])).join(',')+'}';
  throw new TypeError('Unsupported canonical coding value');
}
export const codingValueHash=value=>createHash('sha256').update(canonicalCodingJson(value)).digest('hex');
