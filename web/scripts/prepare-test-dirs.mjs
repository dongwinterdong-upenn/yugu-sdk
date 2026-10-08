#!/usr/bin/env node
// Creates the output directories of the test reporters.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
fs.rmSync(path.join(ROOT, 'test-results'), { recursive: true, force: true });
fs.mkdirSync(path.join(ROOT, 'test-results'), { recursive: true });
