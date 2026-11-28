import {closeSync, constants, fstatSync, fsyncSync, ftruncateSync, mkdirSync, openSync, readSync, writeSync} from 'node:fs';
import {dirname} from 'node:path';

const chunkSize = 64 * 1024;

function withFile(file, operation) {
 let fd;
 try { fd = openSync(file, 'r'); }
 catch (error) { if (error.code === 'ENOENT') return operation(null, null); throw error; }
 try { return operation(fd, fstatSync(fd)); }
 finally { closeSync(fd); }
}

// Find the closest newline strictly before the given position.
function newlineBefore(fd, position) {
 const buffer = Buffer.allocUnsafe(Math.min(chunkSize, position));
 while (position > 0) {
  const start = Math.max(0, position - buffer.length);
  const size = readSync(fd, buffer, 0, position - start, start);
  const index = buffer.subarray(0, size).lastIndexOf(10);
  if (index !== -1) return start + index;
  position = start;
 }
 return -1;
}

function* rows(fd, start, end) {
 if (start >= end) return;
 const buffer = Buffer.allocUnsafe(Math.min(chunkSize, end - start));
 let pending = [], rowStart = start;
 for (let position = start; position < end;) {
  const size = readSync(fd, buffer, 0, Math.min(buffer.length, end - position), position);
  if (size === 0) break;
  let offset = 0;
  for (let nl = buffer.subarray(0, size).indexOf(10); nl !== -1;
   nl = buffer.subarray(0, size).indexOf(10, offset)) {
   const fragment = buffer.subarray(offset, nl);
   const line = pending.length ? Buffer.concat([...pending, fragment]) : fragment;
   if (line.length && !(line.length === 1 && line[0] === 13)) {
    const event = JSON.parse(line.toString('utf8'));
    yield {event, start: rowStart, end: position + nl + 1};
   }
   pending = [];
   rowStart = position + nl + 1;
   offset = nl + 1;
  }
  if (offset < size) pending.push(Buffer.from(buffer.subarray(offset, size)));
  position += size;
 }
 // A live writer or a crash can leave an incomplete trailing record.
}

function seek(fd, end, cursor) {
 let low = 0, high = end;
 while (low < high) {
  const midpoint = low + Math.floor((high - low) / 2);
  const start = Math.max(low, newlineBefore(fd, midpoint) + 1);
  const row = rows(fd, start, high).next().value;
  if (!row) break;
  if (BigInt(row.event.eventId) <= cursor) low = row.end;
  else high = row.start;
 }
 return low;
}

function tail(fd, size) {
 const end = newlineBefore(fd, size) + 1;
 let position = end;
 while (position > 0) {
  const start = newlineBefore(fd, position - 1) + 1;
  const row = rows(fd, start, position).next().value;
  if (row) return {end, last: BigInt(row.event.eventId)};
  position = start;
 }
 return {end, last: 0n};
}

export class Journal {
 #next;
 #writer;
 constructor(file) { this.file = file; }

 get lastEventId() {
  return withFile(this.file, (fd, stat) => String(stat ? this.#tail(fd, stat).last : 0n));
 }

 #tail(fd, stat) {
  if (this.#writer?.ino === stat.ino && this.#writer.dev === stat.dev && this.#writer.size === stat.size) {
   return this.#writer;
  }
  return tail(fd, stat.size);
 }

 append(fields) { return this.appendBatch([fields])[0]; }

 // The single journal writer confirms a group only after its shared durability fence.
 appendBatch(fields) {
  if (!fields.length) return [];
  const flags = constants.O_RDWR | constants.O_CREAT;
  let fd;
  try { fd = openSync(this.file, flags, 0o600); }
  catch (error) {
   if (error.code !== 'ENOENT') throw error;
   mkdirSync(dirname(this.file), {recursive: true});
   fd = openSync(this.file, flags, 0o600);
  }
  try {
   const stat = fstatSync(fd);
   const {end, last} = this.#tail(fd, stat);
   const timestamp = new Date().toISOString();
   const events = fields.map((field, index) => ({...field, eventId: String(last + BigInt(index + 1)), timestamp}));
   const data = Buffer.from(events.map(event => JSON.stringify(event) + '\n').join(''));
   this.#writer = undefined;
   if (end < stat.size) ftruncateSync(fd, end);
   for (let offset = 0; offset < data.length;) {
    const written = writeSync(fd, data, offset, data.length - offset, end + offset);
    if (!written) throw Error('Journal append made no progress');
    offset += written;
   }
   fsyncSync(fd);
   const size = end + data.length;
   this.#writer = {ino: stat.ino, dev: stat.dev, size, end: size, last: last + BigInt(events.length)};
   return events;
  } finally { closeSync(fd); }
 }

 page(id, limit) {
  if (!/^\d+$/.test(String(id))) throw Error('Invalid event cursor');
  if (!Number.isSafeInteger(limit) || limit < 1) throw Error('Invalid event page size');
  const cursor = BigInt(id);
  return withFile(this.file, (fd, stat) => {
   if (!stat) return {events: [], hasMore: false};
   const sameFile = this.#next?.ino === stat.ino && this.#next.dev === stat.dev && stat.size >= this.#next.size;
   const start = cursor === 0n ? 0 : sameFile && this.#next.cursor === cursor
    ? this.#next.offset : seek(fd, this.#tail(fd, stat).end, cursor);
   const events = [];
   let offset = start, last = cursor;
   for (const row of rows(fd, start, stat.size)) {
    const number = BigInt(row.event.eventId);
    if (number <= cursor) continue;
    if (events.length >= limit) return {events, hasMore: true};
    events.push(row.event);
    last = number;
    offset = row.end;
    if (events.length === limit) this.#next = {ino: stat.ino, dev: stat.dev, size: stat.size, offset, cursor: last};
   }
   this.#next = {ino: stat.ino, dev: stat.dev, size: stat.size, offset, cursor: last};
   return {events, hasMore: false};
  });
 }

 *readAfter(id = '0') {
  let after = id;
  const last = BigInt(this.lastEventId);
  for (;;) {
   const {events, hasMore} = this.page(after, 128);
   for (const event of events) {
    if (BigInt(event.eventId) > last) return;
    yield event;
   }
   if (!hasMore) return;
   after = events.at(-1).eventId;
  }
 }

 after(id) { return [...this.readAfter(id)]; }
 get events() { return this.after('0'); }
}
