import MarkdownIt from "markdown-it";

const parser = new MarkdownIt({ html: false });

// Some models wrap a complete answer in a Markdown source fence. Only unwrap
// a single document wrapper; code examples inside ordinary prose stay literal.
export function unwrapMarkdownDocument(value = "") {
  const source = String(value ?? "");
  const tokens = parser.parse(source, {});
  if (tokens.length !== 1 || tokens[0].type !== "fence") return source;
  const language = tokens[0].info.trim().toLowerCase();
  return language === "markdown" || language === "md" ? tokens[0].content : source;
}
