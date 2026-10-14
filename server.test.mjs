import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { execFileSync } from 'node:child_process';
import { validate, loadCredentials } from './server.mjs';

test('rejects executable tools and extra SQL/credential arguments', () => {
    for (const name of ['run-sql', 'run-sqlcl', 'execute', '__proto__', 'constructor']) {
        assert.throws(() => validate(name, {}));
    }
    for (const extra of [{ sql: 'DELETE FROM T' }, { password: 'x' }, { dsn: 'other' }]) {
        assert.throws(() => validate('table_rows', { name: 'T', ...extra }));
    }
});
test('identifiers cannot inject expressions, SQL, links or quoted names', () => {
    for (const name of ['T; DELETE FROM T', 'T@LINK', 'T WHERE 1=1', 'F()', '"T"', 'SCHEMA.T', 'T--']) {
        assert.throws(() => validate('table_rows', { name }));
        assert.throws(() => validate('procedure_source', { name: 'P', owner: name, type: 'PROCEDURE' }));
    }
    assert.equal(validate('table_rows', { name: ' t_1 ' }).name, 'T_1');
});
test('bounds and source types are enforced', () => {
    for (const limit of [0, -1, 101, 1.5, '20']) assert.throws(() => validate('table_rows', { name: 'T', limit }));
    assert.throws(() => validate('procedure_source', { name: 'P', type: 'BEGIN', start_line: 1 }));
    assert.throws(() => validate('procedure_arguments', { name: 'PKG', routine: 'P()' }));
});
test('JDBC runner independently rejects arbitrary commands', () => {
    assert.throws(() => execFileSync(process.env.ORACLE_MCP_JAVA ?? (process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', 'java') : 'java'), ['-cp', 'build', 'OracleReadOnly', 'run-sql'], { input: '', stdio: ['pipe', 'pipe', 'pipe'] }), error => {
        assert.match(error.stderr.toString(), /Unknown tool/);
        return true;
    });
});

test('invalid connection JSON never leaks credential contents', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'oracle-mcp-test-'));
    const config = path.join(dir, 'connection.json');
    try {
        fs.writeFileSync(config, '{"password":"sensitive-value", invalid}');
        assert.throws(() => loadCredentials(config), error => {
            assert.equal(error.message, 'Cannot read valid Oracle connection JSON');
            return true;
        });
        fs.writeFileSync(config, JSON.stringify({user:'reader',password:'a;=secret',connectString:'host:1521/service'}));
        assert.equal(loadCredentials(config).password, 'a;=secret');
        fs.writeFileSync(config, JSON.stringify({user:'reader',password:'secret'}));
        assert.throws(() => loadCredentials(config));
    } finally { fs.rmSync(dir, {recursive:true,force:true}); }
});
