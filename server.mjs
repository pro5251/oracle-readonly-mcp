import fs from 'node:fs';
import { execFile } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { z } from 'zod';

const root = fileURLToPath(new URL('.', import.meta.url));
const java = process.env.ORACLE_MCP_JAVA ?? (process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', 'java') : 'java');
const driver = process.env.ORACLE_JDBC_JAR;
const ident = z.string().trim().toUpperCase().regex(/^[A-Z][A-Z0-9_$#]{0,127}$/);
const owner = ident.optional();
const named = { owner, name: ident };
const definitions = {
    database_info: { description: 'Read Oracle database version. No SQL execution input.', schema: {} },
    list_objects: { description: 'List accessible objects across schemas, or filter by owner. Use the returned owner for subsequent tools: the login user may own no objects. Pattern uses SQL LIKE wildcards. At most 500 objects.', schema: { owner, pattern: z.string().max(128).toUpperCase().optional(), type: z.enum(['TABLE', 'VIEW', 'PROCEDURE', 'FUNCTION', 'PACKAGE', 'PACKAGE BODY', 'TRIGGER', 'SEQUENCE', 'SYNONYM']).optional(), limit: z.number().int().min(1).max(500).default(100) } },
    table_columns: { description: 'Read column names, types and nullability for an accessible table or view.', schema: named },
    table_constraints: { description: 'Read table constraint names, columns and referenced constraints.', schema: named },
    procedure_source: { description: 'Read SP/function/package/trigger/type source without executing it. Use line pagination; at most 1000 lines per call. Wrapped source remains wrapped.', schema: { ...named, type: z.enum(['PROCEDURE', 'FUNCTION', 'PACKAGE', 'PACKAGE BODY', 'TRIGGER', 'TYPE', 'TYPE BODY']), start_line: z.number().int().min(1).max(1000000).default(1), limit: z.number().int().min(1).max(1000).default(200) } },
    procedure_arguments: { description: 'Read SP or package parameters. For a package set name to its name and optionally routine to one member. Does not execute routines.', schema: { ...named, routine: ident.optional() } },
    table_rows: { description: 'Read at most 100 sample rows from an ordinary base table, in unspecified order. Returns stored scalar columns only; excludes virtual, hidden, LOB, RAW, object columns. No views, synonyms, external or temporary tables. No SQL/filter expressions accepted.', schema: { ...named, limit: z.number().int().min(1).max(100).default(20) } },
};
export function validate(tool, input) {
    if (!Object.hasOwn(definitions, tool)) throw new Error('Unknown read-only tool');
    return z.object(definitions[tool].schema).strict().parse(input);
}
export function loadCredentials(configFile = process.env.ORACLE_CONFIG_FILE) {
    if (!configFile) throw new Error('ORACLE_CONFIG_FILE is required');
    let config;
    try { config = JSON.parse(fs.readFileSync(configFile, 'utf8').replace(/^\uFEFF/, '')); }
    catch { throw new Error('Cannot read valid Oracle connection JSON'); }
    const result = { user: config.user, password: config.password, dsn: config.connectString };
    if (Object.values(result).some(v => typeof v !== 'string' || !v || /[\r\n\0]/.test(v))) throw new Error('Oracle config requires nonempty user, password and connectString strings without control line separators');
    return result;
}
export async function invoke(tool, input) {
    const params = validate(tool, input);
    if (!driver || !fs.existsSync(driver)) throw new Error('ORACLE_JDBC_JAR must point to an installed Oracle JDBC driver');
    const payload = Object.entries({ ...loadCredentials(), ...params }).map(([k, v]) => `${k}=${Buffer.from(String(v)).toString('base64')}`).join('\n') + '\n';
    return new Promise((resolve, reject) => {
        const child = execFile(java, ['-cp', [path.join(root, 'build'), path.resolve(driver)].join(path.delimiter), 'OracleReadOnly', tool], { timeout: 45000, maxBuffer: 4 * 1024 * 1024 }, (error, stdout, stderr) => {
            if (error) return reject(new Error(stderr.trim() || 'Oracle read-only query timed out or failed'));
            try { resolve(JSON.parse(stdout)); } catch { reject(new Error('Invalid Oracle tool response')); }
        });
        child.stdin.on('error', () => {});
        child.stdin.end(payload);
    });
}
if (process.argv[1] === fileURLToPath(import.meta.url)) {
    const server = new McpServer({ name: 'oracle-readonly-mcp', version: '0.1.0' });
    for (const [name, def] of Object.entries(definitions)) {
        server.registerTool(name, { description: def.description, inputSchema: def.schema, annotations: { readOnlyHint: true, destructiveHint: false, openWorldHint: false } }, async input => {
            try { return { content: [{ type: 'text', text: JSON.stringify(await invoke(name, input)) }] }; }
            catch (error) { return { isError: true, content: [{ type: 'text', text: error.message }] }; }
        });
    }
    await server.connect(new StdioServerTransport());
}
