import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
const root = fileURLToPath(new URL('..', import.meta.url));
const javac = process.env.ORACLE_MCP_JAVAC ?? (process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', 'javac') : 'javac');
fs.mkdirSync(path.join(root, 'build'), {recursive:true});
execFileSync(javac, ['--release', '17', '-d', path.join(root, 'build'), path.join(root, 'OracleReadOnly.java')], {stdio:'inherit'});
