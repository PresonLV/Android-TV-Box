package app.jianxia.core.spider

import app.jianxia.core.parser.resolveAgainst

data class JsImport(val binding: String?, val url: String)

/**
 * 把远程 ES 模块收成一份普通脚本。依赖在运行时下载，不放进安装包。
 * 能接住常见的 `import 名字 from`、`import {名字}` 和只有副作用的 `import`。
 */
object JsModules {
    private val importPattern = Regex(
        """import\s*(?:([^\s"'{};]+)\s+from\s*)?(?:\{([^}]+)\}\s*from\s*)?["']([^"']+)["']\s*;?""",
    )
    private val exportFunction = Regex("""export\s+function\s+(\w+)""")
    private val exportDefault = Regex("""export\s+default\s*""")
    private val exportList = Regex("""export\s*\{([^}]+)\}\s*;?""")

    const val PRELUDE = """
var console = console || { log: function() {}, error: function() {}, warn: function() {} };
if (typeof globalThis === "undefined") { var globalThis = this; }
if (!String.prototype.replaceAll) {
  String.prototype.replaceAll = function(search, replacement) { return String(this).split(search).join(replacement); };
}
function req(url, obj) {
  var raw = host.req(String(url), JSON.stringify(obj || {}));
  return JSON.parse(raw);
}
function pdfh(html, rule) { return host.pdfh(html == null ? "" : String(html), String(rule)); }
function pdfa(html, rule) { return JSON.parse(host.pdfa(html == null ? "" : String(html), String(rule))); }
function pd(html, rule, url) { return host.pd(html == null ? "" : String(html), String(rule), url == null ? "" : String(url)); }
function joinUrl(a, b) { return host.joinUrl(a == null ? "" : String(a), b == null ? "" : String(b)); }
function getProxy(flag) { return host.proxyUrl(); }
var local = {
  set: function(scope, key, value) { host.localSet(String(scope), String(key), value == null ? "" : String(value)); },
  get: function(scope, key) { return host.localGet(String(scope), String(key)); },
  delete: function(scope, key) { host.localDelete(String(scope), String(key)); }
};
console.log = function() { host.log(Array.prototype.join.call(arguments, " ")); };
"""

    const val CALLS = """
function __jx_init(ext) {
  if (typeof init === "function") return init(ext);
  if (typeof __spiderDefault === "object" && __spiderDefault && __spiderDefault.init) return __spiderDefault.init(ext);
}
function __jx_home(filter) {
  if (typeof home === "function") return home(!!filter);
  if (typeof __spiderDefault === "object" && __spiderDefault && __spiderDefault.home) return __spiderDefault.home(!!filter);
  return "{}";
}
function __jx_home_vod() {
  if (typeof homeVod === "function") return homeVod();
  if (typeof __spiderDefault === "object" && __spiderDefault && __spiderDefault.homeVod) return __spiderDefault.homeVod();
  return "{\"list\":[]}";
}
function __jx_category(tid, pg, extendJson) {
  var extend = {};
  try { extend = JSON.parse(extendJson || "{}"); } catch (e) {}
  if (typeof category === "function") return category(String(tid), pg, false, extend);
  if (typeof __spiderDefault === "object" && __spiderDefault && __spiderDefault.category) return __spiderDefault.category(String(tid), pg, false, extend);
  return "{}";
}
function __jx_detail(id) {
  if (typeof detail === "function") return detail(String(id));
  if (typeof __spiderDefault === "object" && __spiderDefault && __spiderDefault.detail) return __spiderDefault.detail(String(id));
  return "{}";
}
function __jx_search(wd, pg) {
  if (typeof search === "function") return search(String(wd), false, pg);
  if (typeof __spiderDefault === "object" && __spiderDefault && __spiderDefault.search) return __spiderDefault.search(String(wd), false, pg);
  return "{}";
}
function __jx_play(flag, id) {
  if (typeof play === "function") return play(String(flag), String(id), []);
  if (typeof __spiderDefault === "object" && __spiderDefault && __spiderDefault.play) return __spiderDefault.play(String(flag), String(id), []);
  return "{}";
}
function __jx_proxy(json) {
  var params = {};
  try { params = JSON.parse(json || "{}"); } catch (e) {}
  var result = null;
  if (typeof proxy === "function") result = proxy(params);
  else if (typeof __spiderDefault === "object" && __spiderDefault && __spiderDefault.proxy) result = __spiderDefault.proxy(params);
  return JSON.stringify(result == null ? [404, "text/plain", ""] : result);
}
function __jx_video(url) {
  if (typeof isVideo === "function") return !!isVideo(String(url));
  return false;
}
if (typeof cheerio === "undefined" || cheerio === null) { var cheerio = function(){}; }
if (typeof cheerio.jp !== "function") {
  cheerio.jp = function(path, obj) { return JSON.parse(host.jsonPath(String(path), JSON.stringify(obj))); };
}
if (typeof cheerio.jinja2 !== "function") {
  cheerio.jinja2 = function(template, data) { return host.jinja(String(template), JSON.stringify(data == null ? {} : data)); };
}
"""

