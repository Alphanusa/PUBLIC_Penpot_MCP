// Smoke test for a running MCP server (the release image): waits until /mcp
// answers, then lists the tools with a stateless MCP request (same shape as
// packages/server/src/PenpotMcpServer.http.test.ts) and checks the expected ones.
//
// Usage: node smoke-image.mjs <base-url> [expect-engine: true|false]

const [baseUrl = "http://127.0.0.1:4401", expectEngineArg = "false"] = process.argv.slice(2);
const expectEngine = expectEngineArg === "true";
const PROTOCOL = "2026-07-28";

async function listTools() {
    const res = await fetch(`${baseUrl}/mcp`, {
        method: "POST",
        headers: {
            "Content-Type": "application/json",
            Accept: "application/json, text/event-stream",
            "MCP-Protocol-Version": PROTOCOL,
            "Mcp-Method": "tools/list",
        },
        body: JSON.stringify({
            jsonrpc: "2.0",
            id: 1,
            method: "tools/list",
            params: {
                _meta: {
                    "io.modelcontextprotocol/protocolVersion": PROTOCOL,
                    "io.modelcontextprotocol/clientInfo": { name: "release-smoke", version: "1" },
                    "io.modelcontextprotocol/clientCapabilities": {},
                },
            },
        }),
    });
    const text = await res.text();
    if (!res.ok) throw new Error(`HTTP ${res.status}: ${text.slice(0, 500)}`);
    // JSON body, or an SSE stream whose data: lines carry the JSON-RPC message
    const json = text.trimStart().startsWith("{")
        ? JSON.parse(text)
        : JSON.parse(
              text
                  .split("\n")
                  .filter((line) => line.startsWith("data:"))
                  .map((line) => line.slice(5).trim())
                  .find((data) => data.startsWith("{")) ?? "{}"
          );
    if (json.error) throw new Error(`MCP error: ${JSON.stringify(json.error)}`);
    return (json.result?.tools ?? []).map((tool) => tool.name);
}

const deadline = Date.now() + 60_000;
let tools;
let lastError;
while (Date.now() < deadline) {
    try {
        tools = await listTools();
        break;
    } catch (error) {
        lastError = error;
        await new Promise((resolve) => setTimeout(resolve, 1000));
    }
}
if (!tools) {
    console.error(`MCP server did not answer at ${baseUrl}/mcp: ${lastError}`);
    process.exit(1);
}

const required = ["execute_code", "high_level_overview", "export_shape"];
const browserFree = ["list_files", "get_page_overview", "render_shape", "edit_page"];
const missing = required.filter((name) => !tools.includes(name));
const hasEngine = browserFree.every((name) => tools.includes(name));

console.log(JSON.stringify({ tools, browserFree: hasEngine }, null, 2));
if (missing.length) {
    console.error(`missing tools: ${missing.join(", ")}`);
    process.exit(1);
}
if (expectEngine && !hasEngine) {
    console.error("engine expected in the image, but the browser-free tools are not registered");
    process.exit(1);
}
console.log("smoke test passed");
