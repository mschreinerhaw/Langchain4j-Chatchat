/** Split model reasoning tags from visible answer text without rendering tag contents as HTML. */
export function splitThinkBlocks(source) {
  const text = String(source || "");
  const blocks = [];
  const tags = /<\s*(\/?)\s*think\s*>/gi;
  let inThink = false;
  let start = 0;
  let match;

  while ((match = tags.exec(text))) {
    const closing = !!match[1];
    if (closing && inThink) {
      blocks.push({ type: "thinking", content: text.slice(start, match.index).trim() });
      inThink = false;
      start = tags.lastIndex;
    } else if (!closing && !inThink) {
      if (match.index > start) blocks.push({ type: "text", content: text.slice(start, match.index) });
      inThink = true;
      start = tags.lastIndex;
    }
    // Stray closing tags are omitted; nested opening tags remain inside the current thought.
    else if (closing && !inThink) {
      if (match.index > start) blocks.push({ type: "text", content: text.slice(start, match.index) });
      start = tags.lastIndex;
    }
  }
  if (start < text.length) {
    const tail = text.slice(start);
    if (inThink) {
      blocks.push({ type: "thinking", content: tail.trim() });
    } else {
      const pendingOpen = /<\s*t(?:h(?:i(?:n(?:k)?)?)?)?\s*$/i.exec(tail);
      if (pendingOpen) {
        if (pendingOpen.index) blocks.push({ type: "text", content: tail.slice(0, pendingOpen.index) });
        blocks.push({ type: "thinking", content: "" });
      } else {
        blocks.push({ type: "text", content: tail });
      }
    }
  } else if (inThink) {
    blocks.push({ type: "thinking", content: "" });
  }
  return blocks;
}