    fun imports(source: String): List<JsImport> = importPattern.findAll(source).mapNotNull { match ->
        val url = match.groupValues[3]
        if (!usable(url)) return@mapNotNull null
        val named = match.groupValues[2].split(",").firstOrNull { it.isNotBlank() }
            ?.substringBefore(" as ")?.trim()
        val binding = match.groupValues[1].ifBlank { named.orEmpty() }.ifBlank { null }
        JsImport(binding, url)
    }.toList()

    fun assemble(entryUrl: String, entrySource: String, load: (String) -> String): String {
        val order = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        fun walk(base: String, source: String, depth: Int) {
            if (depth > 4) return
            imports(source).forEach { item ->
                val absolute = absoluteImport(base, item.url)
                if (!seen.add(absolute)) return@forEach
                val body = load(absolute)
                walk(absolute, body, depth + 1)
                order += render(body, item.binding)
            }
        }
        walk(entryUrl, entrySource, 0)
        return buildString {
            append(order.joinToString("\n"))
            append('\n')
            append(entry(entrySource))
            append('\n')
            append(CALLS)
        }
    }

    fun entry(source: String): String {
        var code = source
        imports(code).asReversed().forEach { item ->
            val quoted = Regex.escape(item.url)
            code = Regex("""import\s*(?:[^\s"'{};]+\s+from\s*)?(?:\{[^}]+\}\s*from\s*)?["']$quoted["']\s*;?""")
                .replace(code, "")
        }
        code = exportFunction.replace(code) { "function ${it.groupValues[1]}" }
        code = exportDefault.replace(code, "var __spiderDefault = ")
        code = exportList.replace(code, "")
        return code
    }

    internal fun render(source: String, binding: String?): String {
        val function = exportFunction.find(source)
        if (function != null) {
            val name = function.groupValues[1]
            val code = exportFunction.replace(source, "function $name")
            return iife(code, name, binding ?: name)
        }
        if (exportDefault.containsMatchIn(source)) {
            return iife(exportDefault.replace(source, "var __default = "), "__default", binding)
        }
        val list = exportList.find(source)
        if (list != null) {
            val pairs = list.groupValues[1].split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { spec ->
                val bits = spec.split(Regex("""\s+as\s+"""))
                bits[0].trim() to bits.getOrElse(1) { bits[0] }.trim()
            }
            val code = exportList.replace(source, "") + buildString {
                val defaultLocal = pairs.firstOrNull { it.second == "default" }?.first
                append(if (defaultLocal != null) "\nvar __default = $defaultLocal;\n" else "\nvar __default = {};\n")
                pairs.filter { it.second != "default" }.forEach { (local, exported) ->
                    append("__default[${quote(exported)}] = $local;\n")
                }
            }
            return iife(code, "__default", binding)
        }
        if (source.contains("CryptoJS") && source.contains("exports")) {
            val code = "var module={exports:{}};\nvar exports=module.exports;\n$source\nvar __default=module.exports;\n"
            return iife(code, "__default", binding ?: "CryptoJS")
        }
        return iife(source, "undefined", binding)
    }

    private fun iife(code: String, result: String, globalName: String?): String {
        val assign = if (globalName.isNullOrBlank()) "" else "var $globalName = "
        return "$assign(function(module, exports){\n$code\nreturn $result;\n})({exports:{}},{});\n"
    }

    private fun absoluteImport(base: String, spec: String): String {
        if (spec.startsWith("assets://")) {
            val name = spec.substringAfterLast("/")
            val dir = base.substringBeforeLast("/")
            return "$dir/$name"
        }
        return resolveAgainst(base, spec)
    }

    private fun usable(url: String): Boolean =
        url.startsWith("assets://") ||
            url.startsWith("http://") ||
            url.startsWith("https://") ||
            url.startsWith(".") ||
            url.startsWith("/") ||
            url.endsWith(".js")

    private fun quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
