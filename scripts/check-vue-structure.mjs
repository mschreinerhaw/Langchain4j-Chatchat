import { existsSync, readFileSync, readdirSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, extname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repository = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const projects =
  process.argv.length > 2
    ? process.argv.slice(2).map((directory) => resolve(directory))
    : ["chatchat-api/web-app", "chatchat-mcp-server/web-app-mcp"].map((directory) =>
        resolve(repository, directory)
      );

function vueFiles(directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = resolve(directory, entry.name);
    return entry.isDirectory() ? vueFiles(path) : extname(path) === ".vue" ? [path] : [];
  });
}

const errors = [];
let count = 0;
for (const project of projects) {
  const require = createRequire(resolve(project, "package.json"));
  const { parse } = require("vue/compiler-sfc");
  for (const file of vueFiles(resolve(project, "src"))) {
    count++;
    const { descriptor, errors: parseErrors } = parse(readFileSync(file, "utf8"), {
      filename: file,
    });
    for (const error of parseErrors) errors.push(`${file}: ${error.message || error}`);
    if (!descriptor.template) errors.push(`${file}: 缺少 template`);
    function checkTemplate(node) {
      if (node?.type === 1 && node.props.some((prop) => prop.type === 6 && prop.name === "style")) {
        errors.push(`${file}: 静态样式应使用 class 并定义在独立 CSS 文件中`);
      }
      for (const child of node?.children || []) checkTemplate(child);
    }
    checkTemplate(descriptor.template?.ast);
    if (descriptor.scriptSetup) errors.push(`${file}: 请将 script setup 转为独立的组件脚本`);
    if (descriptor.customBlocks.length) errors.push(`${file}: 仅允许模板和外部脚本、样式引用`);
    for (const block of [descriptor.script, ...descriptor.styles].filter(Boolean)) {
      if (!block.src || block.content.trim()) {
        errors.push(`${file}: ${block.type} 必须通过 src 引用独立文件`);
        continue;
      }
      const source = block.src.split("?")[0];
      try {
        const target = source.startsWith(".")
          ? resolve(dirname(file), source)
          : require.resolve(source);
        if (!existsSync(target)) errors.push(`${file}: 引用文件不存在 ${block.src}`);
      } catch {
        errors.push(`${file}: 无法解析引用 ${block.src}`);
      }
    }
  }
}

if (errors.length) {
  console.error(errors.join("\n"));
  process.exitCode = 1;
} else {
  console.log(`Vue 结构检查通过：${count} 个组件，脚本和样式均为独立文件。`);
}
