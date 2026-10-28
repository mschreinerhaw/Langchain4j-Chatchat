import MarkdownIt from "markdown-it";

// Narrative text is Markdown, never an executable HTML document. Preserve reported
// values verbatim; headings, emphasis, lists and tables only change presentation.
const markdown = new MarkdownIt({ html: false, linkify: true, breaks: true, typographer: false });
// Report images use the authorized artifact path, never a model-supplied URL.
markdown.renderer.rules.image = (tokens,index) => markdown.utils.escapeHtml(tokens[index].content || '');
const linkOpen = markdown.renderer.rules.link_open
  || ((tokens, index, options, env, self) => self.renderToken(tokens, index, options));
markdown.renderer.rules.link_open = (tokens, index, options, env, self) => {
  tokens[index].attrSet("target", "_blank");
  tokens[index].attrSet("rel", "noopener noreferrer");
  return linkOpen(tokens, index, options, env, self);
};

export function renderReportNarrative(value = "") {
  const tokens = markdown.parse(String(value ?? ""), {});
  // A whole Markdown document can arrive in a source fence. Code examples inside
  // normal prose keep their literal content and are not reinterpreted as markup.
  if (tokens.length === 1 && tokens[0].type === "fence"
      && ["md", "markdown"].includes(tokens[0].info.trim().toLowerCase())) {
    return markdown.render(tokens[0].content);
  }
  return markdown.renderer.render(tokens, markdown.options, {});
}
