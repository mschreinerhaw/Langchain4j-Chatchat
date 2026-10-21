import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { parse } from "vue/compiler-sfc";

// Presentation checks follow the component's actual source references and ignore layout whitespace.
export function readVueSource(url) {
  const file = fileURLToPath(url);
  const source = readFileSync(file, "utf8");
  const { descriptor } = parse(source, { filename: file });
  const content = (block) =>
    block?.src ? readFileSync(resolve(dirname(file), block.src), "utf8") : block?.content || "";
  return [
    content(descriptor.template).replace(/\s+/g, " ").replace(/\s+>/g, ">"),
    content(descriptor.script),
    ...descriptor.styles.map(content),
  ].join("\n");
}
